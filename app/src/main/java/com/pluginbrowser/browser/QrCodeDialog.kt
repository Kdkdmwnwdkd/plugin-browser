package com.pluginbrowser.browser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.pluginbrowser.util.UrlUtils

/**
 * 把当前地址变成二维码。
 *
 * 只做一件事：给一个能扫的方块，加一行看得懂这是哪儿的字。
 * 不加分享、不加保存——需要存图的人会自己截屏，多一个按钮就多一行要解释的东西。
 *
 * 颜色刻意不跟主题走：二维码必须是浅底深码，深色模式下来一块黑底白码，
 * 一半扫码器认不出来。所以这里固定白底，正文用界面上的那一支墨色。
 */
object QrCodeDialog {

    private const val SIZE = 720

    fun show(activity: Activity, url: String) {
        if (url.isBlank()) return
        val bitmap = encode(url) ?: return

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val qr = ImageView(activity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(248), dp(248))
        }

        val host = TextView(activity).apply {
            text = UrlUtils.hostOf(url)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFF17181B.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) }
        }

        val full = TextView(activity).apply {
            text = url
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF71757F.toInt())
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(24), dp(24), dp(24), dp(22))
            addView(qr)
            addView(host)
            addView(full)
        }

        AlertDialog.Builder(activity)
            .setView(root)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 编码失败（链接过长、内存不够）返回 null，由调用方静默处理 */
    private fun encode(text: String): Bitmap? = try {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, SIZE, SIZE, hints)
        val pixels = IntArray(SIZE * SIZE)
        val dark = 0xFF17181B.toInt()
        val light = 0xFFFFFFFF.toInt()
        for (y in 0 until SIZE) {
            val row = y * SIZE
            for (x in 0 until SIZE) {
                pixels[row + x] = if (matrix.get(x, y)) dark else light
            }
        }
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        }
    } catch (e: Throwable) {
        null
    }
}
