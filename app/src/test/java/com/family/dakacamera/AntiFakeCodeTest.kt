package com.family.dakacamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AntiFakeCodeTest {

    private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    @Test
    fun roundTripPositiveCoords() {
        val t = 1_790_000_000L // 2026 年
        val code = AntiFakeCode.encode(t, 39.9042, 116.4074)
        val r = AntiFakeCode.decode(code)
        assertNotNull("应能解码: $code", r)
        r!!
        assertEquals(t / 60, r.epochSec / 60) // 分钟级一致
        assertEquals(39.9042, r.lat, 1e-5)
        assertEquals(116.4074, r.lng, 1e-5)
    }

    @Test
    fun roundTripNegativeCoords() {
        val t = 1_790_000_000L
        val r = AntiFakeCode.decode(AntiFakeCode.encode(t, -33.8688, 151.2093))!!
        assertEquals(-33.8688, r.lat, 1e-5)
        assertEquals(151.2093, r.lng, 1e-5)
    }

    @Test
    fun roundTripNoLocationSentinel() {
        // 无定位时 App 用 (0,0) 作哨兵值,必须能原样往返
        val r = AntiFakeCode.decode(AntiFakeCode.encode(1_790_000_000L, 0.0, 0.0))!!
        assertEquals(0.0, r.lat, 0.0)
        assertEquals(0.0, r.lng, 0.0)
    }

    @Test
    fun codeFormatIs20CharsIn5Groups() {
        val code = AntiFakeCode.encode(1_790_000_000L, 39.9042, 116.4074)
        val groups = code.split("-")
        assertEquals(5, groups.size)
        groups.forEach { assertEquals(4, it.length) }
        groups.forEach { g -> g.forEach { ch -> assert(alphabet.indexOf(ch) >= 0) } }
    }

    @Test
    fun tamperedCodeRejected() {
        val clean = AntiFakeCode.encode(1_790_000_000L, 39.9042, 116.4074).replace("-", "")
        for (pos in intArrayOf(0, 5, 12, 19)) {
            val orig = clean[pos]
            val replaced = if (orig == 'Z') 'Y' else alphabet[alphabet.indexOf(orig) + 1]
            val mutated = clean.substring(0, pos) + replaced + clean.substring(pos + 1)
            assertNull("位置 $pos 篡改后应判为无效", AntiFakeCode.decode(mutated))
        }
    }

    @Test
    fun inputTolerance_lowercaseAndSpaces() {
        val t = 1_790_000_000L
        val code = AntiFakeCode.encode(t, 39.9042, 116.4074)
        val expected = AntiFakeCode.decode(code)!!
        assertEquals(expected, AntiFakeCode.decode(code.lowercase()))
        assertEquals(expected, AntiFakeCode.decode("  $code  "))
        assertEquals(expected, AntiFakeCode.decode(code.replace("-", " ")))
    }

    @Test
    fun inputTolerance_confusableChars() {
        // 找一个包含 1 或 0 的合法码,把 1 换成 I/L、0 换成 O 后仍应可解析(Crockford 容错)
        var code: String? = null
        var t = 1_790_000_000L
        while (code == null) {
            val c = AntiFakeCode.encode(t, 39.9042, 116.4074)
            if (c.contains('1') || c.contains('0')) code = c else t += 60
        }
        val original = AntiFakeCode.decode(code)!!
        val withConfusables = code.replace('1', 'I').replace('0', 'O')
        assertEquals(original, AntiFakeCode.decode(withConfusables))
        val withL = code.replace('1', 'L')
        assertEquals(original, AntiFakeCode.decode(withL))
    }

    @Test
    fun invalidInputsRejected() {
        assertNull(AntiFakeCode.decode(""))
        assertNull(AntiFakeCode.decode("AAAA"))
        assertNull(AntiFakeCode.decode("AB12-CD34-EF56-GH78-JK9"))    // 19 字符
        assertNull(AntiFakeCode.decode("AB12-CD34-EF56-GH78-JK901"))  // 21 字符
        assertNull(AntiFakeCode.decode("UUUU-UUUU-UUUU-UUUU-UUUU"))   // U 不在字母表
        assertNull(AntiFakeCode.decode("!!!!-!!!!-!!!!-!!!!-!!!!"))   // 非法字符
    }
}
