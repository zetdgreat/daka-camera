package com.family.dakacamera

import org.junit.Assert.assertEquals
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
}
