package com.family.dakacamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/** 把合成好的照片保存到系统相册「Pictures/打卡相机」。 */
object MediaStoreSaver {

    fun save(ctx: Context, bmp: Bitmap, displayName: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveModern(ctx, bmp, displayName)
        } else {
            saveLegacy(ctx, bmp, displayName)
        }

    /** API 29+:MediaStore + IS_PENDING,不需要任何存储权限。 */
    private fun saveModern(ctx: Context, bmp: Bitmap, displayName: String): Boolean {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/打卡相机"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
            } ?: return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    /** API 24–28:直接写公共 Pictures 目录并通知媒体扫描。 */
    private fun saveLegacy(ctx: Context, bmp: Bitmap, displayName: String): Boolean {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "打卡相机"
        )
        if (!dir.exists() && !dir.mkdirs()) return false
        val file = File(dir, displayName)
        return try {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null, null)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
