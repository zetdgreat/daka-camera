package com.family.dakacamera

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.ceil

/** 附近地点 */
data class PoiItem(
    val name: String,
    val address: String,        // 简短地址(如道路+门牌),用于兜底/辅助展示
    val lat: Double,
    val lng: Double,
    val distanceMeters: Double,
    val fullAddress: String = address, // 完整地址(省市区+道路+门牌)
) {
    /** 水印地址行使用的文本:完整地址 + 地点名 */
    fun watermarkText(): String = when {
        fullAddress.isBlank() -> name
        fullAddress.contains(name) -> fullAddress
        else -> fullAddress + name
    }
}

/**
 * 指定半径内的地点搜索(半径可在设置中拖拽选择 5~300 米)。
 *
 * 数据源:
 *  - App 内填写了高德 Key(或私有构建时配置了 daka.amapKey)时用高德 Web 服务 API(国内最准);
 *  - 否则用 OpenStreetMap Nominatim(免注册,国内乡镇覆盖可能较少)。
 *
 * 防卡死设计:全部网络请求在后台线程;连接/读取超时;分页与结果数有上限;
 * 支持取消(关闭进行中的连接);回调切回主线程;同一时间只有一个搜索。
 */
object PoiSearch {

    private const val AMAP_PAGE_SIZE = 25
    private const val AMAP_MAX_RESULTS = 200
    private const val OSM_MAX_RESULTS = 150
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val OVERPASS_READ_TIMEOUT_MS = 20_000

    /** Overpass 镜像:主节点优先,失败后切备用节点 */
    private val OVERPASS_ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    private const val USER_AGENT =
        "DakaCamera/1.1 (https://github.com/zetdgreat/daka-camera)"

    private val mainHandler = Handler(Looper.getMainLooper())

    interface Callback {
        fun onResult(items: List<PoiItem>, truncated: Boolean, usedFallback: Boolean)
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

