package com.family.dakacamera

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.util.Size
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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

    /** 用户手动校准选中的地点(会话内有效;离开 500 米自动失效) */
    private var manualLocation: PoiItem? = null
    private var poiDialog: AlertDialog? = null
    private var poiSearch: PoiSearch.Cancellable? = null

    private val overlayHandler = Handler(Looper.getMainLooper())
    private var overlayMinute = -1L
    private var overlayLocationKey = ""
    private val overlayTicker = object : Runnable {
        override fun run() {
            updateOverlayIfNeeded()
            overlayHandler.postDelayed(this, 1_000)
        }
    }

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
        updateOverlayIfNeeded(force = true)
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
        binding.openGallery.setOnClickListener { openGallery() }
        binding.switchCamera.setOnClickListener { camera.switchCamera() }
        binding.verifyEntry.setOnClickListener {
            startActivity(Intent(this, VerifyActivity::class.java))
        }
        binding.settingsEntry.setOnClickListener { showSettingsDialog() }
        binding.calibrateLocation.setOnClickListener { showLocationPicker() }

        // 预览区域尺寸确定后,把「注意安全」居中到预览上方的白色区域
        binding.previewContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            centerSafetyNotice()
        }
        binding.root.post { centerSafetyNotice() }

        requestPermissionsIfNeeded()
    }

    /** 把「注意安全」放在预览上方白色区域的垂直中央(水平方向由布局居中) */
    private fun centerSafetyNotice() {
        val previewLoc = IntArray(2)
        binding.previewContainer.getLocationInWindow(previewLoc)
        val rootLoc = IntArray(2)
        binding.root.getLocationInWindow(rootLoc)
        val noticeHeight = binding.safetyNotice.height
        if (noticeHeight <= 0 || previewLoc[1] <= rootLoc[1]) return

        val whiteTop = rootLoc[1]
        val whiteBottom = previewLoc[1]
        val centerY = (whiteTop + whiteBottom) / 2
        val lp = binding.safetyNotice.layoutParams as FrameLayout.LayoutParams
        val newTop = (centerY - noticeHeight / 2 - rootLoc[1]).coerceAtLeast(0)
        if (lp.topMargin != newTop) {
            lp.topMargin = newTop
            binding.safetyNotice.layoutParams = lp
        }

        // 状态提示(定位中…等)放在「注意安全」下方,避免重叠
        val gap = (12 * resources.displayMetrics.density).toInt()
        val chipLp = binding.statusChip.layoutParams as FrameLayout.LayoutParams
        val chipTop = newTop + noticeHeight + gap
        if (chipLp.topMargin != chipTop) {
            chipLp.topMargin = chipTop
            binding.statusChip.layoutParams = chipLp
        }
    }

    override fun onStart() {
        super.onStart()
        if (locationHelper.hasPermission()) resolveLocation()
    }

    override fun onResume() {
        super.onResume()
        updateOverlayIfNeeded(force = true)
        refreshGalleryThumb()
        overlayHandler.post(overlayTicker)
    }

    override fun onPause() {
        super.onPause()
        overlayHandler.removeCallbacks(overlayTicker)
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayHandler.removeCallbacksAndMessages(null)
        poiSearch?.cancel()
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
        if (!locationHelper.hasPermission()) {
            updateOverlayIfNeeded(force = true)
            return
        }
        showChip("定位中…")
        locationHelper.resolve { info ->
            if (info != null) {
                hideChip()
                autoClearManualIfMoved(info)
            } else {
                showChip("未获取到定位,水印将显示无定位信息")
            }
            updateOverlayIfNeeded(force = true)
        }
    }

    /** 真实位置远离手动校准点时,自动恢复自动定位,避免用到过期位置 */
    private fun autoClearManualIfMoved(info: LocationHelper.Info) {
        val manual = manualLocation ?: return
        if (GeoUtils.distanceMeters(info.lat, info.lng, manual.lat, manual.lng) > 500.0) {
            manualLocation = null
            toast("已离开手动选择的位置,恢复自动定位")
        }
    }

    /** 当前生效的位置:手动校准优先,其次自动定位 */
    private fun effectiveLocation(): LocationHelper.Info? =
        manualLocation?.let {
            LocationHelper.Info(it.lat, it.lng, it.watermarkText(), System.currentTimeMillis())
        } ?: locationHelper.last

    // ---------------- 实时水印预览 ----------------

    private fun updateOverlayIfNeeded(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val minute = now / 60_000L
        val loc = effectiveLocation()
        val locKey = if (loc == null) "none"
        else "${loc.lat},${loc.lng},${loc.addressText}"
        if (!force && minute == overlayMinute && locKey == overlayLocationKey) return
        overlayMinute = minute
        overlayLocationKey = locKey

        val lat = loc?.lat ?: 0.0
        val lng = loc?.lng ?: 0.0
        binding.watermarkOverlay.data = WatermarkData(
            timeText = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(now)),
            dateText = SimpleDateFormat("yyyy.MM.dd EEEE", Locale.CHINA).format(Date(now)),
            address = loc?.addressText ?: "无定位信息",
            code = AntiFakeCode.encode(now / 1000, lat, lng)
        )
    }

    // ---------------- 拍照 ----------------

    private fun onShutter() {
        if (capturing) return
        capturing = true
        binding.shutter.isEnabled = false

        val cached = effectiveLocation()
        val fresh = cached != null && System.currentTimeMillis() - cached.timeMs < 90_000
        if (fresh) {
            captureWith(cached)
        } else if (!locationHelper.hasPermission()) {
            captureWith(null)
        } else {
            showChip("定位中…")
            locationHelper.resolve { info ->
                if (info != null) {
                    hideChip()
                    autoClearManualIfMoved(info)
                } else {
                    showChip("未获取到定位,水印将显示无定位信息")
                }
                captureWith(info ?: effectiveLocation())
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
                    updateOverlayIfNeeded(force = true)
                    if (ok) refreshGalleryThumb() // 左下角缩略图更新为新照片
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

    // ---------------- 相册 ----------------

    /** 更新左下角相册按钮:显示最新照片的圆形缩略图;没有照片时显示占位图标 */
    private fun refreshGalleryThumb() {
        Thread {
            val thumb = latestPhotoThumbnail()
            runOnUiThread {
                if (thumb != null) {
                    binding.galleryThumb.setImageBitmap(thumb)
                } else {
                    binding.galleryThumb.setImageResource(R.drawable.ic_gallery)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun latestPhotoThumbnail(): Bitmap? {
        val uri = latestPhotoUri() ?: return null
        val size = (54 * resources.displayMetrics.density).toInt().coerceAtLeast(96)
        return try {
            val source: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(uri, Size(size, size), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    contentResolver,
                    ContentUris.parseId(uri),
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null
                )
            }
            source?.let { circularCrop(it, size) }
        } catch (t: Throwable) {
            null
        }
    }

    /** 居中裁成正方形,再裁成圆形 */
    private fun circularCrop(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        val square = Bitmap.createBitmap(src, left, top, side, side)

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return output
    }

    private fun openGallery() {
        // MediaStore 查询放到后台线程,避免个别机型上查询慢导致卡顿
        Thread {
            val uri = latestPhotoUri()
            runOnUiThread { openGalleryWith(uri) }
        }.apply { isDaemon = true }.start()
    }

    private fun openGalleryWith(uri: Uri?) {
        if (uri != null) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
                return
            } catch (t: Throwable) {
                // 没有可查看图片的应用,继续尝试打开相册
            }
        }
        try {
            startActivity(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
            )
        } catch (t: Throwable) {
            toast("还没有照片,或未找到相册应用")
        }
    }

    /** 最近一张保存在「打卡相机」相册里的照片 */
    private fun latestPhotoUri(): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            selectionArgs = arrayOf("%打卡相机%")
        } else {
            @Suppress("DEPRECATION")
            selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            selectionArgs = arrayOf("%/Pictures/打卡相机/%")
        }
        return try {
            contentResolver.query(
                collection, projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    )
                    ContentUris.withAppendedId(collection, id)
                } else {
                    null
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------- 定位校准 ----------------

    private fun showLocationPicker() {
        if (poiDialog?.isShowing == true) return

        val view = layoutInflater.inflate(R.layout.dialog_location_picker, null)
        val progress = view.findViewById<ProgressBar>(R.id.poiProgress)
        val status = view.findViewById<TextView>(R.id.poiStatus)
        val empty = view.findViewById<TextView>(R.id.poiEmpty)
        val listView = view.findViewById<ListView>(R.id.poiList)
        val retry = view.findViewById<TextView>(R.id.poiRetry)
        val source = view.findViewById<TextView>(R.id.poiSource)
        val hint = view.findViewById<TextView>(R.id.poiHint)
        val radius = AppSettings.getSearchRadius(this)
        hint.text = getString(R.string.pick_location_hint_fmt, radius)

        fun updateSourceLabel(fallback: Boolean = false) {
            source.text = when {
                fallback -> getString(R.string.data_source_fallback)
                AppSettings.resolveAmapKey(this).isNotBlank() -> getString(R.string.data_source_amap)
                else -> getString(R.string.data_source_osm)
            }
        }
        updateSourceLabel()

        var adapter: PoiAdapter? = null
        val dialog = AlertDialog.Builder(this).setView(view).create()

        fun showLoading(text: String) {
            progress.visibility = View.VISIBLE
            status.visibility = View.VISIBLE
            status.text = text
            empty.visibility = View.GONE
            retry.visibility = View.GONE
            listView.visibility = View.GONE
        }

        fun showError(message: String) {
            progress.visibility = View.GONE
            status.visibility = View.VISIBLE
            status.text = message
            empty.visibility = View.GONE
            retry.visibility = View.VISIBLE
            listView.visibility = View.GONE
        }

        fun doSearch(lat: Double, lng: Double) {
            showLoading(getString(R.string.searching_nearby_fmt, radius))
            poiSearch = PoiSearch.search(
                this@MainActivity, lat, lng, radius,
                object : PoiSearch.Callback {
                override fun onResult(items: List<PoiItem>, truncated: Boolean, usedFallback: Boolean) {
                    if (!dialog.isShowing) return
                    if (usedFallback) updateSourceLabel(fallback = true)
                    progress.visibility = View.GONE
                    if (items.isEmpty()) {
                        status.visibility = View.GONE
                        empty.text = getString(R.string.no_nearby_location_fmt, radius)
                        empty.visibility = View.VISIBLE
                        retry.visibility = View.VISIBLE
                        listView.visibility = View.GONE
                        return
                    }
                    status.visibility = View.VISIBLE
                    status.text = buildString {
                        append("共 ${items.size} 个地点,由近到远")
                        if (truncated) append("(已达显示上限)")
                    }
                    empty.visibility = View.GONE
                    retry.visibility = View.GONE
                    val a = PoiAdapter(this@MainActivity, items)
                    adapter = a
                    listView.adapter = a
                    listView.visibility = View.VISIBLE
                }

                override fun onError(message: String) {
                    if (!dialog.isShowing) return
                    showError(message)
                }
            })
        }

        fun startSearch() {
            poiSearch?.cancel()
            poiSearch = null
            val last = locationHelper.last
            if (last != null && System.currentTimeMillis() - last.timeMs < 60_000) {
                doSearch(last.lat, last.lng)
            } else if (!locationHelper.hasPermission()) {
                showError("未获得定位权限,无法搜索附近地点")
            } else {
                showLoading("正在获取当前位置…")
                locationHelper.resolve { info ->
                    if (!dialog.isShowing) return@resolve
                    if (info == null) showError("未获取到当前位置,请稍后重试")
                    else doSearch(info.lat, info.lng)
                }
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter?.getItem(position) ?: return@setOnItemClickListener
            manualLocation = item
            updateOverlayIfNeeded(force = true)
            toast("已选择:${item.name}")
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.poiReset).setOnClickListener {
            manualLocation = null
            updateOverlayIfNeeded(force = true)
            toast("已恢复自动定位")
            dialog.dismiss()
        }
        retry.setOnClickListener { startSearch() }
        view.findViewById<View>(R.id.poiCancel).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            poiSearch?.cancel()
            poiSearch = null
            poiDialog = null
        }

        poiDialog = dialog
        dialog.show()
        startSearch()
    }

    /** 设置:高德 Key(只保存在本机)+ 定位校准搜索半径 */
    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val input = view.findViewById<EditText>(R.id.amapKeyInput)
        input.setText(AppSettings.getAmapKey(this))
        val dialog = AlertDialog.Builder(this).setView(view).create()

        val radiusViews = listOf(
            100 to view.findViewById<TextView>(R.id.radius100),
            200 to view.findViewById<TextView>(R.id.radius200),
            300 to view.findViewById<TextView>(R.id.radius300),
            400 to view.findViewById<TextView>(R.id.radius400),
            500 to view.findViewById<TextView>(R.id.radius500),
        )
        fun renderRadius(selected: Int) {
            radiusViews.forEach { (meters, tv) ->
                tv.setTextColor(
                    if (meters == selected) 0xFFFFD400.toInt() else 0xFFAAAAAA.toInt()
                )
                tv.setTypeface(null, if (meters == selected) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        renderRadius(AppSettings.getSearchRadius(this))
        radiusViews.forEach { (meters, tv) ->
            tv.setOnClickListener {
                AppSettings.setSearchRadius(this, meters)
                renderRadius(meters)
            }
        }

        view.findViewById<View>(R.id.amapKeyClear).setOnClickListener {
            AppSettings.setAmapKey(this, "")
            toast("已清除高德 Key,将使用 OpenStreetMap")
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.amapKeyCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.amapKeySave).setOnClickListener {
            val key = input.text?.toString()?.trim().orEmpty()
            AppSettings.setAmapKey(this, key)
            toast(if (key.isBlank()) "已清空,将使用 OpenStreetMap" else "已保存高德 Key")
            dialog.dismiss()
        }
        dialog.show()
    }

    /** 地点列表适配器(复用 convertView,避免列表过长时频繁 inflate) */
    private class PoiAdapter(
        context: Context,
        private val items: List<PoiItem>,
    ) : ArrayAdapter<PoiItem>(context, R.layout.item_poi, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.item_poi, parent, false)
            val item = items[position]
            view.findViewById<TextView>(R.id.poiName).text = item.name
            view.findViewById<TextView>(R.id.poiDetail).text = buildString {
                append(String.format(Locale.US, "%.0f 米", item.distanceMeters))
                if (item.address.isNotBlank()) {
                    append(" · ")
                    append(item.address)
                }
            }
            return view
        }
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
