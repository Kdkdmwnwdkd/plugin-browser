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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/**
 * 长截图。
 *
 * WebView.draw() 只绘制可视区域，没法一次画出整个页面，
 * 所以逐屏 scrollTo → 取一屏 → 拼接，屏间留 4% 重叠避免固定元素被切断。
 *
 * 高度不能直接用 contentHeight：不少页面（带 100vh 分区、懒加载的站点）
 * 会把它报得比真实内容大很多，截出来下半张全是背景色。
 * 这里先用 JS 找「最后一个可见非 fixed 元素」的底部，再与 contentHeight 取较小值；
 * 另外每帧与上一帧比对，滚不动了立即收尾。
 */
object LongScreenshot {

    /** 单张图上限，再大容易 OOM */
    private const val MAX_HEIGHT_PX = 18000

    /** 位图内存预算（分片 + 成品同时存在） */
    private const val MEMORY_BUDGET_BYTES = 48 * 1024 * 1024

    private const val MAX_FRAMES = 40
    private const val OVERLAP_RATIO = 0.04f

    /** 每屏停留时间：给懒加载与滚动动画留出时间，太快会截到空白 */
    private const val FRAME_SETTLE_MS = 200L

    private const val MEASURE_JS = """
        (function () {
          var doc = document.documentElement;
          var body = document.body;
          var scrollH = Math.max(doc ? doc.scrollHeight : 0, body ? body.scrollHeight : 0);
          var maxBottom = 0;
          var els = document.querySelectorAll('body *');
          var n = Math.min(els.length, 2500);
          for (var i = 0; i < n; i++) {
            var el = els[i];
            var cs = window.getComputedStyle(el);
            if (!cs) continue;
            if (cs.position === 'fixed' || cs.display === 'none' || cs.visibility === 'hidden') continue;
            var r = el.getBoundingClientRect();
            if (r.width < 2 || r.height < 2) continue;
            var bottom = r.bottom + (window.pageYOffset || 0);
            if (bottom > maxBottom) maxBottom = bottom;
          }
          var vh = window.innerHeight || 0;
          var h = Math.max(maxBottom, vh);
          if (scrollH > 0) h = Math.min(h, scrollH);
          return JSON.stringify({ h: Math.round(h), vh: Math.round(vh) });
        })()
    """

    suspend fun capture(
        webView: WebView,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Result<Bitmap> = withContext(Dispatchers.Main) {
        val width = webView.width
        val viewport = webView.height
        if (width <= 0 || viewport <= 0) {
            return@withContext Result.failure(IllegalStateException("页面还没渲染完成"))
        }

        val originX = webView.scrollX
        val originY = webView.scrollY

        // 测量要在顶部做，否则 getBoundingClientRect 的基准会漂
        webView.scrollTo(0, 0)
        delay(FRAME_SETTLE_MS)

        // 注意单位：contentHeight 与 JS 测量都是 CSS 像素，而 viewport 是物理像素。
        // 早先直接把 CSS 高度当物理高度用，遇到 100vh 分区会多截一大截背景色。
        val fallbackPx = (webView.contentHeight * webView.scale).toInt().coerceAtLeast(viewport)
        val measured = measureContent(webView)
        val ratio = if (measured.second > 0) viewport.toFloat() / measured.second else 1f
        val measuredPx = if (measured.first > 0) {
            (measured.first * ratio).toInt()
        } else {
            fallbackPx
        }
        // 以 JS 实测的内容底部为准；兜底值只在测量失败时使用，同时防止测量异常偏大
        val contentHeight = min(measuredPx.coerceAtLeast(viewport), fallbackPx)

        val memoryLimit = MEMORY_BUDGET_BYTES / 4 / width
        val limit = min(contentHeight, min(MAX_HEIGHT_PX, max(viewport, memoryLimit)))

        val overlap = (viewport * OVERLAP_RATIO).toInt()
        val step = max(1, viewport - overlap)
        val frames = min(MAX_FRAMES, max(1, (limit + step - 1) / step))

        val pieces = ArrayList<Pair<Int, Bitmap>>(frames)
        val handler = Handler(Looper.getMainLooper())
        var index = 0

        suspendCancellableCoroutine<Result<Bitmap>> { cont ->
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
                    canvas.drawBitmap(
                        piece,
                        Rect(0, 0, width, srcH),
                        Rect(0, y, width, y + srcH),
                        null,
                    )
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

                handler.postDelayed({
                    if (!cont.isActive) {
                        finish()
                        return@postDelayed
                    }
                    val shot = Bitmap.createBitmap(width, viewport, Bitmap.Config.ARGB_8888)
                    webView.draw(Canvas(shot))

                    // 滚不动了（到底，或页面主体在内部容器里不跟随 scrollTo），
                    // 与其继续截同样的画面，不如就此收尾
                    val prev = pieces.lastOrNull()?.second
                    if (prev != null && nearlySame(prev, shot)) {
                        shot.recycle()
                        finish()
                        return@postDelayed
                    }

                    pieces.add(y to shot)
                    index++
                    onProgress(index, frames)
                    next()
                }, FRAME_SETTLE_MS)
            }

            next()
        }
    }

    /** 返回 (页面内容高度, 页面 CSS 视口高度)，单位都是 CSS 像素；失败返回 0 */
    private suspend fun measureContent(webView: WebView): Pair<Int, Int> {
        val raw = suspendCancellableCoroutine<String?> { cont ->
            webView.evaluateJavascript(MEASURE_JS) { value -> cont.resume(value) }
        } ?: return 0 to 0
        return runCatching {
            val inner = (JSONTokener(raw).nextValue() as? String).orEmpty()
            val json = org.json.JSONObject(inner)
            json.optInt("h", 0) to json.optInt("vh", 0)
        }.getOrDefault(0 to 0)
    }

    /** 两张截图是否几乎一样（采样 12×24 个点），用于判断"滚不动了" */
    private fun nearlySame(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        val stepX = max(1, a.width / 12)
        val stepY = max(1, a.height / 24)
        var total = 0
        var same = 0
        var x = stepX / 2
        while (x < a.width) {
            var y = stepY / 2
            while (y < a.height) {
                total++
                if (a.getPixel(x, y) == b.getPixel(x, y)) same++
                y += stepY
            }
            x += stepX
        }
        return total > 0 && same.toFloat() / total > 0.998f
    }

    /**
     * 存到相册：API 29+ 走 MediaStore（无需权限），
     * 更低版本写应用外部 Pictures 目录并通知相册扫描。
     */
    fun saveToGallery(context: Context, bitmap: Bitmap): Uri? = runCatching {
        val name = "Kite_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date()) + ".png"

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/Kite",
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
                "Kite",
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
