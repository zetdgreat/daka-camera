package com.family.dakacamera

import android.content.Context

/**
 * 本机设置(SharedPreferences)。
 *
 * 高德 Key 只保存在设备本地:
 *  - 公开仓库与公开 APK 均不内置 Key;
 *  - 优先使用 App 内填写的 Key,没有时回退到构建时配置(私有构建用,公开版为空)。
 */
object AppSettings {

    private const val PREFS = "daka_settings"
    private const val KEY_AMAP = "amap_web_key"
    private const val KEY_SEARCH_RADIUS = "search_radius"

    private val ALLOWED_RADII = listOf(100, 200, 300, 400, 500)

    fun resolveAmapKey(ctx: Context): String =
        getAmapKey(ctx).ifBlank { BuildConfig.AMAP_KEY }

    fun getAmapKey(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AMAP, "")
            .orEmpty()
            .trim()

    fun setAmapKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AMAP, key.trim())
            .apply()
    }

    /** 定位校准的搜索半径(米),默认 300 */
    fun getSearchRadius(ctx: Context): Int {
        val value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SEARCH_RADIUS, 300)
        return if (value in ALLOWED_RADII) value else 300
    }

    fun setSearchRadius(ctx: Context, meters: Int) {
        if (meters !in ALLOWED_RADII) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SEARCH_RADIUS, meters)
            .apply()
    }
}
