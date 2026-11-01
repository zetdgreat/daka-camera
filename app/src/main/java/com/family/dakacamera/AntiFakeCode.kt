package com.family.dakacamera

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/**
 * 防伪码编解码 —— 纯 JVM 实现,不依赖任何 Android API,可离线单元测试。
 *
 * 码流共 100 bit,恰好编码为 20 个 Crockford Base32 字符(展示为 5 组 × 4 字符):
 *
 *   密文 C(96 bit,12 字节)
 *     payload = [版本 4bit | 自 2020-01-01 起的分钟数 28bit] + lat×1e5(int32 大端) + lng×1e5(int32 大端)
 *     混淆方式:C[0..3] = payload[0..3] XOR HMAC(K,"DKV1")[0..3]
 *              C[4..11] = payload[4..11] XOR HMAC(K,"DKV1"||C[0..3])[0..7]
 *   校验 chk(4 bit)= HMAC(K,"MAC"||C) 首字节低 4 位
 *
 * 解码校验链:校验位(1/16 漏检)→ 版本号(1/16)→ 坐标范围(±90/±180),
 * 随机串被误判为"有效"的概率低于百万分之一,足以区分"手输错"与"合法码"。
 *
 * ⚠️ 如实说明:密钥硬编码在 APK 内,反编译 APK 可提取密钥并伪造任意码,
 * 本方案属于「威慑级防伪」(防普通改图、防时间造假),不是密码学意义上的不可伪造。
 * 同时,时间取自拍摄设备的系统时钟 —— 手动修改系统时间可让水印与码同时变成假时间,设计上无解。
 */
object AntiFakeCode {

    private const val VERSION = 1

    /** 自 2020-01-01T00:00:00Z 起的分钟数 */
    private const val MINUTES_2020 = 26_297_280L

    /** Crockford Base32 字母表:去掉易混淆的 I / L / O / U */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private const val CODE_CHARS = 20
    private const val BITS = CODE_CHARS * 5 // 100

    private val KEY: ByteArray = MessageDigest.getInstance("SHA-256")
        .digest("DakaCamera-v1-family-checkin-7Q2mZk48Px".toByteArray(Charsets.UTF_8))

    data class Result(val epochSec: Long, val lat: Double, val lng: Double)

    /** 生成防伪码,返回已分组的显示串(如 "AB12-CD34-EF56-GH78-JK90")。 */
    fun encode(epochSec: Long, lat: Double, lng: Double): String =
        toBase32(codeBits(epochSec, lat, lng)).chunked(4).joinToString("-")

    /**
     * 校验并解码。非法 / 损坏的码返回 null。
     * 输入容错:忽略连字符与空白,大小写不敏感,I/L 视作 1,O 视作 0(对应 Crockford 规范)。
     */
    fun decode(code: String): Result? {
        // ---- 规范化:先做混淆字符映射,再校验字符合法性 ----
        val normalized = StringBuilder(CODE_CHARS)
        for (raw in code.uppercase()) {
            val ch = when (raw) {
                'I', 'L' -> '1'
                'O' -> '0'
                else -> raw
            }
            when {
                ALPHABET.indexOf(ch) >= 0 -> normalized.append(ch)
                ch == '-' || ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' -> Unit
                else -> return null // 含字母表外的非法字符(如 U)
            }
        }
        val clean = normalized.toString()
        if (clean.length != CODE_CHARS) return null

        // ---- 解 Base32 为 100 bit ----
        val bits = IntArray(BITS)
        var bitIdx = 0
        for (ch in clean) {
            val v = ALPHABET.indexOf(ch)
            for (b in 4 downTo 0) bits[bitIdx++] = (v ushr b) and 1
        }

        val c = ByteArray(12)
        for (i in 0 until 96) {
            if (bits[i] == 1) c[i / 8] = (c[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
        }
        var chk = 0
        for (i in 96 until BITS) chk = (chk shl 1) or bits[i]

        // ---- ① 校验位 ----
        if ((hmac("MAC".toByteArray(Charsets.UTF_8), c)[0].toInt() and 0x0F) != chk) return null

        // ---- 解密 ----
        val s0 = hmac("DKV1".toByteArray(Charsets.UTF_8))
        val p = ByteArray(12)
        for (i in 0..3) p[i] = (c[i].toInt() xor s0[i].toInt()).toByte()
        val s1 = hmac("DKV1".toByteArray(Charsets.UTF_8), c.copyOfRange(0, 4))
        for (i in 4..11) p[i] = (c[i].toInt() xor s1[i - 4].toInt()).toByte()

        // ---- ② 版本号 ----
        val version = (p[0].toInt() ushr 4) and 0x0F
        if (version != VERSION) return null

        val ts28 = ((p[0].toInt() and 0x0F) shl 24) or
                ((p[1].toInt() and 0xFF) shl 16) or
                ((p[2].toInt() and 0xFF) shl 8) or
                (p[3].toInt() and 0xFF)
        val lat = int32BE(p, 4) / 1e5
        val lng = int32BE(p, 8) / 1e5

        // ---- ③ 坐标范围 ----
        if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) return null

        return Result((ts28 + MINUTES_2020) * 60L, lat, lng)
    }

    // ---------------- 内部实现 ----------------

    private fun codeBits(epochSec: Long, lat: Double, lng: Double): IntArray {
        val ts28 = epochSec / 60L - MINUTES_2020
        val payload = ByteArray(12)
        writeInt32BE(payload, 0, (VERSION shl 28) or (ts28.toInt() and 0x0FFFFFFF))
        writeInt32BE(payload, 4, (lat * 1e5).roundToInt())
        writeInt32BE(payload, 8, (lng * 1e5).roundToInt())

        val s0 = hmac("DKV1".toByteArray(Charsets.UTF_8))
        val c = ByteArray(12)
        for (i in 0..3) c[i] = (payload[i].toInt() xor s0[i].toInt()).toByte()
        val s1 = hmac("DKV1".toByteArray(Charsets.UTF_8), c.copyOfRange(0, 4))
        for (i in 4..11) c[i] = (payload[i].toInt() xor s1[i - 4].toInt()).toByte()

        val chk = hmac("MAC".toByteArray(Charsets.UTF_8), c)[0].toInt() and 0x0F

        val bits = IntArray(BITS)
        for (i in 0 until 96) bits[i] = (c[i / 8].toInt() ushr (7 - i % 8)) and 1
        for (i in 0 until 4) bits[96 + i] = (chk ushr (3 - i)) and 1
        return bits
    }

    private fun toBase32(bits: IntArray): String {
        val sb = StringBuilder(CODE_CHARS)
        var acc = 0
        var n = 0
        for (b in bits) {
            acc = (acc shl 1) or b
            n++
            if (n == 5) {
                sb.append(ALPHABET[acc])
                acc = 0
                n = 0
            }
        }
        return sb.toString()
    }

    private fun hmac(vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(KEY, "HmacSHA256"))
        for (part in parts) mac.update(part)
        return mac.doFinal()
    }

    private fun writeInt32BE(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value ushr 24).toByte()
        dst[offset + 1] = (value ushr 16).toByte()
        dst[offset + 2] = (value ushr 8).toByte()
        dst[offset + 3] = value.toByte()
    }

    private fun int32BE(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 24) or
                ((src[offset + 1].toInt() and 0xFF) shl 16) or
                ((src[offset + 2].toInt() and 0xFF) shl 8) or
                (src[offset + 3].toInt() and 0xFF)
}
