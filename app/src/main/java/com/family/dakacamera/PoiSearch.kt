package com.family.dakacamera

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos

/** 附近地点 */
data class PoiItem(
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val distanceMeters: Double,
) {
    /** 水印地址行使用的文本 */
    fun watermarkText(): String =
        if (address.isBlank() || address == name || name.contains(address)) name
        else "$name($address)"
}

/**
 * 300 米内地点搜索。
 *
 * 数据源:
 *  - 配置了 `daka.amapKey`(或环境变量 DAKA_AMAP_KEY)时用高德 Web 服务 API(国内最准);
 *  - 否则用 OpenStreetMap Nominatim(免注册,国内乡镇覆盖可能较少)。
 *
 * 防卡死设计:全部网络请求在后台线程;连接/读取超时;分页与结果数有上限;
 * 支持取消(关闭进行中的连接);回调切回主线程;同一时间只有一个搜索。
 */
object PoiSearch {

    const val RADIUS_METERS = 300.0

    private const val AMAP_PAGE_SIZE = 25
    private const val AMAP_MAX_RESULTS = 100
    private const val OSM_LIMIT = 40
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000

    private const val USER_AGENT =
        "DakaCamera/1.1 (https://github.com/zetdgreat/daka-camera)"

    private val mainHandler = Handler(Looper.getMainLooper())

    interface Callback {
        fun onResult(items: List<PoiItem>, truncated: Boolean)
        fun onError(message: String)
    }

    /** 用于取消正在进行的搜索(关闭连接,丢弃回调)。 */
    class Cancellable internal constructor() {
        @Volatile
        internal var cancelled = false

        @Volatile
        internal var connection: HttpURLConnection? = null

        fun cancel() {
            cancelled = true
            runCatching { connection?.disconnect() }
        }
    }

    fun search(lat: Double, lng: Double, callback: Callback): Cancellable {
        val cancellable = Cancellable()
        Thread {
            try {
                val (items, truncated) =
                    if (BuildConfig.AMAP_KEY.isNotBlank()) searchAmap(cancellable, lat, lng)
                    else searchOsm(cancellable, lat, lng)
                if (cancellable.cancelled) return@Thread
                mainHandler.post {
                    if (!cancellable.cancelled) callback.onResult(items, truncated)
                }
            } catch (t: Throwable) {
                if (cancellable.cancelled) return@Thread
                val message = friendlyError(t)
                mainHandler.post {
                    if (!cancellable.cancelled) callback.onError(message)
                }
            }
        }.apply { isDaemon = true }.start()
        return cancellable
    }

    // ---------------- OpenStreetMap Nominatim ----------------

    private fun searchOsm(
        c: Cancellable,
        lat: Double,
        lng: Double,
    ): Pair<List<PoiItem>, Boolean> {
        val dLat = RADIUS_METERS / 111_320.0
        val dLng = RADIUS_METERS / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val viewbox = String.format(
            Locale.US, "%f,%f,%f,%f",
            lng - dLng, lat + dLat, lng + dLng, lat - dLat
        )
        val url = URL(
            "https://nominatim.openstreetmap.org/search?format=jsonv2&bounded=1" +
                    "&viewbox=$viewbox&limit=$OSM_LIMIT&addressdetails=1&namedetails=1" +
                    "&accept-language=zh-CN&dedupe=1"
        )
        val body = get(
            c, url,
            mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json")
        )
        val arr = JSONArray(body)
        val items = ArrayList<PoiItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pLat = o.optString("lat").toDoubleOrNull() ?: continue
            val pLng = o.optString("lon").toDoubleOrNull() ?: continue
            val distance = GeoUtils.distanceMeters(lat, lng, pLat, pLng)
            if (distance > RADIUS_METERS + 0.5) continue

            val display = o.optString("display_name").trim()
            val name = o.optJSONObject("namedetails")?.optString("name")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: o.optString("name").trim().takeIf { it.isNotBlank() }
                ?: display.substringBefore(',').trim().ifBlank { "未命名地点" }
            items.add(PoiItem(name, display, pLat, pLng, distance))
        }
        items.sortBy { it.distanceMeters }
        val dedup = items.distinctBy { "${it.name}|${it.lat}|${it.lng}" }
        return dedup to false
    }

    // ---------------- 高德 Web 服务「周边搜索」 ----------------

    private fun searchAmap(
        c: Cancellable,
        lat: Double,
        lng: Double,
    ): Pair<List<PoiItem>, Boolean> {
        val key = URLEncoder.encode(BuildConfig.AMAP_KEY, "UTF-8")
        val items = ArrayList<PoiItem>()
        var total = 0
        var page = 1
        val maxPages = ceil(AMAP_MAX_RESULTS.toDouble() / AMAP_PAGE_SIZE).toInt()

        while (page <= maxPages) {
            val url = URL(
                "https://restapi.amap.com/v3/place/around?key=$key" +
                        "&location=$lng,$lat&radius=${RADIUS_METERS.toInt()}" +
                        "&sortrule=distance&offset=$AMAP_PAGE_SIZE&page=$page&extensions=base"
            )
            val body = get(c, url, mapOf("Accept" to "application/json"))
            val root = JSONObject(body)
            if (root.optString("status") != "1") {
                val info = root.optString("info").ifBlank { "高德接口返回错误" }
                throw IllegalStateException(info)
            }
            total = root.optString("count").toIntOrNull() ?: 0
            val pois = root.optJSONArray("pois") ?: JSONArray()
            if (pois.length() == 0) break

            for (i in 0 until pois.length()) {
                val poi = pois.optJSONObject(i) ?: continue
                val parts = poi.optString("location").split(',')
                if (parts.size != 2) continue
                val pLng = parts[0].toDoubleOrNull() ?: continue
                val pLat = parts[1].toDoubleOrNull() ?: continue
                val distance = GeoUtils.distanceMeters(lat, lng, pLat, pLng)
                if (distance > RADIUS_METERS + 0.5) continue

                val name = poi.optString("name").trim().ifBlank { "未命名地点" }
                val address = poi.optString("address").trim().let {
                    if (it == "[]") "" else it
                }
                items.add(PoiItem(name, address, pLat, pLng, distance))
            }

            if (items.size >= total) break
            page++
        }

        items.sortBy { it.distanceMeters }
        val dedup = items.distinctBy { "${it.name}|${it.lat}|${it.lng}" }
        return dedup.take(AMAP_MAX_RESULTS) to (total > AMAP_MAX_RESULTS)
    }

    // ---------------- HTTP ----------------

    private fun get(
        c: Cancellable,
        url: URL,
        headers: Map<String, String>,
    ): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            c.connection = conn
            if (c.cancelled) throw InterruptedException()
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.connection = null
            runCatching { conn?.disconnect() }
        }
    }

    private fun friendlyError(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException -> "请求超时,请检查网络后重试"
        is java.net.UnknownHostException -> "网络不可用,请检查网络后重试"
        is java.net.ConnectException -> "无法连接服务器,请检查网络后重试"
        is javax.net.ssl.SSLException -> "网络连接失败,请重试"
        is java.io.IOException -> "网络连接失败,请重试"
        else -> t.message?.takeIf { it.isNotBlank() } ?: "搜索失败,请重试"
    }
}
