package com.family.dakacamera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.view.Surface
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/**
 * CameraX 封装:预览、前后摄切换、拍照。
 *
 * 方向与镜像都由本类处理,保证交给 [WatermarkRenderer] 的位图是「竖屏正向、前摄已镜像」的:
 *  - toBitmap() 返回的位图未旋转,必须按 imageInfo.rotationDegrees 旋转;
 *  - 前摄手动水平镜像(与预览画面一致),镜像发生在画水印之前,水印文字不会变成反字;
 *  - 界面锁竖屏,故 targetRotation 取屏幕方向即可,无需多方向组合处理。
 */
class CameraController(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val onError: (String) -> Unit,
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    fun start() {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                cameraProvider = future.get()
                bind()
            } catch (t: Throwable) {
                onError("相机初始化失败:${t.message}")
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bind()
    }

    fun takePicture(onBitmap: (Bitmap) -> Unit) {
        val capture = imageCapture ?: run {
            onError("相机未就绪")
            return
        }
        capture.targetRotation = displayRotation()
        val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT

        capture.takePicture(
            ContextCompat.getMainExecutor(activity),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        var bmp = image.toBitmap()
                        val degrees = image.imageInfo.rotationDegrees
                        if (degrees != 0) bmp = rotate(bmp, degrees.toFloat())
                        if (mirror) bmp = mirrorHorizontal(bmp)
                        onBitmap(bmp)
                    } catch (t: Throwable) {
                        onError("照片处理失败:${t.message}")
                    } finally {
                        image.close() // 必须关闭,否则后续拍照会卡住
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    onError("拍照失败:${exception.message}")
                }
            }
        )
    }

    private fun bind() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(95)
            .setTargetRotation(displayRotation())
            .build()
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(activity, selector, preview, capture)
            imageCapture = capture
        } catch (t: Throwable) {
            onError("相机不可用:${t.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.rotation ?: Surface.ROTATION_0
        } else {
            activity.windowManager.defaultDisplay.rotation
        }

    private fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (out !== src) src.recycle()
        return out
    }

    private fun mirrorHorizontal(src: Bitmap): Bitmap {
        val matrix = Matrix().apply { postScale(-1f, 1f, src.width / 2f, src.height / 2f) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (out !== src) src.recycle()
        return out
    }
}
