package com.family.dakacamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * 定位 + 逆地理编码(免注册方案,不依赖 Google Play 服务):
 *  1. 先用「最近一次已知位置」立即回一次,同时向系统请求一次当前位置,5 秒超时;
 *  2. 逆地理编码走 Android 系统 Geocoder(国内 ROM 无 GMS 时经常不可用/抛异常,
 *     这是常态,失败时降级为「经纬度 xx.xxxxxx, yy.yyyyyy」,绝不阻塞拍照);
 *  3. 回调统一切回主线程。
 */
class LocationHelper(private val ctx: Context) {

    data class Info(
        val lat: Double,
        val lng: Double,
        /** 详细地址;查不到时为「经纬度 xx, yy」;无定位时为 null */
        val addressText: String?,
        val timeMs: Long,
        /** 定位精度(米),未知时为 0 */
        val accuracyMeters: Float = 0f,
    )

    /** 最近一次解析结果(内存缓存,拍照时优先使用新鲜的) */
    @Volatile
    var last: Info? = null
        private set

    private var inFlight = false
    private val pending = mutableListOf<(Info?) -> Unit>()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /** 解析位置(主线程回调)。重复调用会合并到同一次进行中的解析。 */
    fun resolve(onDone: (Info?) -> Unit) {
        if (!hasPermission()) {
            Handler(Looper.getMainLooper()).post { onDone(null) }
            return
        }
        synchronized(this) {
            if (inFlight) {
                pending.add(onDone)
                return
            }
            inFlight = true
            pending.add(onDone)
        }
        doResolve()
    }

    private fun finish(info: Info?) {
        val callbacks: List<(Info?) -> Unit>
        synchronized(this) {
            inFlight = false
            callbacks = pending.toList()
            pending.clear()
            last = info
        }
        Handler(Looper.getMainLooper()).post { callbacks.forEach { it(info) } }
    }

    @SuppressLint("MissingPermission")
    private fun doResolve() {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            finish(null)
            return
        }
        var best: Location? = recentLastKnown(lm, 120_000L)
        var delivered = false

        fun deliver(loc: Location?) {
            if (delivered) return
            delivered = true
            if (loc == null) {
                finish(null)
                return
            }
            Thread {
                val text = reverseGeocode(ctx, loc.latitude, loc.longitude)
                    ?: String.format(Locale.US, "经纬度 %.6f, %.6f", loc.latitude, loc.longitude)
                finish(
                    Info(
                        loc.latitude,
                        loc.longitude,
                        text,
                        System.currentTimeMillis(),
                        loc.accuracy,
                    )
                )
            }.start()
        }

        // 网络定位往往先返回但精度差;优先使用精度更高的 GPS 结果
        fun consider(loc: Location?) {
            if (loc == null) return
            val current = best
            if (current == null ||
                loc.accuracy < current.accuracy ||
                (loc.accuracy == current.accuracy && loc.time >= current.time)
            ) {
                best = loc
            }
            // 只立即采用“新鲜”的高精度定位,避免旧位置抢先返回
            val fresh = System.currentTimeMillis() - loc.time < 10_000L
            if (loc.accuracy <= 40f && fresh) deliver(loc)
        }

