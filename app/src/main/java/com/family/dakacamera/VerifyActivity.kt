package com.family.dakacamera

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.family.dakacamera.databinding.ActivityVerifyBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 验真页:输入照片右下角的防伪码,显示该照片拍摄时的真实时间与地点。 */
class VerifyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVerifyBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVerifyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.verifyButton.setOnClickListener { verify() }
    }

    private fun verify() {
        val raw = binding.codeInput.text?.toString().orEmpty()
        if (raw.isBlank()) {
            showResult("请输入照片右下角「防伪」后面的编码。", error = true)
            return
        }
        val result = AntiFakeCode.decode(raw)
        if (result == null) {
            showResult("无效防伪码:该编码不合法或已被篡改,请核对后重新输入。", error = true)
            return
        }
        render(result, address = null)
        hideKeyboard()

        val noLocation = result.lat == 0.0 && result.lng == 0.0
        if (!noLocation) {
            Thread {
                val addr = LocationHelper.reverseGeocode(this, result.lat, result.lng)
                Handler(Looper.getMainLooper()).post { render(result, address = addr) }
            }.start()
        }
    }

    private fun render(result: AntiFakeCode.Result, address: String?) {
        val date = Date(result.epochSec * 1000)
        val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(date)
        val weekText = SimpleDateFormat("EEEE", Locale.CHINA).format(date)
        val noLocation = result.lat == 0.0 && result.lng == 0.0

        val sb = StringBuilder()
        sb.append("✓ 防伪码有效\n\n")
        sb.append("拍摄时间:").append(timeText).append("(").append(weekText).append(")\n")
        if (noLocation) {
            sb.append("拍摄地点:拍摄时无定位信息\n")
        } else {
            sb.append("拍摄坐标:")
                .append(String.format(Locale.US, "%.6f, %.6f", result.lat, result.lng))
                .append('\n')
            sb.append("地点地址:")
                .append(address ?: "查询中…")
                .append('\n')
        }
        sb.append("\n说明:防伪码由「打卡相机」生成并加密校验,时间取自拍摄设备的系统时钟;" +
                "本页可验证编码的合法性与其中记录的时间、地点,但不能证明照片未被后期处理。")
        showResult(sb.toString(), error = false)
    }

    private fun showResult(text: String, error: Boolean) {
        binding.resultText.text = text
        binding.resultText.setTextColor(if (error) 0xFFFF6B6B.toInt() else 0xFFEDEDED.toInt())
        binding.resultText.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        binding.codeInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.codeInput.windowToken, 0)
    }
}
