package com.local.threadssticker

import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.inputmethodservice.InputMethodService
import androidx.core.content.FileProvider
import coil3.ImageLoader
import coil3.load
import coil3.gif.GifDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

class StickerKeyboardService : InputMethodService() {
    private enum class ScopeMode { RECENT, FREQUENT, ALL }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repo: StickerRepository
    private lateinit var imageLoader: ImageLoader
    private lateinit var grid: GridView
    private lateinit var search: EditText
    private lateinit var categorySpinner: Spinner
    private lateinit var recentTab: TextView
    private lateinit var frequentTab: TextView
    private lateinit var allTab: TextView

    private var allStickers: List<StickerItem> = emptyList()
    private var allCategories: List<StickerCategory> = emptyList()
    private var scopeMode = ScopeMode.RECENT
    private var categoryMode: String? = null
    private var adapter: StickerAdapter? = null
    private var keyboardSwitchInProgress = false

    override fun onCreate() {
        super.onCreate()
        repo = (application as StickerApplication).repository
        imageLoader = ImageLoader.Builder(this).components { add(GifDecoder.Factory()) }.build()
        serviceScope.launch {
            combine(repo.stickers, repo.categories) { stickers, categories -> stickers to categories }
                .collect { (stickers, categories) ->
                    allStickers = stickers
                    allCategories = categories
                    updateCategorySpinner()
                    refreshGrid()
                }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(8))
            setBackgroundColor(PAPER)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        top.addView(arrowButton { returnToPreviousKeyboard() }, linear(dp(42), dp(42)))

        search = EditText(this).apply {
            hint = "搜尋分類"
            textSize = 14f
            setSingleLine(true)
            setTextColor(INK)
            setHintTextColor(MUTED)
            setPadding(dp(14), 0, dp(12), 0)
            background = roundedBackground(TILE, dp(18).toFloat())
            addTextChangedListener(SimpleTextWatcher { refreshGrid() })
        }
        top.addView(search, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginStart = dp(8)
        })

