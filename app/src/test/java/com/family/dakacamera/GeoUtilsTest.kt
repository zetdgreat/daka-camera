package com.family.dakacamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoUtilsTest {

    @Test
    fun zeroDistance() {
        assertEquals(0.0, GeoUtils.distanceMeters(39.9, 116.4, 39.9, 116.4), 0.001)
    }

    @Test
    fun knownLatitudeDistance() {
        // 纬度相差 0.001° ≈ 111.3 米
        val d = GeoUtils.distanceMeters(39.9000, 116.4000, 39.9010, 116.4000)
        assertEquals(111.3, d, 1.0)
    }

    @Test
    fun distanceIsSymmetric() {
        val a = GeoUtils.distanceMeters(39.9042, 116.4074, 39.9050, 116.4080)
        val b = GeoUtils.distanceMeters(39.9050, 116.4080, 39.9042, 116.4074)
        assertEquals(a, b, 0.0001)
    }

    @Test
    fun gcjOffsetIsSignificantInChina() {
        val gcj = GeoUtils.wgs84ToGcj02(37.19, 116.87)
        val offset = GeoUtils.distanceMeters(37.19, 116.87, gcj[0], gcj[1])
        assertTrue("GCJ-02 偏移应在数百米量级,实际 $offset 米", offset in 100.0..1500.0)
    }

    @Test
    fun gcjRoundTrip() {
        val gcj = GeoUtils.wgs84ToGcj02(37.19, 116.87)
        val wgs = GeoUtils.gcj02ToWgs84(gcj[0], gcj[1])
        assertEquals(37.19, wgs[0], 1e-5)
        assertEquals(116.87, wgs[1], 1e-5)
    }

    @Test
    fun outsideChinaIsUnchanged() {
        val paris = GeoUtils.wgs84ToGcj02(48.8566, 2.3522)
        assertEquals(48.8566, paris[0], 0.0)
        assertEquals(2.3522, paris[1], 0.0)
    }
}
