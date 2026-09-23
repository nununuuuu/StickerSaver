package com.local.threadssticker

import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.Drawable
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.content.res.ColorStateList
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
import android.widget.HorizontalScrollView
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
    private var displayedStickers: List<StickerItem> = emptyList()
    private var categoryIdsInMenu: List<String?> = emptyList()
    private lateinit var updateBanner: TextView
    private val updateChecker by lazy { UpdateChecker(this) }
    private var keyboardUpdate: UpdateInfo? = null
    private var updateDownloading = false
    private var keyboardSwitchInProgress = false
    private var keyboardCheckRunning = false
    private var packMode = false
    private var selectedPackId: String? = null
    private val unclassifiedPackId = "__unclassified__"
    private lateinit var modeRow: LinearLayout
    private lateinit var packCovers: LinearLayout
    private lateinit var packStrip: HorizontalScrollView
    private lateinit var stickerTabs: LinearLayout
    private val imagePacks by lazy { ImagePackStore(applicationContext) }
    private var currentPackImages: List<PackImage> = emptyList()

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
            setPadding(dp(12), dp(9), dp(12), dp(8))
            setBackgroundColor(PAPER)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        top.addView(arrowButton { returnToPreviousKeyboard() }, linear(dp(40), dp(40)))

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
        top.addView(search, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginStart = dp(10)
        })

        categorySpinner = Spinner(this).apply {
            background = roundedBackground(WARM_CARD, dp(18).toFloat())
            foreground = pillRipple(18)
            setPadding(dp(8), 0, dp(6), 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    categoryMode = when (position) {
                        0 -> null
                        1 -> "__uncategorized__"
                        else -> categoryIdsInMenu.getOrNull(position)
                    }
                    refreshGrid()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        top.addView(categorySpinner, LinearLayout.LayoutParams(dp(105), dp(40)).apply {
            marginStart = dp(10)
        })
        root.addView(top)
        updateBanner = TextView(this).apply {
            textSize = 13f
            setTextColor(INK)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = roundedBackground(WARM_SELECTED, dp(14).toFloat())
            foreground = pillRipple(14)
            visibility = View.GONE
            setOnClickListener { startKeyboardUpdate() }
            setOnLongClickListener {
                keyboardUpdate?.let { updateChecker.dismissForToday(it.version) }
                keyboardUpdate = null
                renderUpdateBanner()
                true
            }
        }
        root.addView(updateBanner, LinearLayout.LayoutParams(-1, dp(38)).apply {
            topMargin = dp(5)
            bottomMargin = dp(4)
        })
        keyboardUpdate = updateChecker.cachedKeyboardUpdate()
        renderUpdateBanner()
        checkKeyboardUpdates()

        modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,dp(6),0,dp(6)) }
        modeRow.addView(tabText("貼圖庫") {packMode=false;showKeyboardMode()},LinearLayout.LayoutParams(0,dp(32),1f))
        modeRow.addView(tabText("圖集") {packMode=true;showKeyboardMode()},LinearLayout.LayoutParams(0,dp(32),1f))
        root.addView(modeRow)
        packCovers = LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        packStrip = HorizontalScrollView(this).apply {isHorizontalScrollBarEnabled=false;visibility=View.GONE;addView(packCovers)}
        root.addView(packStrip,LinearLayout.LayoutParams(-1,dp(58)).apply {topMargin=dp(4);bottomMargin=dp(4)})
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(9), dp(2), dp(9))
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
        stickerTabs = tabs

        grid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(12)
            horizontalSpacing = dp(10)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(2), dp(7), dp(2), dp(7))
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                if(packMode) currentPackImages.getOrNull(position)?.let(::insertPackImage)
                else this@StickerKeyboardService.adapter?.itemAt(position)?.let(::insertSticker)
            }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
                if(packMode) currentPackImages.getOrNull(position)?.let(::sharePackImage)
                else this@StickerKeyboardService.adapter?.itemAt(position)?.let(::shareSticker)
                true
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(286)).apply {topMargin=dp(4)})

        updateCategorySpinner()
        refreshGrid()
        showKeyboardMode()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardUpdate = updateChecker.cachedKeyboardUpdate()
        renderUpdateBanner()
        checkKeyboardUpdates()
        if(::grid.isInitialized)showKeyboardMode()
    }

    private fun checkKeyboardUpdates() {
        if (keyboardCheckRunning || !updateChecker.shouldAutoCheckNow()) return
        keyboardCheckRunning = true
        serviceScope.launch {
            runCatching { updateChecker.check() }
                .onSuccess { found ->
                    keyboardUpdate = found?.takeUnless { updateChecker.isDismissedToday(it.version) }
                }
            keyboardCheckRunning = false
            keyboardUpdate = updateChecker.cachedKeyboardUpdate()
            renderUpdateBanner()
        }
    }

    private fun renderUpdateBanner() {
        if (!::updateBanner.isInitialized) return
        val info = keyboardUpdate ?: updateChecker.cachedKeyboardUpdate()
        if (!updateChecker.autoCheckEnabled || info == null || updateChecker.isDismissedToday(info.version)) {
            updateBanner.visibility = View.GONE
            return
        }
        keyboardUpdate = info
        updateBanner.visibility = View.VISIBLE
        updateBanner.text = if (updateDownloading) "正在下載新版…" else "↑ 新版本 v" + info.version + " · 點擊下載更新"
    }

    private fun startKeyboardUpdate() {
        val info = keyboardUpdate ?: return
        if (updateDownloading) return
        updateDownloading = true
        renderUpdateBanner()
        serviceScope.launch {
            runCatching {
                val file = updateChecker.downloadApk(info) { progress ->
                    updateBanner.post {
                        if (::updateBanner.isInitialized) updateBanner.text =
                            "下載 v" + info.version + "… " + progress + "%"
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !packageManager.canRequestPackageInstalls()
                ) {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Toast.makeText(this@StickerKeyboardService, "請允許安裝後再次點擊更新", Toast.LENGTH_LONG).show()
                } else {
                    val uri = FileProvider.getUriForFile(this@StickerKeyboardService,
                        packageName + ".fileprovider", file)
                    startActivity(Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }.onFailure {
                Toast.makeText(this@StickerKeyboardService,
                    "更新失敗：" + (it.message ?: "請重試"), Toast.LENGTH_LONG).show()
            }
            updateDownloading = false
            renderUpdateBanner()
        }
    }

    private fun updateCategorySpinner() {
        if (!::categorySpinner.isInitialized) return
        val sorted = allCategories.sortedBy { it.name.lowercase() }
        val ids = listOf<String?>(null, "__uncategorized__") + sorted.map { it.id }
        if (ids == categoryIdsInMenu) return
        categoryIdsInMenu = ids
        val labels = listOf("全部分類", "未分類") + sorted.map { it.name }
        categorySpinner.adapter = object : android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                TextView(context).apply {
                    text = labels.getOrElse(position) { "全部分類" } + "  ▾"
                    textSize = 13f
                    setTextColor(INK)
                    gravity = Gravity.CENTER
                    setPadding(dp(4), 0, dp(4), 0)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                TextView(context).apply {
                    text = labels.getOrElse(position) { "全部分類" }
                    textSize = 14f
                    setTextColor(INK)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    setBackgroundColor(TILE)
                }
        }
        categorySpinner.setSelection(ids.indexOf(categoryMode).takeIf { it >= 0 } ?: 0, false)
    }

    private fun refreshGrid() {
        if (!::grid.isInitialized || packMode) return

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

        if (adapter == null) {
            adapter = StickerAdapter(this, shown, imageLoader)
            grid.adapter = adapter
        } else if (shown != displayedStickers) {
            adapter?.updateItems(shown)
        }
        displayedStickers = shown
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

    private fun showKeyboardMode() {
        if(!::grid.isInitialized || !::modeRow.isInitialized)return
        stickerTabs.visibility=if(packMode)View.GONE else View.VISIBLE
        search.visibility=if(packMode)View.GONE else View.VISIBLE
        categorySpinner.visibility=if(packMode)View.GONE else View.VISIBLE
        packStrip.visibility=if(packMode)View.VISIBLE else View.GONE
        (modeRow.getChildAt(0) as TextView).background=roundedBackground(if(packMode)Color.TRANSPARENT else WARM_SELECTED,dp(16).toFloat())
        (modeRow.getChildAt(1) as TextView).background=roundedBackground(if(packMode)WARM_SELECTED else Color.TRANSPARENT,dp(16).toFloat())
        if(packMode) {
            refreshPackGrid()
        } else {
            grid.adapter=adapter
            refreshGrid()
        }
    }

    private fun refreshPackGrid() {
        if(!packMode || !::grid.isInitialized)return
        // Reload persisted content when opening the keyboard after edits in the App.
        val store=ImagePackStore(applicationContext)
        val packs=store.packs.value.sortedBy {it.order}
        val images=store.images.value
        if(selectedPackId!=null && selectedPackId!=unclassifiedPackId && packs.none {it.id==selectedPackId})selectedPackId=null
        if(selectedPackId==null)selectedPackId=packs.firstOrNull()?.id ?: unclassifiedPackId
        packCovers.removeAllViews()
        val unclassified=images.filter {it.packId==null}
        if(unclassified.isNotEmpty()) {
            val active=selectedPackId==unclassifiedPackId
            packCovers.addView(TextView(this).apply {
                text="未分類"
                gravity=Gravity.CENTER
                textSize=12f
                setTextColor(INK)
                background=roundedBackground(if(active) WARM_SELECTED else Color.TRANSPARENT,dp(12).toFloat())
                foreground=pillRipple(12)
                setOnClickListener {selectedPackId=unclassifiedPackId;refreshPackGrid()}
            },LinearLayout.LayoutParams(dp(56),dp(50)).apply {marginEnd=dp(7)})
        }
        for(pack in packs) {
            val image=images.firstOrNull {it.id==pack.coverId}?:images.firstOrNull {it.packId==pack.id}
            val selected=selectedPackId==pack.id
            val coverButton=FrameLayout(this).apply {
                background=roundedBackground(if(selected) WARM_SELECTED else Color.TRANSPARENT,dp(12).toFloat())
                clipToOutline=true
                foreground=pillRipple(12)
                contentDescription=pack.name
                isClickable=true
                setOnClickListener {selectedPackId=pack.id;refreshPackGrid()}
            }
            val coverImage=ImageView(this).apply {
                scaleType=ImageView.ScaleType.FIT_CENTER
                background=roundedBackground(Color.TRANSPARENT,dp(9).toFloat())
                clipToOutline=true
                contentDescription=pack.name
                if(image!=null)load(File(image.display),imageLoader)
            }
            coverButton.addView(coverImage,FrameLayout.LayoutParams(dp(42),dp(42),Gravity.CENTER))
            packCovers.addView(coverButton,LinearLayout.LayoutParams(dp(50),dp(50)).apply {marginEnd=dp(7)})
        }
        currentPackImages=images.filter {it.packId==(if(selectedPackId==unclassifiedPackId)null else selectedPackId)}.sortedBy {it.order}
        grid.adapter=object:BaseAdapter() {
            override fun getCount()=currentPackImages.size
            override fun getItem(position:Int):Any=currentPackImages[position]
            override fun getItemId(position:Int)=currentPackImages[position].id.hashCode().toLong()
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup?):View {
                val view=(convertView as? ImageView)?:ImageView(this@StickerKeyboardService).apply {
                    scaleType=ImageView.ScaleType.CENTER_INSIDE
                    background=roundedBackground(Color.TRANSPARENT,dp(15).toFloat())
                    layoutParams=android.widget.AbsListView.LayoutParams(-1,dp(84))
                    setPadding(dp(4),dp(5),dp(4),dp(5))
                }
                val item=currentPackImages[position]
                view.load(File(item.display),imageLoader)
                view.contentDescription=if(item.mime=="image/gif")"GIF 動圖" else "圖片"
                return view
            }
        }
    }

    private fun insertPackImage(item:PackImage) {
        if(!supportsRichImage(currentInputEditorInfo,item.mime)) {
            Toast.makeText(this,"此輸入框不支援圖片，長按圖片可分享",Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val file=File(item.display)
            val uri=FileProvider.getUriForFile(this,packageName+".fileprovider",file)
            val description=ClipDescription("圖片梗圖",arrayOf(item.mime,"image/*"))
            val input=InputContentInfo(uri,description,null)
            currentInputConnection?.commitContent(input,InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,null)==true
        }.onFailure {Toast.makeText(this,"插入圖片失敗，請長按分享",Toast.LENGTH_SHORT).show()}
            .onSuccess {if(!it)Toast.makeText(this,"此輸入框不支援圖片，請長按分享",Toast.LENGTH_SHORT).show()}
    }

    private fun sharePackImage(item:PackImage) {
        runCatching {
            val file=File(item.display)
            val uri=FileProvider.getUriForFile(this,packageName+".fileprovider",file)
            val intent=Intent(Intent.ACTION_SEND).setType(item.mime)
                .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent,"分享圖片梗圖").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {Toast.makeText(this,"分享圖片失敗",Toast.LENGTH_SHORT).show()}
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

        // A second switch while the first IME is starting can hide the newly
        // selected keyboard. Make exactly one framework switch per tap.
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { switchToPreviousInputMethod() }.getOrDefault(false)
        } else {
            @Suppress("DEPRECATION")
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            @Suppress("DEPRECATION")
            runCatching { imm.switchToLastInputMethod(window.window?.attributes?.token) }
                .getOrDefault(false)
        }
        if (!switched) {
            // No last keyboard to return to; let Android select the next IME.
            showKeyboardPicker()
        }
        // Do not call switchToNextInputMethod(), hideSoftInput(), or requestHideSelf()
        // after a successful switch: Android now owns the hand-off.
        serviceScope.launch {
            delay(650)
            keyboardSwitchInProgress = false
        }
    }

    private fun showKeyboardPicker() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
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
        foreground = pillRipple(18)
        setOnClickListener { click() }
    }

    private fun pillRipple(radiusDp: Int): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(Color.argb(42, 52, 49, 46)),
            null,
            roundedBackground(Color.WHITE, dp(radiusDp).toFloat())
        )

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
        private var items: List<StickerItem>,
        private val imageLoader: ImageLoader,
    ) : BaseAdapter() {
        fun updateItems(next: List<StickerItem>) { items = next; notifyDataSetChanged() }
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): StickerItem = items[position]
        override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()
        fun itemAt(position: Int): StickerItem? = items.getOrNull(position)

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val frame = (convertView as? FrameLayout) ?: FrameLayout(context).apply {
                clipChildren = true
                clipToPadding = true
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(context, 17).toFloat()
                }
                layoutParams = android.widget.AbsListView.LayoutParams(
                    android.widget.AbsListView.LayoutParams.MATCH_PARENT,
                    dp(context, 84)
                )
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(context, 8), dp(context, 13), dp(context, 13), dp(context, 7))
                }, FrameLayout.LayoutParams(-1, -1))
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3))
                    setBackgroundColor(Color.TRANSPARENT)
                }, FrameLayout.LayoutParams(dp(context, 30), dp(context, 30), Gravity.TOP or Gravity.RIGHT).apply {
                    topMargin = dp(context, 3)
                    rightMargin = dp(context, 3)
                })
            }
            val sticker = items[position]
            val image = frame.getChildAt(0) as ImageView
            val star = frame.getChildAt(1) as ImageView
            val model: Any = sticker.localCachePath?.takeIf { File(it).isFile && File(it).length() > 0L }?.let(::File)
                ?: sticker.mediaUrl
            if (image.tag != sticker.id || image.drawable == null) image.load(model, imageLoader) {
                listener(onError = { _, _ ->
                    if (model is File) image.load(sticker.mediaUrl, imageLoader)
                })
            }
            image.tag = sticker.id
            star.setImageDrawable(SoftStarDrawable(sticker.favoriteAt != null))
            star.contentDescription = if (sticker.favoriteAt != null) "取消收藏" else "加入收藏"
            star.setOnClickListener { (context.applicationContext as StickerApplication).repository.toggleFavorite(sticker.id) }
            return frame
        }

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }

    private class SoftStarDrawable(private val favorite: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        override fun draw(canvas: Canvas) {
            val bounds = bounds
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val radius = minOf(bounds.width(), bounds.height()) * 0.36f
            val path = Path()
            for (point in 0 until 10) {
                val angle = Math.PI * (point / 5.0 - 0.5)
                val r = if (point % 2 == 0) radius else radius * 0.51f
                val x = cx + (Math.cos(angle) * r).toFloat()
                val y = cy + (Math.sin(angle) * r).toFloat()
                if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            paint.color = if (favorite) Color.rgb(214, 161, 58) else Color.rgb(117, 110, 103)
            paint.strokeWidth = radius * 0.15f
            paint.style = if (favorite) Paint.Style.FILL else Paint.Style.STROKE
            canvas.drawPath(path, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
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
