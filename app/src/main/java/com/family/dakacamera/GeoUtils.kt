package com.family.dakacamera

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    // GCJ-02(火星坐标)转换参数
    private const val PI = Math.PI
    private const val GCJ_A = 6378245.0
    private const val GCJ_EE = 0.00669342162296594323

    /** Haversine 球面距离(米)。 */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** 是否在中国境外(境外坐标不需要 GCJ-02 转换)。 */
    fun isOutOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    /** WGS-84(GPS 原始坐标)→ GCJ-02(高德/腾讯等国内地图使用的坐标)。 */
    fun wgs84ToGcj02(lat: Double, lng: Double): DoubleArray {
        if (isOutOfChina(lat, lng)) return doubleArrayOf(lat, lng)
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - GCJ_EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (GCJ_A / sqrtMagic * cos(radLat) * PI)
        return doubleArrayOf(lat + dLat, lng + dLng)
    }

    /** GCJ-02 → WGS-84(迭代逼近)。 */
    fun gcj02ToWgs84(lat: Double, lng: Double): DoubleArray {
        if (isOutOfChina(lat, lng)) return doubleArrayOf(lat, lng)
        var wgsLat = lat
        var wgsLng = lng
        repeat(4) {
            val gcj = wgs84ToGcj02(wgsLat, wgsLng)
            wgsLat -= gcj[0] - lat
            wgsLng -= gcj[1] - lng
        }
        return doubleArrayOf(wgsLat, wgsLng)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