        categorySpinner = Spinner(this).apply {
            background = roundedBackground(WARM_CARD, dp(18).toFloat())
            setPadding(dp(8), 0, dp(6), 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    categoryMode = when (position) {
                        0 -> null
                        1 -> "__uncategorized__"
                        else -> allCategories.sortedBy { it.name.lowercase() }.getOrNull(position - 2)?.id
                    }
                    refreshGrid()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        top.addView(categorySpinner, LinearLayout.LayoutParams(dp(112), dp(42)).apply {
            marginStart = dp(8)
        })
        root.addView(top)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }
        recentTab = tabText("最近") { scopeMode = ScopeMode.RECENT; refreshGrid() }
        frequentTab = tabText("收藏") { scopeMode = ScopeMode.FREQUENT; refreshGrid() }
        allTab = tabText("全部") { scopeMode = ScopeMode.ALL; refreshGrid() }

        tabs.addView(recentTab, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) })
        tabs.addView(frequentTab, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
        tabs.addView(allTab, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4) })
        root.addView(tabs)

        grid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(8)
            horizontalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(1), dp(2), dp(1), dp(6))
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                this@StickerKeyboardService.adapter?.itemAt(position)?.let(::insertSticker)
            }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
                this@StickerKeyboardService.adapter?.itemAt(position)?.let(::shareSticker)
                true
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(286)))

        updateCategorySpinner()
        refreshGrid()
        return root
    }

    private fun updateCategorySpinner() {
        if (!::categorySpinner.isInitialized) return
        val current = categoryMode
        val sorted = allCategories.sortedBy { it.name.lowercase() }
        val labels = mutableListOf("全部", "未分類")
        labels += sorted.map { category ->
            val count = allStickers.count { category.id in it.categoryIds }
            category.name + "  " + count
        }

        val spinnerAdapter = object : android.widget.ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return super.getView(position, convertView, parent).apply {
                    (this as? TextView)?.apply {
                        setTextColor(INK)
                        textSize = 14f
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), 0, dp(8), 0)
                    }
                }
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return super.getDropDownView(position, convertView, parent).apply {
                    (this as? TextView)?.apply {
                        setTextColor(INK)
                        textSize = 14f
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        setBackgroundColor(TILE)
                    }
                }
            }
        }
        categorySpinner.adapter = spinnerAdapter

        val targetIndex = when (current) {
            null -> 0
            "__uncategorized__" -> 1
            else -> sorted.indexOfFirst { it.id == current }.takeIf { it >= 0 }?.plus(2) ?: 0
        }
        categorySpinner.setSelection(targetIndex, false)
    }

    private fun refreshGrid() {
        if (!::grid.isInitialized) return

        val base = when (scopeMode) {
            ScopeMode.RECENT -> allStickers.filter { it.lastUsedAt != null }.sortedByDescending { it.lastUsedAt }.take(20)
            ScopeMode.FREQUENT -> allStickers.filter { it.favoriteAt != null }.sortedByDescending { it.favoriteAt }
            ScopeMode.ALL -> allStickers.asReversed()
        }

        val categoryFiltered = when (val mode = categoryMode) {
            null -> base
            "__uncategorized__" -> base.filter { it.categoryIds.isEmpty() }
            else -> base.filter { mode in it.categoryIds }
        }

        val query = if (::search.isInitialized) search.text.toString().trim() else ""
        val queryCategoryIds = if (query.isBlank()) emptySet() else {
            allCategories.filter { it.name.contains(query, ignoreCase = true) }.map { it.id }.toSet()
        }
        val shown = if (query.isBlank()) categoryFiltered
        else categoryFiltered.filter { sticker -> sticker.categoryIds.any { it in queryCategoryIds } }

        adapter = StickerAdapter(this, shown, imageLoader)
        grid.adapter = adapter
        updateTabStyle()
    }

    private fun updateTabStyle() {
        if (!::recentTab.isInitialized) return

        fun style(view: TextView, active: Boolean) {
            view.setTextColor(INK)
            view.background = roundedBackground(
                if (active) WARM_SELECTED else Color.TRANSPARENT,
                dp(18).toFloat()
            )
        }

        style(recentTab, scopeMode == ScopeMode.RECENT)
        style(frequentTab, scopeMode == ScopeMode.FREQUENT)
        style(allTab, scopeMode == ScopeMode.ALL)
    }

    private fun insertSticker(sticker: StickerItem) {
        val editorInfo = currentInputEditorInfo
        if (!supportsRichImage(editorInfo, sticker.mimeType)) {
            Toast.makeText(this, "此輸入框不支援貼圖", Toast.LENGTH_SHORT).show()
            return
        }

        serviceScope.launch {
            runCatching {
                val file = repo.markUsedAndCache(sticker.id)
                val uri = FileProvider.getUriForFile(
                    this@StickerKeyboardService,
                    packageName + ".fileprovider",
                    file
                )
                val description = ClipDescription("Sticker", arrayOf(sticker.mimeType, "image/*"))
                val contentInfo = InputContentInfo(uri, description, null)
                val connection = currentInputConnection ?: error("目前沒有可用的輸入框")
                connection.commitContent(
                    contentInfo,
                    InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                    null
                )
            }.onSuccess { accepted ->
                if (!accepted) {
                    Toast.makeText(this@StickerKeyboardService, "此輸入框不支援貼圖", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(this@StickerKeyboardService, "插入貼圖失敗", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareSticker(sticker: StickerItem) {
        serviceScope.launch {
            runCatching {
                val file = repo.markUsedAndCache(sticker.id)
                val uri = FileProvider.getUriForFile(
                    this@StickerKeyboardService,
                    packageName + ".fileprovider",
                    file
                )
                val send = Intent(Intent.ACTION_SEND)
                    .setType(sticker.mimeType)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(
                    Intent.createChooser(send, "分享貼圖")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                Toast.makeText(this@StickerKeyboardService, "分享貼圖失敗", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun supportsRichImage(editorInfo: EditorInfo?, mimeType: String): Boolean {
        val supported = editorInfo?.contentMimeTypes ?: emptyArray()
        if (supported.isEmpty()) return false
        return supported.any { declared ->
            declared == "*/*" ||
                declared.equals("image/*", ignoreCase = true) ||
                declared.equals(mimeType, ignoreCase = true) ||
                (declared.endsWith("/*") && mimeType.startsWith(declared.substringBefore('/')))
        }
    }

    private fun returnToPreviousKeyboard() {
        if (keyboardSwitchInProgress) return
        keyboardSwitchInProgress = true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
            keyboardSwitchInProgress = false
            return
        }

        val accepted = switchToPreviousInputMethod()
        serviceScope.launch {
            delay(220)

            // switchToPreviousInputMethod() 回傳 true 只代表系統接受要求。
            // 若短時間後預設 IME 仍然是 Sticker Saver，補一次切換，避免只收起本鍵盤卻沒有接上上一個鍵盤。
            val activeIme = runCatching {
                Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            }.getOrNull()
            val stillStickerSaver = activeIme?.contains(packageName, ignoreCase = true) == true

            if (!accepted || stillStickerSaver) {
                val recovered = switchToPreviousInputMethod() || switchToNextInputMethod(false)
                if (!recovered) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showInputMethodPicker()
                }
            }

            delay(350)
            keyboardSwitchInProgress = false
        }
    }

    private fun arrowButton(click: () -> Unit): View =
        object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK
                style = Paint.Style.STROKE
                strokeWidth = dp(2.4f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            init {
                background = roundedBackground(WARM_CARD, dp(18).toFloat())
                setOnClickListener { click() }
                contentDescription = "返回上一個鍵盤"
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width * 0.53f
                val cy = height * 0.5f
                val arm = dp(7f)
                canvas.drawLine(cx + arm * 0.6f, cy, cx - arm, cy, paint)
                canvas.drawLine(cx - arm, cy, cx - arm * 0.25f, cy - arm * 0.75f, paint)
                canvas.drawLine(cx - arm, cy, cx - arm * 0.25f, cy + arm * 0.75f, paint)
            }
        }

    private fun tabText(label: String, click: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(INK)
        setOnClickListener { click() }
    }

    private fun roundedBackground(fillColor: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = radius
        }

    private fun linear(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private class StickerAdapter(
        private val context: Context,
        private val items: List<StickerItem>,
        private val imageLoader: ImageLoader,
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): StickerItem = items[position]
        override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()
        fun itemAt(position: Int): StickerItem? = items.getOrNull(position)

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val frame = (convertView as? FrameLayout) ?: FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(TILE)
                    cornerRadius = dp(context, 17).toFloat()
                }
                layoutParams = android.widget.AbsListView.LayoutParams(
                    android.widget.AbsListView.LayoutParams.MATCH_PARENT,
                    dp(context, 76)
                )
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(context, 7), dp(context, 7), dp(context, 7), dp(context, 7))
                }, FrameLayout.LayoutParams(-1, -1))
                addView(TextView(context).apply {
                    gravity = Gravity.CENTER
                    textSize = 22f
                    setShadowLayer(2f, 0f, 0f, Color.WHITE)
                }, FrameLayout.LayoutParams(dp(context, 32), dp(context, 32), Gravity.TOP or Gravity.RIGHT))
            }
            val sticker = items[position]
            val image = frame.getChildAt(0) as ImageView
            val star = frame.getChildAt(1) as TextView
            val model: Any = sticker.localCachePath?.takeIf { File(it).isFile && File(it).length() > 0L }?.let(::File)
                ?: sticker.mediaUrl
            image.setImageDrawable(null)
            image.load(model, imageLoader) {
                listener(onError = { _, _ ->
                    if (model is File) image.load(sticker.mediaUrl, imageLoader)
                })
            }
            star.text = if (sticker.favoriteAt != null) "★" else "☆"
            star.setTextColor(if (sticker.favoriteAt != null) Color.rgb(214, 161, 58) else MUTED)
            star.setOnClickListener { (context.applicationContext as StickerApplication).repository.toggleFavorite(sticker.id) }
            return frame
        }

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }

    private class SimpleTextWatcher(private val onChanged: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChanged()
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }

    companion object {
        private val PAPER = Color.rgb(247, 245, 242)
        private val INK = Color.rgb(36, 34, 32)
        private val MUTED = Color.rgb(117, 110, 103)
        private val WARM_CARD = Color.rgb(240, 236, 230)
        private val WARM_SELECTED = Color.rgb(232, 222, 210)
        private val TILE = Color.rgb(254, 253, 252)
    }
}