    fun search(
        context: Context,
        lat: Double,
        lng: Double,
        radiusMeters: Int,
        callback: Callback,
    ): Cancellable {
        val cancellable = Cancellable()
        val amapKey = AppSettings.resolveAmapKey(context)
        val radius = radiusMeters.toDouble().coerceIn(5.0, 300.0)
        Thread {
            try {
                var usedFallback = false
                val (items, truncated) = if (amapKey.isNotBlank()) {
                    try {
                        searchAmap(cancellable, amapKey, lat, lng, radius)
                    } catch (t: Throwable) {
                        if (cancellable.cancelled) throw t
                        // 高德 Key 过期 / 不可用 / 请求失败:自动回退默认数据源
                        usedFallback = true
                        try {
                            searchOsm(cancellable, lat, lng, radius)
                        } catch (e: Throwable) {
                            if (cancellable.cancelled) throw e
                            throw IllegalStateException(
                                "高德 Key 不可用,且 OpenStreetMap 服务暂时不可用。" +
                                        "请检查 Key,或稍后重试。"
                            )
                        }
                    }
                } else {
                    try {
                        searchOsm(cancellable, lat, lng, radius)
                    } catch (e: Throwable) {
                        if (cancellable.cancelled) throw e
                        throw IllegalStateException(
                            "OpenStreetMap 服务暂时不可用。" +
                                    "可在设置中填写免费的高德 Key,或稍后重试。"
                        )
                    }
                }
                if (cancellable.cancelled) return@Thread
                mainHandler.post {
                    if (!cancellable.cancelled) {
                        callback.onResult(items, truncated, usedFallback)
                    }
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

    // ---------------- OpenStreetMap(Overpass API,免注册) ----------------

    private fun searchOsm(
        c: Cancellable,
        lat: Double,
        lng: Double,
        radius: Double,
    ): Pair<List<PoiItem>, Boolean> {
        val radiusInt = radius.toInt()
        val query = "[out:json][timeout:15];" +
                "(node(around:$radiusInt,$lat,$lng)[name];" +
                "way(around:$radiusInt,$lat,$lng)[name];" +
                "relation(around:$radiusInt,$lat,$lng)[name];);" +
                "out center $OSM_MAX_RESULTS;"
        val encoded = URLEncoder.encode(query, "UTF-8")

        var lastError: Throwable? = null
        for (base in OVERPASS_ENDPOINTS) {
            if (c.cancelled) throw InterruptedException()
            try {
                val body = get(
                    c,
                    URL("$base?data=$encoded"),
                    mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"),
                    readTimeoutMs = OVERPASS_READ_TIMEOUT_MS,
                )
                return parseOverpass(body, lat, lng, radius)
            } catch (t: Throwable) {
                if (c.cancelled) throw t
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("附近地点搜索失败")
    }

    private fun parseOverpass(
        body: String,
        lat: Double,
        lng: Double,
        radius: Double,
    ): Pair<List<PoiItem>, Boolean> {
        val root = JSONObject(body)
        val elements = root.optJSONArray("elements") ?: JSONArray()
        val items = ArrayList<PoiItem>()
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val tags = element.optJSONObject("tags") ?: continue
            val name = tags.optString("name").trim()
            if (name.isBlank()) continue
            val position = elementPosition(element) ?: continue
            val distance = GeoUtils.distanceMeters(lat, lng, position.first, position.second)
            if (distance > radius + 0.5) continue
            items.add(
                PoiItem(
                    name = name,
                    address = osmAddress(tags),
                    lat = position.first,
                    lng = position.second,
                    distanceMeters = distance,
                )
            )
        }
        items.sortBy { it.distanceMeters }
        val dedup = items.distinctBy { "${it.name}|${it.lat}|${it.lng}" }
        val truncated = elements.length() >= OSM_MAX_RESULTS
        return dedup.take(OSM_MAX_RESULTS) to truncated
    }

    /** 节点取 lat/lon,way/relation 取 center */
    private fun elementPosition(element: JSONObject): Pair<Double, Double>? {
        if (element.has("lat") && element.has("lon")) {
            val lat = element.optDouble("lat", Double.NaN)
            val lon = element.optDouble("lon", Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) return lat to lon
        }
        val center = element.optJSONObject("center") ?: return null
        val lat = center.optDouble("lat", Double.NaN)
        val lon = center.optDouble("lon", Double.NaN)
        return if (!lat.isNaN() && !lon.isNaN()) lat to lon else null
    }

    private fun osmAddress(tags: JSONObject): String {
        val province = tags.optString("addr:province").trim()
        val city = tags.optString("addr:city").trim()
        val district = tags.optString("addr:district").trim()
        val suburb = tags.optString("addr:suburb").trim()
        val street = tags.optString("addr:street").trim()
        val number = tags.optString("addr:housenumber").trim()
        val parts = ArrayList<String>(6)
        fun addUnique(value: String) {
            if (value.isNotBlank() && parts.lastOrNull() != value) parts.add(value)
        }
        addUnique(province)
        addUnique(city)
        addUnique(district)
        addUnique(suburb)
        if (street.isNotBlank()) addUnique(if (number.isNotBlank()) "$street$number" else street)
        return parts.joinToString("")
    }

    // ---------------- 高德 Web 服务「周边搜索」 ----------------

    private fun searchAmap(
        c: Cancellable,
        apiKey: String,
        lat: Double,
        lng: Double,
        radius: Double,
    ): Pair<List<PoiItem>, Boolean> {
        val key = URLEncoder.encode(apiKey, "UTF-8")
        val items = ArrayList<PoiItem>()
        var total = 0
        var page = 1
        val maxPages = ceil(AMAP_MAX_RESULTS.toDouble() / AMAP_PAGE_SIZE).toInt()

        // 高德使用 GCJ-02,查询前先转换中心点坐标
        val gcjCenter = GeoUtils.wgs84ToGcj02(lat, lng)
        val queryLat = gcjCenter[0]
        val queryLng = gcjCenter[1]

        while (page <= maxPages) {
            val url = URL(
                "https://restapi.amap.com/v3/place/around?key=$key" +
                        "&location=$queryLng,$queryLat&radius=${radius.toInt()}" +
                        "&sortrule=distance&offset=$AMAP_PAGE_SIZE&page=$page&extensions=base"
            )
            val root = getAmapJson(c, url)
            total = root.optString("count").toIntOrNull() ?: 0
            val pois = root.optJSONArray("pois") ?: JSONArray()
            if (pois.length() == 0) break

            for (i in 0 until pois.length()) {
                val poi = pois.optJSONObject(i) ?: continue
                val parts = poi.optString("location").split(',')
                if (parts.size != 2) continue
                val pLng = parts[0].toDoubleOrNull() ?: continue
                val pLat = parts[1].toDoubleOrNull() ?: continue

                // 高德返回 GCJ-02,转回 WGS-84 后再保存/计算距离,
                // 保证水印坐标、防伪码与自动定位使用同一坐标系
                val wgs = GeoUtils.gcj02ToWgs84(pLat, pLng)
                val distance = GeoUtils.distanceMeters(lat, lng, wgs[0], wgs[1])
                if (distance > radius + 0.5) continue

                val name = poi.optString("name").trim().ifBlank { "未命名地点" }
                val address = poi.optString("address").trim().let {
                    if (it == "[]") "" else it
                }
                // 补全省/市/区,组成完整地址(水印和列表都使用)
                val admin = listOf(
                    poi.optString("pname").trim(),
                    poi.optString("cityname").trim(),
                    poi.optString("adname").trim(),
                ).filter { it.isNotBlank() }
                    .fold(ArrayList<String>(3)) { acc, part ->
                        if (acc.lastOrNull() != part) acc.add(part)
                        acc
                    }.joinToString("")
                val fullAddress = admin + address
                items.add(PoiItem(name, address, wgs[0], wgs[1], distance, fullAddress))
            }

            if (items.size >= total) break
            page++
            if (page <= maxPages) {
                // 个人 Key QPS 较低,翻页之间稍作间隔,避免 10021 QPS 超限
                Thread.sleep(350)
            }
        }

        // 住宅区等 POI 稀疏区域:用逆地理编码返回的周边 POI 补充,
        // 避免列表里只剩一个小区名
        if (items.size < 10 && !c.cancelled) {
            try {
                items.addAll(fetchRegeoPois(c, key, queryLng, queryLat, radius, lat, lng))
            } catch (t: Throwable) {
                if (c.cancelled) throw t
                // 补充失败不影响已有结果
            }
        }

        items.sortBy { it.distanceMeters }
        val dedup = items.distinctBy {
            "${it.name}|${(it.lat * 10_000).toInt()}|${(it.lng * 10_000).toInt()}"
        }
        return dedup.take(AMAP_MAX_RESULTS) to (total > AMAP_MAX_RESULTS)
    }

    /** 高德接口统一处理:QPS 超限(10021)时短暂等待并重试 */
    private fun getAmapJson(c: Cancellable, url: URL): JSONObject {
        var attempt = 0
        while (true) {
            if (c.cancelled) throw InterruptedException()
            val body = get(c, url, mapOf("Accept" to "application/json"))
            val root = JSONObject(body)
            if (root.optString("status") == "1") return root
            if (root.optString("infocode") == "10021" && attempt < 2) {
                attempt++
                Thread.sleep(700)
                continue
            }
            val info = root.optString("info").ifBlank { "高德接口返回错误" }
            throw IllegalStateException(info)
        }
    }

    /**
     * 逆地理编码返回的周边 POI(有时比周边搜索更全),
     * 过滤到指定半径内并转换为 WGS-84。
     */
    private fun fetchRegeoPois(
        c: Cancellable,
        encodedKey: String,
        queryLng: Double,
        queryLat: Double,
        radius: Double,
        centerLat: Double,
        centerLng: Double,
    ): List<PoiItem> {
        val root = getAmapJson(
            c,
            URL(
                "https://restapi.amap.com/v3/geocode/regeo?key=$encodedKey" +
                        "&location=$queryLng,$queryLat&extensions=all"
            )
        )
        val regeocode = root.optJSONObject("regeocode") ?: return emptyList()
        val component = regeocode.optJSONObject("addressComponent")
        val admin = listOf(
            component?.optString("province").orEmpty(),
            component?.optString("city").orEmpty(),
            component?.optString("district").orEmpty(),
        ).map { it.trim().let { v -> if (v == "[]" || v == "null") "" else v } }
            .filter { it.isNotBlank() }
            .fold(ArrayList<String>(3)) { acc, part ->
                if (acc.lastOrNull() != part) acc.add(part)
                acc
            }.joinToString("")

        val pois = regeocode.optJSONArray("pois") ?: return emptyList()
        val out = ArrayList<PoiItem>()
        for (i in 0 until pois.length()) {
            val poi = pois.optJSONObject(i) ?: continue
            val parts = poi.optString("location").split(',')
            if (parts.size != 2) continue
            val gcjLng = parts[0].toDoubleOrNull() ?: continue
            val gcjLat = parts[1].toDoubleOrNull() ?: continue
            val wgs = GeoUtils.gcj02ToWgs84(gcjLat, gcjLng)
            val distance = GeoUtils.distanceMeters(centerLat, centerLng, wgs[0], wgs[1])
            if (distance > radius + 0.5) continue
            val name = poi.optString("name").trim()
            if (name.isBlank()) continue
            val address = poi.optString("address").trim().let { if (it == "[]") "" else it }
            out.add(PoiItem(name, address, wgs[0], wgs[1], distance, admin + address))
        }
        return out
    }

    // ---------------- HTTP ----------------

    private fun get(
        c: Cancellable,
        url: URL,
        headers: Map<String, String>,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
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
