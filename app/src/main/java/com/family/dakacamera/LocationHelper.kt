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
import java.io.IOException
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
        val best = bestLastKnown(lm)
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
                finish(Info(loc.latitude, loc.longitude, text, System.currentTimeMillis()))
            }.start()
        }

        val provider = when {
            runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ->
                LocationManager.NETWORK_PROVIDER
            runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ->
                LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            deliver(best)
            return
        }

        val signal = CancellationSignal()
        Handler(Looper.getMainLooper()).postDelayed({
            signal.cancel()
            deliver(best) // 超时:回落到最近已知位置
        }, 5000)

        try {
            LocationManagerCompat.getCurrentLocation(
                lm, provider, signal, ContextCompat.getMainExecutor(ctx)
            ) { loc -> deliver(loc ?: best) }
        } catch (t: Throwable) {
            deliver(best)
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

    companion object {
        /** 逆地理编码。必须在后台线程调用;失败返回 null(国内无 GMS 机型属常态)。 */
        fun reverseGeocode(ctx: Context, lat: Double, lng: Double): String? {
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
