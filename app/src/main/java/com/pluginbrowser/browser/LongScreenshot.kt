package com.pluginbrowser.browser

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/**
 * 长截图。
 *
 * WebView.draw() 只绘制可视区域，没法一次画出整个 contentHeight，
 * 所以逐屏 scrollTo → 等一次真实绘制（postVisualStateCallback）→ 取一屏 → 拼接。
 * 每屏之间留 4% 重叠，避免固定定位元素被切成两半。
 */
object LongScreenshot {

    /** 单张图上限，再大容易 OOM */
    private const val MAX_HEIGHT_PX = 18000

    /** 位图内存预算（拼接时同时存在分片 + 成品，按一半算） */
    private const val MEMORY_BUDGET_BYTES = 48 * 1024 * 1024

    private const val MAX_FRAMES = 40
    private const val OVERLAP_RATIO = 0.04f

    suspend fun capture(
        webView: WebView,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Result<Bitmap> = suspendCancellableCoroutine { cont ->
        val width = webView.width
        val viewport = webView.height
        if (width <= 0 || viewport <= 0) {
            cont.resume(Result.failure(IllegalStateException("页面还没渲染完成")))
            return@suspendCancellableCoroutine
        }

        val contentHeight = (webView.contentHeight * webView.scale).toInt().coerceAtLeast(viewport)
        val memoryLimit = MEMORY_BUDGET_BYTES / 4 / width
        val limit = min(contentHeight, min(MAX_HEIGHT_PX, max(viewport, memoryLimit)))

        val overlap = (viewport * OVERLAP_RATIO).toInt()
        val step = max(1, viewport - overlap)
        val frames = min(MAX_FRAMES, max(1, (limit + step - 1) / step))

        val originX = webView.scrollX
        val originY = webView.scrollY
        val pieces = ArrayList<Pair<Int, Bitmap>>(frames)
        val handler = Handler(Looper.getMainLooper())
        var index = 0

        fun finish() {
            webView.scrollTo(originX, originY)
            if (pieces.isEmpty()) {
                cont.resume(Result.failure(IllegalStateException("没有截到内容")))
                return
            }
            val totalHeight = min(limit, pieces.last().first + viewport)
            val out = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            for ((y, piece) in pieces) {
                if (y >= totalHeight) {
                    piece.recycle()
                    continue
                }
                val srcH = min(piece.height, totalHeight - y)
                canvas.drawBitmap(piece, Rect(0, 0, width, srcH), Rect(0, y, width, y + srcH), null)
                piece.recycle()
            }
            pieces.clear()
            cont.resume(Result.success(out))
        }

        fun next() {
            if (!cont.isActive || index >= frames) {
                finish()
                return
            }
            val y = min(index * step, max(0, limit - viewport))
            webView.scrollTo(0, y)

            // VisualStateCallback 是抽象类，不能 SAM 转换，只能匿名对象
            webView.postVisualStateCallback(
                index.toLong() + 1,
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (!cont.isActive) {
                            finish()
                            return
                        }
                        val shot = Bitmap.createBitmap(width, viewport, Bitmap.Config.ARGB_8888)
                        webView.draw(Canvas(shot))
                        pieces.add(y to shot)
                        index++
                        onProgress(index, frames)
                        handler.post { next() }
                    }
                },
            )
        }

        handler.post { next() }
    }

    /**
     * 存到相册：API 29+ 走 MediaStore（无需权限），
     * 更低版本写应用外部 Pictures 目录并通知相册扫描。
     */
    fun saveToGallery(context: Context, bitmap: Bitmap): Uri? = runCatching {
        val name = "PB_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date()) + ".png"

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/PluginBrowser",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore 拒绝写入")
            resolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: error("无法打开输出流")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "PluginBrowser",
            ).apply { mkdirs() }
            val file = File(dir, name)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf("image/png"), null,
            )
            Uri.fromFile(file)
        }
    }.getOrNull()
}
