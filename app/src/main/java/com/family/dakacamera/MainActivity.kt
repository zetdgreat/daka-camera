package com.family.dakacamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.family.dakacamera.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraController
    private lateinit var locationHelper: LocationHelper
    private var capturing = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasCameraPermission()) {
            binding.shutter.isEnabled = true
            camera.start()
        } else {
            binding.shutter.isEnabled = false
            showChip("未授予相机权限,请在系统设置中开启")
            toast("需要相机权限才能拍照")
        }
        if (locationHelper.hasPermission()) resolveLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        locationHelper = LocationHelper(this)
        camera = CameraController(this, binding.previewView) { msg ->
            runOnUiThread { toast(msg); finishCapture() }
        }

        binding.shutter.setOnClickListener { onShutter() }
        binding.switchCamera.setOnClickListener { camera.switchCamera() }
        binding.verifyEntry.setOnClickListener {
            startActivity(Intent(this, VerifyActivity::class.java))
        }

        requestPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        if (locationHelper.hasPermission()) resolveLocation()
    }

    // ---------------- 权限 ----------------

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        if (hasCameraPermission()) {
            binding.shutter.isEnabled = true
            camera.start()
        }
        val needed = mutableListOf<String>()
        if (!hasCameraPermission()) needed += Manifest.permission.CAMERA
        if (!locationHelper.hasPermission()) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    // ---------------- 定位 ----------------

    private fun resolveLocation() {
        if (!locationHelper.hasPermission()) return
        showChip("定位中…")
        locationHelper.resolve { info ->
            if (info != null) {
                hideChip()
            } else {
                showChip("未获取到定位,水印将显示无定位信息")
            }
        }
    }

    // ---------------- 拍照 ----------------

    private fun onShutter() {
        if (capturing) return
        capturing = true
        binding.shutter.isEnabled = false

        val cached = locationHelper.last
        val fresh = cached != null && System.currentTimeMillis() - cached.timeMs < 90_000
        if (fresh) {
            captureWith(cached)
        } else if (!locationHelper.hasPermission()) {
            captureWith(null)
        } else {
            showChip("定位中…")
            locationHelper.resolve { info ->
                if (info != null) hideChip() else showChip("未获取到定位,水印将显示无定位信息")
                captureWith(info)
            }
        }
    }

    private fun captureWith(loc: LocationHelper.Info?) {
        camera.takePicture { bmp -> composeAndSave(bmp, loc) }
    }

    private fun composeAndSave(photo: Bitmap, loc: LocationHelper.Info?) {
        // 大图绘制放到后台线程,避免卡住预览
        Thread {
            try {
                val now = System.currentTimeMillis()
                val timeText = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(now))
                val dateText = SimpleDateFormat("yyyy.MM.dd EEEE", Locale.CHINA).format(Date(now))
                // 无定位时用 (0,0) 作哨兵值,验真页会显示「拍摄时无定位信息」
                val lat = loc?.lat ?: 0.0
                val lng = loc?.lng ?: 0.0
                val code = AntiFakeCode.encode(now / 1000, lat, lng)
                val data = WatermarkData(
                    timeText = timeText,
                    dateText = dateText,
                    address = loc?.addressText ?: "无定位信息",
                    code = code
                )
                val watermarked = WatermarkRenderer.render(photo, data)
                val name = "Daka_" +
                        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) + ".jpg"
                val ok = MediaStoreSaver.save(this, watermarked, name)
                runOnUiThread {
                    toast(if (ok) "已保存到相册「打卡相机」" else "保存失败")
                    finishCapture()
                    if (locationHelper.hasPermission()) resolveLocation() // 为下一张刷新定位
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    toast("处理失败:${t.message}")
                    finishCapture()
                }
            }
        }.start()
    }

    private fun finishCapture() {
        capturing = false
        binding.shutter.isEnabled = true
    }

    // ---------------- 小工具 ----------------

    private fun showChip(text: String) {
        binding.statusChip.text = text
        binding.statusChip.visibility = View.VISIBLE
    }

    private fun hideChip() {
        binding.statusChip.visibility = View.GONE
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
