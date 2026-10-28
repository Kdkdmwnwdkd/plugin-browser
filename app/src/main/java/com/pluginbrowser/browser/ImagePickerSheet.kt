package com.pluginbrowser.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.pluginbrowser.R
import com.pluginbrowser.download.DownloadEngine
import com.pluginbrowser.download.DownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「保存图片」面板：把页面里的图排成九宫格，勾选后批量进下载队列。
 *
 * 缩略图自己下自己压：项目里没有图片加载库，为了一个面板引入 Glide/Coil
 * 不划算（包体 + 一堆初始化）。这里只做三件事：采样、缓存、失败留空。
 */
class ImagePickerSheet : BottomSheetDialogFragment() {

    private var adapter: ImageAdapter? = null
    private var saveButton: MaterialButton? = null
    private var countText: TextView? = null
    private val selected = LinkedHashSet<String>()

    override fun getTheme(): Int = R.style.Theme_PluginBrowser_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_image_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val items = PageImages.pending
        val grid = view.findViewById<RecyclerView>(R.id.image_grid)
        val empty = view.findViewById<TextView>(R.id.image_empty)
        countText = view.findViewById(R.id.image_count)
        saveButton = view.findViewById(R.id.image_save)

        grid.layoutManager = GridLayoutManager(requireContext(), 3)
        val listAdapter = ImageAdapter(
            items = items,
            scope = viewLifecycleOwner.lifecycleScope,
            onToggle = { image, checked ->
                if (checked) selected.add(image.url) else selected.remove(image.url)
                updateCount(items.size)
            },
        )
        adapter = listAdapter
        grid.adapter = listAdapter

        grid.isVisible = items.isNotEmpty()
        empty.isVisible = items.isEmpty()
        updateCount(items.size)

        val selectAll = view.findViewById<TextView>(R.id.image_select_all)
        selectAll.setOnClickListener {
            if (selected.size == items.size && items.isNotEmpty()) {
                selected.clear()
                listAdapter.clearSelection()
                selectAll.text = "全选"
            } else {
                items.forEach { selected.add(it.url) }
                listAdapter.selectAll()
                selectAll.text = "取消全选"
            }
            updateCount(items.size)
        }

        saveButton?.setOnClickListener { save() }
    }

    override fun onStart() {
        super.onStart()
        val sheet = (dialog as? BottomSheetDialog)?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet,
        ) ?: return
        sheet.layoutParams = sheet.layoutParams.apply {
            height = (resources.displayMetrics.heightPixels * 0.85f).toInt()
        }
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isHideable = true
        }
    }

    private fun updateCount(total: Int) {
        countText?.text = if (total == 0) {
            "没有找到可保存的图片"
        } else {
            "$total 张 · 已选 ${selected.size} 张"
        }
        saveButton?.apply {
            isEnabled = selected.isNotEmpty()
            text = if (selected.isEmpty()) "保存" else "保存 ${selected.size} 张"
        }
    }

    private fun save() {
        if (selected.isEmpty()) return
        val urls = selected.toList()
        val ctx = requireContext()
        val engine = DownloadEngine.get(ctx)
        lifecycleScope.launch {
            var queued = 0
            urls.forEachIndexed { index, url ->
                val name = PageImages.fileNameOf(url, index)
                val ok = runCatching {
                    engine.enqueue(name, url, PageImages.mimeOf(url), -1)
                }.isSuccess
                if (ok) queued++
            }
            if (queued > 0) {
                DownloadService.start(ctx)
                Toast.makeText(
                    ctx,
                    "已加入下载队列 $queued 张，保存到相册 PluginBrowser",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                Toast.makeText(ctx, "加入队列失败，请重试", Toast.LENGTH_SHORT).show()
            }
            dismissAllowingStateLoss()
        }
    }

    /* ---------------- 适配器 ---------------- */

    private class ImageAdapter(
        private val items: List<PageImages.Image>,
        private val scope: CoroutineScope,
        private val onToggle: (PageImages.Image, Boolean) -> Unit,
    ) : RecyclerView.Adapter<ImageAdapter.VH>() {

        private val checked = HashSet<String>()

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(R.id.image_thumb)
            val check: CheckBox = view.findViewById(R.id.image_check)
            val size: TextView = view.findViewById(R.id.image_size)
            var job: Job? = null
        }

        fun selectAll() {
            items.forEach { checked.add(it.url) }
            notifyDataSetChanged()
        }

        fun clearSelection() {
            checked.clear()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_image, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val image = items[position]
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = image.url in checked
            holder.size.isVisible = image.width > 0
            holder.size.text = if (image.width > 0) "${image.width}×${image.height}" else ""

            holder.itemView.setOnClickListener {
                val nowChecked = image.url !in checked
                if (nowChecked) checked.add(image.url) else checked.remove(image.url)
                holder.check.isChecked = nowChecked
                onToggle(image, nowChecked)
            }

            // tag 用来防「滚动复用后旧图盖新图」：异步回来时对不上就丢掉
            holder.thumb.tag = image.url
            holder.thumb.setImageDrawable(null)
            holder.job?.cancel()
            ThumbCache.get(image.url)?.let {
                holder.thumb.setImageBitmap(it)
                return
            }
            holder.job = scope.launch {
                val bmp = withContext(Dispatchers.IO) { fetchThumb(image.url) }
                if (bmp == null || holder.thumb.tag != image.url) return@launch
                holder.thumb.setImageBitmap(bmp)
            }
        }

        override fun getItemCount(): Int = items.size

        private fun fetchThumb(url: String): Bitmap? {
            var conn: HttpURLConnection? = null
            return try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 12000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "image/*")
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.size < 32) return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 240) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
                if (bmp != null) ThumbCache.put(url, bmp)
                bmp
            } catch (e: Exception) {
                // 防盗链、超时、格式不支持：留空比显示裂图体面
                null
            } finally {
                conn?.disconnect()
            }
        }

        private companion object {
            const val UA =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        }
    }

    /** 解码后的缩略图，按字节数限 12MB，避免滑几十张把内存吃满 */
    private object ThumbCache {
        private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

        fun get(url: String): Bitmap? = cache.get(url)

        fun put(url: String, bitmap: Bitmap) {
            cache.put(url, bitmap)
        }
    }
}