        // 同时向网络定位与 GPS 请求:某些设备(或模拟器)的网络 provider
        // 虽然存在却不返回结果,并行请求 GPS 可避免白等 5 秒后拿不到位置。
        val providers = buildList {
            if (runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
            if (runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
                add(LocationManager.GPS_PROVIDER)
            }
        }
        if (providers.isEmpty()) {
            deliver(best)
            return
        }

        val signals = providers.map { CancellationSignal() }
        Handler(Looper.getMainLooper()).postDelayed({
            signals.forEach { runCatching { it.cancel() } }
            deliver(best) // 超时:使用目前精度最好的位置
        }, 5000)

        providers.forEachIndexed { index, provider ->
            try {
                LocationManagerCompat.getCurrentLocation(
                    lm, provider, signals[index], ContextCompat.getMainExecutor(ctx)
                ) { loc ->
                    consider(loc)
                }
            } catch (t: Throwable) {
                // 单个 provider 失败不影响其他 provider
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(lm: LocationManager): Location? =
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).filter {
            runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
        }.mapNotNull {
            runCatching { lm.getLastKnownLocation(it) }.getOrNull()
        }.maxByOrNull { it.time }

    /** 只接受较新的“最近位置”,避免用很久以前的旧位置冒充当前位置 */
    @SuppressLint("MissingPermission")
    private fun recentLastKnown(lm: LocationManager, maxAgeMs: Long): Location? =
        bestLastKnown(lm)?.takeIf { System.currentTimeMillis() - it.time <= maxAgeMs }

    /**
     * 定位校准专用:不使用缓存,现场重新定位,并在最多 5 秒内取精度最高的结果。
     * 若出现精度 ≤10 米的位置则立即返回;否则等到 5 秒后取最优值。
     */
    fun resolveBest(onDone: (Info?) -> Unit) {
        if (!hasPermission()) {
            Handler(Looper.getMainLooper()).post { onDone(null) }
            return
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            Handler(Looper.getMainLooper()).post { onDone(null) }
            return
        }

        var best: Location? = recentLastKnown(lm, 30_000L)
        var delivered = false
        val signals = mutableListOf<CancellationSignal>()

        fun deliverBest() {
            if (delivered) return
            delivered = true
            signals.forEach { runCatching { it.cancel() } }
            val loc = best
            if (loc == null) {
                Handler(Looper.getMainLooper()).post { onDone(null) }
                return
            }
            Thread {
                val text = reverseGeocode(ctx, loc.latitude, loc.longitude)
                    ?: String.format(Locale.US, "经纬度 %.6f, %.6f", loc.latitude, loc.longitude)
                val info = Info(
                    loc.latitude,
                    loc.longitude,
                    text,
                    System.currentTimeMillis(),
                    loc.accuracy,
                )
                last = info
                Handler(Looper.getMainLooper()).post { onDone(info) }
            }.start()
        }

        fun consider(loc: Location?) {
            if (loc == null) return
            val current = best
            if (current == null ||
                loc.accuracy < current.accuracy ||
                (loc.accuracy == current.accuracy && loc.time >= current.time)
            ) {
                best = loc
            }
            val fresh = System.currentTimeMillis() - loc.time < 10_000L
            if (loc.accuracy <= 10f && fresh) deliverBest()
        }

        val providers = buildList {
            if (runCatching {
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                }.getOrDefault(false)
            ) add(LocationManager.NETWORK_PROVIDER)
            if (runCatching {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                }.getOrDefault(false)
            ) add(LocationManager.GPS_PROVIDER)
        }
        if (providers.isEmpty()) {
            deliverBest()
            return
        }

        val providerSignals = providers.map {
            CancellationSignal().also { s -> signals.add(s) }
        }
        Handler(Looper.getMainLooper()).postDelayed({ deliverBest() }, 5000)

        providers.forEachIndexed { index, provider ->
            try {
                LocationManagerCompat.getCurrentLocation(
                    lm, provider, providerSignals[index], ContextCompat.getMainExecutor(ctx)
                ) { loc -> consider(loc) }
            } catch (t: Throwable) {
                // 单个 provider 失败不影响其他
            }
        }
    }

    companion object {
        /**
         * 逆地理编码。必须在后台线程调用。
         * 配置了高德 Key(App 内或私有构建)时优先走高德,失败回退系统 Geocoder。
         */
        fun reverseGeocode(ctx: Context, lat: Double, lng: Double): String? {
            val amapKey = AppSettings.resolveAmapKey(ctx)
            if (amapKey.isNotBlank()) {
                amapReverseGeocode(amapKey, lat, lng)?.let { return it }
            }
            return systemReverseGeocode(ctx, lat, lng)
        }

        private fun amapReverseGeocode(key: String, lat: Double, lng: Double): String? {
            var conn: HttpURLConnection? = null
            return try {
                // 高德使用 GCJ-02,先把 GPS 的 WGS-84 坐标转换过去,否则地址会偏移数百米
                val gcj = GeoUtils.wgs84ToGcj02(lat, lng)
                val url = URL(
                    "https://restapi.amap.com/v3/geocode/regeo?key=" +
                            URLEncoder.encode(key, "UTF-8") +
                            "&location=${gcj[1]},${gcj[0]}&extensions=all"
                )
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    setRequestProperty("Accept", "application/json")
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(body)
                if (root.optString("status") != "1") return null
                val regeocode = root.optJSONObject("regeocode") ?: return null

                val component = regeocode.optJSONObject("addressComponent")
                val streetNumber = component?.optJSONObject("streetNumber")
                val number = streetNumber?.optString("number").orEmpty().cleanAmapValue()
                val street = streetNumber?.optString("street").orEmpty().cleanAmapValue()
                val building = component?.optJSONObject("building")
                    ?.optString("name").orEmpty().cleanAmapValue()
                val neighborhood = component?.optJSONObject("neighborhood")
                    ?.optString("name").orEmpty().cleanAmapValue()
                val businessArea = component?.optJSONArray("businessAreas")
                    ?.optJSONObject(0)?.optString("name").orEmpty().cleanAmapValue()
                val nearestPoi = regeocode.optJSONArray("pois")
                    ?.optJSONObject(0)?.optString("name").orEmpty().cleanAmapValue()

                val formatted = regeocode.optString("formatted_address")
                    .cleanAmapValue()
                    .takeIf { it.isNotBlank() }
                val base = formatted ?: listOf(
                    component?.optString("province").orEmpty().cleanAmapValue(),
                    component?.optString("city").orEmpty().cleanAmapValue(),
                    component?.optString("district").orEmpty().cleanAmapValue(),
                    component?.optString("township").orEmpty().cleanAmapValue(),
                    street,
                    number,
                ).filter { it.isNotBlank() }.joinToString("")

                if (base.isBlank()) return null

                // 高德的部分坐标只返回到路名。仅当地址停在“路/街/村/号”这一级时,
                // 才补最近的地点名,避免把附近不相干的 POI 拼到已足够详细的地址后面。
                val streetLevel = formatted == null ||
                        STREET_LEVEL_SUFFIXES.any { formatted.endsWith(it) }
                var result = base
                if (streetLevel) {
                    val detail = listOf(nearestPoi, building, neighborhood, businessArea)
                        .firstOrNull { it.isNotBlank() } ?: ""
                    if (detail.isNotBlank() && !result.contains(detail)) {
                        if (number.isNotBlank() && !result.contains(number)) result += number
                        result += detail
                    }
                }
                result.ifBlank { null }
            } catch (t: Throwable) {
                null
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

        /** 高德空值常返回 "[]" 或 "null",统一清理 */
        private fun String.cleanAmapValue(): String =
            if (this == "[]" || this == "null") "" else this.trim()

        /** 地址只到这些层级时,视为“只有路名/行政区”,需要补地点名 */
        private val STREET_LEVEL_SUFFIXES = listOf(
            "路", "街", "大街", "大道", "道", "巷", "胡同", "公路",
            "村", "镇", "乡", "号",
        )

        /** 系统 Geocoder:国内无 GMS 机型常常不可用,失败返回 null。 */
        private fun systemReverseGeocode(ctx: Context, lat: Double, lng: Double): String? {
            return try {
                if (!Geocoder.isPresent()) return null
                @Suppress("DEPRECATION")
                val list = Geocoder(ctx, Locale.CHINA).getFromLocation(lat, lng, 1)
                val addr = list?.firstOrNull() ?: return null
                addr.getAddressLine(0)?.takeIf { it.isNotBlank() }
                    ?: listOfNotNull(
                        addr.locality, addr.subLocality, addr.thoroughfare, addr.subThoroughfare
                    ).joinToString("").takeIf { it.isNotBlank() }
            } catch (e: IOException) {
                null
            } catch (t: Throwable) {
                null
            }
        }
    }
}
