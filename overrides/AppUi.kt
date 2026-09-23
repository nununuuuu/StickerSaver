package com.local.threadssticker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private val Paper = Color(0xFFF7F5F2)
private val Ink = Color(0xFF242220)
private val Accent = Color(0xFF34312E)
private val Muted = Color(0xFF756E67)
private val WarmCard = Color(0xFFF0ECE6)
private val WarmSelected = Color(0xFFE8DED2)
private val Tile = Color(0xFFFEFDFC)

private enum class Tab { HOME, LIBRARY, SOURCES, SETTINGS }
private enum class LibraryTab { RECENT, FAVORITES, ALL }
private enum class CategoryDelimiter { PLUS, SLASH, SPACE }

@Composable
fun StickerApp(activity: ComponentActivity, initialSharedText: String?, focusedClipboardText: String?, resumeToken: Int) {
    val repo = (activity.application as StickerApplication).repository
    val stickers by repo.stickers.collectAsState()
    val sources by repo.sources.collectAsState()
    var tab by remember { mutableStateOf(Tab.HOME) }
    val context = LocalContext.current
    val checker = remember { UpdateChecker(context) }
    val scope = rememberCoroutineScope()

    var pendingInput by remember { mutableStateOf(initialSharedText) }
    var clipboardPromptUrl by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateProgress by remember { mutableIntStateOf(0) }
    var updateDownloading by remember { mutableStateOf(false) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val appPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }

    LaunchedEffect(initialSharedText) {
        if (!initialSharedText.isNullOrBlank()) {
            pendingInput = initialSharedText
            tab = Tab.HOME
        }
    }

    LaunchedEffect(focusedClipboardText) {
        val url = focusedClipboardText?.let(::extractThreadsUrl)
        if (!url.isNullOrBlank() &&
            appPrefs.getBoolean("clipboard_monitor", false) &&
            url != pendingInput &&
            clipboardPromptUrl == null
        ) {
            clipboardPromptUrl = url
        }
    }

    LaunchedEffect(Unit) {
        if (checker.shouldAutoCheck()) {
            delay(600)
            var info: UpdateInfo? = null
            for (attempt in 0..1) {
                val result = runCatching { checker.check() }
                if (result.isSuccess) {
                    info = result.getOrNull()
                    break
                }
                if (attempt == 0) delay(1500)
            }
            if (info != null && !checker.isDismissedToday(info!!.version)) {
                update = info
            }
        }
    }

    DisposableEffect(appPrefs) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (!appPrefs.getBoolean("clipboard_monitor", false)) return@OnPrimaryClipChangedListener
            val text = runCatching {
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
                    .orEmpty()
            }.getOrDefault("")
            extractThreadsUrl(text)?.let { url ->
                if (url != pendingInput && clipboardPromptUrl == null) {
                    clipboardPromptUrl = url
                }
            }
        }

        clipboard.addPrimaryClipChangedListener(listener)
        onDispose {
            clipboard.removePrimaryClipChangedListener(listener)
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            primaryContainer = WarmSelected,
            onPrimaryContainer = Ink,
            secondary = Muted,
            onSecondary = Color.White,
            secondaryContainer = WarmCard,
            onSecondaryContainer = Ink,
            tertiary = Accent,
            onTertiary = Color.White,
            tertiaryContainer = WarmCard,
            onTertiaryContainer = Ink,
            background = Paper,
            onBackground = Ink,
            surface = Paper,
            onSurface = Ink,
            surfaceVariant = WarmCard,
            onSurfaceVariant = Ink,
            error = Color(0xFFB3261E),
            onError = Color.White,
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
            outline = Muted,
            outlineVariant = WarmSelected
        )
    ) {
        Scaffold(
            containerColor = Paper,
            bottomBar = {
                NavigationBar(containerColor = Paper, tonalElevation = 0.dp) {
                    nav(Tab.HOME, tab, Icons.Outlined.AddCircle, "新增") { tab = it }
                    nav(Tab.LIBRARY, tab, Icons.Outlined.GridView, "貼圖") { tab = it }
                    nav(Tab.SOURCES, tab, Icons.Outlined.History, "來源") { tab = it }
                    nav(Tab.SETTINGS, tab, Icons.Outlined.Settings, "設定") { tab = it }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    Tab.HOME -> Home(activity, repo, stickers, pendingInput)
                    Tab.LIBRARY -> Library(repo, stickers)
                    Tab.SOURCES -> Sources(repo, sources, stickers)
                    Tab.SETTINGS -> Settings(repo, checker, { update = it }, { error = it })
                }
            }
        }

        clipboardPromptUrl?.let { url ->
            AlertDialog(
                onDismissRequest = { clipboardPromptUrl = null },
                containerColor = Paper,
                tonalElevation = 0.dp,
                shape = RoundedCornerShape(28.dp),
                title = {
                    Text(
                        "偵測到 Threads 連結",
                        color = Ink,
                        fontWeight = FontWeight.Bold
                    )
                },
                dismissButton = {
                    TextButton(onClick = { clipboardPromptUrl = null }) {
                        Text("取消", color = Ink)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            pendingInput = url
                            tab = Tab.HOME
                            clipboardPromptUrl = null
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color.White
                        )
                    ) { Text("貼上") }
                }
            )
        }

        update?.let { info ->
            AlertDialog(
                onDismissRequest = {
                    if (!updateDownloading) {
                        checker.dismissForToday(info.version)
                        update = null
                    }
                },
                containerColor = Paper,
                tonalElevation = 0.dp,
                shape = RoundedCornerShape(28.dp),
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "發現新版本 v" + info.version,
                            color = Ink,
                            fontWeight = FontWeight.Bold
                        )
                        info.releaseDate?.let { date ->
                            Text(
                                "更新日期：$date",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted
                            )
                        }
                    }
                },
                text = {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (info.features.isNotEmpty()) {
                            item {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    color = WarmCard
                                ) {
                                    Column(
                                        Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text("新增功能", color = Ink, fontWeight = FontWeight.Bold)
                                        info.features.forEach { feature ->
                                            Text(
                                                "• " + feature,
                                                color = Ink.copy(alpha = .88f),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (info.fixes.isNotEmpty()) {
                            item {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    color = WarmCard
                                ) {
                                    Column(
                                        Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text("修正項目", color = Ink, fontWeight = FontWeight.Bold)
                                        info.fixes.forEach { fix ->
                                            Text(
                                                "• " + fix,
                                                color = Ink.copy(alpha = .88f),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = Color(0xFFFFE6E3),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Outlined.WarningAmber,
                                            contentDescription = null,
                                            tint = Color(0xFF9B1C1C)
                                        )
                                        Spacer(Modifier.width(7.dp))
                                        Text(
                                            "重要提醒",
                                            color = Color(0xFF8A1717),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        "• 未及時更新可能導致部分功能無法正常使用",
                                        color = Color(0xFF8A1717),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "• 若選擇取消更新，可在「關於」內自行手動更新",
                                        color = Color(0xFF8A1717),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        if (updateDownloading || downloadedApk != null) {
                            item {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = WarmCard
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        LinearProgressIndicator(
                                            progress = { updateProgress / 100f },
                                            modifier = Modifier.fillMaxWidth(),
                                            color = Accent,
                                            trackColor = WarmSelected
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            if (downloadedApk != null) "下載完成，準備安裝"
                                            else "下載中 $updateProgress%",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Muted
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    if (!updateDownloading) {
                        TextButton(
                            onClick = {
                                checker.dismissForToday(info.version)
                                update = null
                            }
                        ) { Text("取消", color = Ink) }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val ready = downloadedApk
                            if (ready != null) {
                                installDownloadedApk(context, ready)
                            } else if (!updateDownloading) {
                                updateDownloading = true
                                updateProgress = 0
                                scope.launch {
                                    runCatching {
                                        checker.downloadApk(info) { updateProgress = it }
                                    }.onSuccess { file ->
                                        downloadedApk = file
                                        updateProgress = 100
                                        installDownloadedApk(context, file)
                                    }.onFailure {
                                        error = it.message ?: "更新下載失敗"
                                    }
                                    updateDownloading = false
                                }
                            }
                        },
                        enabled = !updateDownloading,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color.White,
                            disabledContainerColor = WarmSelected,
                            disabledContentColor = Muted
                        )
                    ) {
                        Text(if (downloadedApk != null) "安裝" else if (updateDownloading) "下載中…" else "更新")
                    }
                }
            )
        }

        error?.let { msg ->
            AlertDialog(
                onDismissRequest = { error = null },
                containerColor = Paper,
                tonalElevation = 0.dp,
                shape = RoundedCornerShape(28.dp),
                title = { Text("發生錯誤", color = Ink, fontWeight = FontWeight.Bold) },
                text = {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = WarmCard
                    ) {
                        Text(msg, Modifier.padding(14.dp), color = Ink)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { error = null },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color.White
                        )
                    ) { Text("知道了") }
                }
            )
        }
    }
}

@Composable
private fun RowScope.nav(tab: Tab, selected: Tab, icon: ImageVector, text: String, set: (Tab) -> Unit) {
    NavigationBarItem(
        selected = tab == selected,
        onClick = { set(tab) },
        icon = { Icon(icon, null) },
        label = { Text(text) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Accent,
            selectedTextColor = Accent,
            indicatorColor = WarmSelected,
            unselectedIconColor = Ink.copy(alpha = .78f),
            unselectedTextColor = Ink.copy(alpha = .78f)
        )
    )
}

@Composable
private fun Header(title: String) {
    Box(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Home(activity: ComponentActivity, repo: StickerRepository, stickers: List<StickerItem>, shared: String?) {
    var input by remember { mutableStateOf(shared.orEmpty()) }
    var post by remember { mutableStateOf(true) }
    var comments by remember { mutableStateOf(false) }
    var categoryText by remember { mutableStateOf("") }
    var categoryDelimiter by remember { mutableStateOf(CategoryDelimiter.PLUS) }
    var loading by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<ParseProgress?>(null) }
    var lastResult by remember { mutableStateOf<ParseResult?>(null) }
    var previewSelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val cancel = remember { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()
    val previewScrollBoundary = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = available

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
        }
    }

    fun mediaKey(url: String): String = url.substringBefore('?').substringBefore('#')

    LaunchedEffect(shared) {
        if (!shared.isNullOrBlank()) input = shared
    }

    fun startParse(includePost: Boolean, commentLimit: Int, loadAll: Boolean = false) {
        cancel.set(false)
        loading = true
        message = null
        progress = null
        scope.launch {
            runCatching {
                repo.parsePreview(
                    input,
                    ParseOptions(
                        parsePost = includePost,
                        parseComments = comments,
                        commentLoadMode = if (loadAll) CommentLoadMode.ALL else CommentLoadMode.TOP,
                        topCommentCount = commentLimit.coerceIn(1, 500)
                    ),
                    cancel,
                    { progress = it }
                )
            }.onSuccess { parsed ->
                val oldKeys = lastResult?.media
                    ?.map { mediaKey(it.url) }
                    ?.toSet()
                    .orEmpty()
                val oldDeselected = oldKeys - previewSelected
                val newKeys = parsed.media.map { mediaKey(it.url) }.toSet()
                previewSelected = newKeys - oldDeselected
                lastResult = parsed
                message = if (parsed.cancelled) {
                    "已停止後續解析；已完成 " + parsed.completedTasks + "/" + parsed.plannedTasks
                } else {
                    val parts = mutableListOf<String>()
                    if (includePost) parts += "貼文 " + parsed.postMedia.distinctBy { mediaKey(it.url) }.size
                    if (comments) parts += "留言貼圖 " + parsed.commentMedia.distinctBy { mediaKey(it.url) }.size
                    "解析完成：" + parts.joinToString("、") + "，尚未加入貼圖庫"
                }
            }.onFailure { message = it.message ?: "解析失敗" }
            loading = false
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Header("Sticker Saver") }
        item {
            Card(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = WarmCard),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        input,
                        { input = it },
                        Modifier.fillMaxWidth(),
                        placeholder = { Text("貼上 Threads 貼文連結", color = Muted.copy(alpha = .55f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Muted,
                            focusedContainerColor = Tile,
                            unfocusedContainerColor = Tile
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("分類（選填）", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = categoryText,
                        onValueChange = { if (!loading && !importing) categoryText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = {
                            Text(
                                when (categoryDelimiter) {
                                    CategoryDelimiter.PLUS -> "例如：寶可夢+貓咪+迷因"
                                    CategoryDelimiter.SLASH -> "例如：寶可夢/貓咪/迷因"
                                    CategoryDelimiter.SPACE -> "例如：寶可夢 貓咪 迷因"
                                },
                                color = Muted.copy(alpha = .55f)
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Muted,
                            focusedContainerColor = Tile,
                            unfocusedContainerColor = Tile
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            CategoryDelimiter.PLUS to "+",
                            CategoryDelimiter.SLASH to "/",
                            CategoryDelimiter.SPACE to "空白"
                        ).forEach { (delimiter, label) ->
                            FilterChip(
                                selected = categoryDelimiter == delimiter,
                                onClick = { if (!loading && !importing) categoryDelimiter = delimiter },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Tile,
                                    labelColor = Ink,
                                    selectedContainerColor = WarmSelected,
                                    selectedLabelColor = Ink
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = categoryDelimiter == delimiter,
                                    borderColor = Muted.copy(alpha = .35f),
                                    selectedBorderColor = Accent.copy(alpha = .35f)
                                )
                            )
                        }
                    }
                    parseCategoryNames(categoryText, categoryDelimiter).takeIf { it.isNotEmpty() }?.let { names ->
                        Text(
                            "將套用：" + names.joinToString("、"),
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("解析來源", fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            post,
                            { if (!loading && !importing) post = it },
                            colors = CheckboxDefaults.colors(checkedColor = Accent)
                        )
                        Text("貼文")
                        Spacer(Modifier.width(12.dp))
                        Checkbox(
                            comments,
                            {
                                if (!loading && !importing) {
                                    comments = it
                                    lastResult = null
                                    previewSelected = emptySet()
                                }
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Accent)
                        )
                        Text("留言")
                    }

                    if (comments) {
                        Text(
                            "初次讀取前 20 則預載留言，只顯示其中的 Sticker / GIF。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    if (!loading) {
                        Button(
                            onClick = {
                                if (!post && !comments) {
                                    message = "請至少選擇貼文或留言"
                                } else {
                                    lastResult = null
                                    previewSelected = emptySet()
                                    startParse(post, 20)
                                }
                            },
                            enabled = input.isNotBlank() && !importing,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = Color.White,
                                disabledContainerColor = WarmSelected,
                                disabledContentColor = Muted
                            )
                        ) { Text("開始解析") }
                    } else {
                        OutlinedButton(
                            onClick = {
                                cancel.set(true)
                                message = "已要求停止；目前項目完成後不再開始後續解析"
                            },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, Muted)
                        ) { Text("取消後續解析") }
                    }

                    progress?.let {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { if (it.plannedTasks == 0) 0f else it.completedTasks.toFloat() / it.plannedTasks },
                            modifier = Modifier.fillMaxWidth(),
                            color = Accent,
                            trackColor = WarmSelected
                        )
                        Text(
                            it.currentLabel + " · " + it.completedTasks + "/" + it.plannedTasks,
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted
                        )
                    }

                    lastResult?.takeIf { comments }?.let { parsed ->
                        val loaded = parsed.selectedCommentCount
                        val remaining = (parsed.availableCommentCount - loaded).coerceAtLeast(0)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "已讀取 " + loaded + " / " + parsed.availableCommentCount + " 則目前預載留言",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted
                        )
                        if (remaining > 0 && !loading && !importing) {
                            Spacer(Modifier.height(8.dp))
                            if (remaining >= 20) {
                                OutlinedButton(
                                    onClick = { startParse(false, loaded + 20) },
                                    modifier = Modifier.fillMaxWidth(),
                                    border = BorderStroke(1.dp, Muted)
                                ) { Text("再載入 20 則留言") }
                                Spacer(Modifier.height(6.dp))
                            }
                            OutlinedButton(
                                onClick = { startParse(false, 500, loadAll = true) },
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.dp, Muted)
                            ) { Text("載入剩餘 " + remaining + " 則留言") }
                        }
                    }

                    message?.let {
                        Text(
                            it,
                            Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted
                        )
                    }
                }
            }
        }

        lastResult?.let { parsed ->
            val previewItems = parsed.media.distinctBy { mediaKey(it.url) }
            if (previewItems.isNotEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = WarmCard
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("解析結果", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "已選 " + previewSelected.size + " / " + previewItems.size + " 張 · 尚未加入貼圖庫",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Muted
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        val all = previewItems.map { mediaKey(it.url) }.toSet()
                                        previewSelected = if (previewSelected.size == all.size) emptySet() else all
                                    }
                                ) {
                                    Text(if (previewSelected.size == previewItems.size) "全部取消" else "全選")
                                }
                            }

                            Spacer(Modifier.height(10.dp))
                            val rows = ((previewItems.size + 3) / 4).coerceIn(1, 4)
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height((rows * 86).dp)
                                    .nestedScroll(previewScrollBoundary),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(previewItems, key = { mediaKey(it.url) }) { media ->
                                    val key = mediaKey(media.url)
                                    val selected = key in previewSelected
                                    Box(
                                        Modifier
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable {
                                                previewSelected = if (selected) previewSelected - key
                                                else previewSelected + key
                                            }
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = if (selected) WarmSelected else Tile,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            AsyncImage(
                                                model = media.url,
                                                contentDescription = "解析貼圖預覽",
                                                modifier = Modifier.fillMaxSize().padding(5.dp)
                                            )
                                        }
                                        Surface(
                                            shape = CircleShape,
                                            color = if (selected) Accent else Paper.copy(alpha = .9f),
                                            modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                if (selected) {
                                                    Icon(
                                                        Icons.Outlined.Check,
                                                        null,
                                                        tint = Color.White,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (!importing) {
                                        importing = true
                                        scope.launch {
                                            runCatching {
                                                repo.importParsedResult(
                                                    result = parsed,
                                                    selectedCanonicalUrls = previewSelected,
                                                    categoryNames = parseCategoryNames(categoryText, categoryDelimiter)
                                                )
                                            }.onSuccess { added ->
                                                val sourceId = AppStore.stableId(parsed.sourceUrl)
                                                if (repo.sources.value.firstOrNull { it.id == sourceId }?.snapshotPath == null) {
                                                    SourceSnapshotter.capture(activity, sourceId, parsed.sourceUrl)?.let {
                                                        repo.attachSnapshot(sourceId, it)
                                                    }
                                                }
                                                message = "已加入 " + added + " 張貼圖"
                                                lastResult = null
                                                previewSelected = emptySet()
                                            }.onFailure {
                                                message = it.message ?: "加入貼圖庫失敗"
                                            }
                                            importing = false
                                        }
                                    }
                                },
                                enabled = previewSelected.isNotEmpty() && !importing,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Accent,
                                    contentColor = Color.White,
                                    disabledContainerColor = WarmSelected,
                                    disabledContentColor = Muted
                                )
                            ) {
                                if (importing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (importing) "加入中…" else "加入貼圖庫")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Library(repo: StickerRepository, stickers: List<StickerItem>) {
    val categories by repo.categories.collectAsState()
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val selected = LibraryTab.entries[pagerState.currentPage]
    var categoryQuery by remember { mutableStateOf("") }
    var uncategorizedOnly by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedStickerIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchCategoryDialog by remember { mutableStateOf(false) }
    var batchDeleteConfirm by remember { mutableStateOf(false) }

    fun leaveSelectionMode() {
        selectionMode = false
        selectedStickerIds = emptySet()
        batchCategoryDialog = false
        batchDeleteConfirm = false
    }

    fun filtered(base: List<StickerItem>): List<StickerItem> {
        if (uncategorizedOnly) return base.filter { it.categoryIds.isEmpty() }
        val query = categoryQuery.trim()
        if (query.isBlank()) return base
        val matchingIds = categories
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { it.id }
            .toSet()
        return base.filter { sticker -> sticker.categoryIds.any { it in matchingIds } }
    }

    fun listFor(tab: LibraryTab): List<StickerItem> {
        val base = when (tab) {
            LibraryTab.RECENT -> stickers.filter { it.lastUsedAt != null }.sortedByDescending { it.lastUsedAt }.take(20)
            LibraryTab.FAVORITES -> stickers.filter { it.favoriteAt != null }.sortedByDescending { it.favoriteAt }
            LibraryTab.ALL -> stickers.asReversed()
        }
        return filtered(base)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (selectionMode) "已選 " + selectedStickerIds.size + " 張" else "貼圖庫",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = {
                    if (selectionMode) leaveSelectionMode()
                    else {
                        selectionMode = true
                        selectedStickerIds = emptySet()
                    }
                }
            ) {
                Icon(
                    if (selectionMode) Icons.Outlined.Close else Icons.Outlined.Edit,
                    contentDescription = if (selectionMode) "結束批量編輯" else "批量編輯"
                )
            }

            if (!selectionMode) {
                Box(Modifier.widthIn(min = 140.dp, max = 180.dp)) {
                    OutlinedTextField(
                        value = if (uncategorizedOnly) "未分類" else categoryQuery,
                        onValueChange = {
                            uncategorizedOnly = false
                            categoryQuery = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("分類篩選") },
                        trailingIcon = {
                            IconButton(onClick = { categoryMenu = !categoryMenu }) {
                                Icon(Icons.Outlined.ArrowDropDown, contentDescription = "分類清單")
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Muted
                        )
                    )
                    DropdownMenu(
                        expanded = categoryMenu,
                        onDismissRequest = { categoryMenu = false },
                        containerColor = Paper,
                        tonalElevation = 0.dp
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部") },
                            onClick = {
                                categoryQuery = ""
                                uncategorizedOnly = false
                                categoryMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("未分類")
                                    Text(
                                        " · " + stickers.count { it.categoryIds.isEmpty() } + " 張",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Muted
                                    )
                                }
                            },
                            onClick = {
                                categoryQuery = ""
                                uncategorizedOnly = true
                                categoryMenu = false
                            }
                        )
                        categories.filter { category -> stickers.any { category.id in it.categoryIds } }
                            .sortedBy { it.name.lowercase() }.forEach { category ->
                            val count = stickers.count { category.id in it.categoryIds }
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(category.name)
                                        Text(
                                            " · " + count + " 張",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Muted
                                        )
                                    }
                                },
                                onClick = {
                                    categoryQuery = category.name
                                    uncategorizedOnly = false
                                    categoryMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        LibraryTabs(
            currentPage = pagerState.currentPage,
            pageOffset = pagerState.currentPageOffsetFraction,
            onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } }
        )

        if (selectionMode) {
            val shownNow = listFor(selected)
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        val shownIds = shownNow.map { it.id }.toSet()
                        selectedStickerIds = if (shownIds.isNotEmpty() && shownIds.all { it in selectedStickerIds }) {
                            selectedStickerIds - shownIds
                        } else {
                            selectedStickerIds + shownIds
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("全選")
                }
                Button(
                    onClick = { batchCategoryDialog = true },
                    enabled = selectedStickerIds.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Icon(Icons.Outlined.Label, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("分類")
                }
                FilledIconButton(
                    onClick = { batchDeleteConfirm = true },
                    enabled = selectedStickerIds.isNotEmpty(),
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFFB3261E),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFE6C9C6),
                        disabledContentColor = Color.White.copy(alpha = .72f)
                    )
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "刪除所選貼圖",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        } else {
            Spacer(Modifier.height(12.dp))
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page ->
            val shown = listFor(LibraryTab.entries[page])
            if (shown.isEmpty()) {
                Empty("沒有符合條件的貼圖")
            } else {
                StickerGrid(
                    repo = repo,
                    list = shown,
                    modifier = Modifier.fillMaxSize(),
                    selectionMode = selectionMode,
                    selectedIds = selectedStickerIds,
                    onSelectionChange = { id, selectedNow ->
                        selectedStickerIds = if (selectedNow) selectedStickerIds + id
                        else selectedStickerIds - id
                    }
                )
            }
        }
    }

    if (batchCategoryDialog) {
        BatchCategoryDialog(
            repo = repo,
            selectedStickerIds = selectedStickerIds,
            dismiss = { batchCategoryDialog = false },
            done = {
                batchCategoryDialog = false
                leaveSelectionMode()
            }
        )
    }

    if (batchDeleteConfirm) {
        DangerConfirmDialog(
            title = "刪除所選貼圖？",
            message = "將刪除 " + selectedStickerIds.size + " 張貼圖與其本機快取。分類本身不會在這裡刪除。",
            confirmLabel = "刪除",
            onConfirm = {
                repo.removeStickers(selectedStickerIds)
                leaveSelectionMode()
            },
            onDismiss = { batchDeleteConfirm = false }
        )
    }
}

@Composable
private fun BatchCategoryDialog(
    repo: StickerRepository,
    selectedStickerIds: Set<String>,
    dismiss: () -> Unit,
    done: () -> Unit,
) {
    val categories by repo.categories.collectAsState()
    val stickers by repo.stickers.collectAsState()
    var addMode by remember { mutableStateOf(true) }
    var selectedCategoryIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var newCategoryText by remember { mutableStateOf("") }

    val usedBySelection = remember(stickers, selectedStickerIds) {
        stickers
            .filter { it.id in selectedStickerIds }
            .flatMap { it.categoryIds }
            .toSet()
    }

    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Paper,
        tonalElevation = 0.dp,
        title = { Text("批量編輯分類") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                addMode = true
                                selectedCategoryIds = emptySet()
                            },
                        shape = RoundedCornerShape(18.dp),
                        color = if (addMode) WarmSelected else Tile,
                        border = BorderStroke(1.dp, if (addMode) Accent.copy(alpha = .35f) else Muted.copy(alpha = .45f))
                    ) {
                        Row(
                            Modifier.padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (addMode) {
                                Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("新增分類", fontWeight = if (addMode) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                addMode = false
                                selectedCategoryIds = emptySet()
                                newCategoryText = ""
                            },
                        shape = RoundedCornerShape(18.dp),
                        color = if (!addMode) WarmSelected else Tile,
                        border = BorderStroke(1.dp, if (!addMode) Accent.copy(alpha = .35f) else Muted.copy(alpha = .45f))
                    ) {
                        Row(
                            Modifier.padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (!addMode) {
                                Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("移除分類", fontWeight = if (!addMode) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }

                Text(
                    if (addMode) "將選擇的分類加入全部 " + selectedStickerIds.size + " 張貼圖"
                    else "從所選貼圖移除指定分類；沒有該分類的貼圖不受影響",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )

                if (addMode) {
                    OutlinedTextField(
                        value = newCategoryText,
                        onValueChange = { newCategoryText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("新增分類名稱") },
                        placeholder = { Text("可用 + 分隔多個分類") }
                    )
                }

                val shownCategories = if (addMode) categories else categories.filter { it.id in usedBySelection }
                if (shownCategories.isEmpty()) {
                    Text(
                        if (addMode) "目前沒有既有分類，也可以直接輸入新分類"
                        else "所選貼圖目前沒有可移除的分類",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(shownCategories.size, key = { shownCategories[it].id }) { index ->
                            val category = shownCategories[index]
                            FilterChip(
                                selected = category.id in selectedCategoryIds,
                                onClick = {
                                    selectedCategoryIds =
                                        if (category.id in selectedCategoryIds) selectedCategoryIds - category.id
                                        else selectedCategoryIds + category.id
                                },
                                label = {
                                    val count = stickers.count {
                                        it.id in selectedStickerIds && category.id in it.categoryIds
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(category.name)
                                        if (!addMode) {
                                            Text(
                                                " · " + count + " 張",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Muted
                                            )
                                        }
                                    }
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Tile,
                                    labelColor = Ink,
                                    selectedContainerColor = WarmSelected,
                                    selectedLabelColor = Ink
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = category.id in selectedCategoryIds,
                                    borderColor = Muted.copy(alpha = .45f),
                                    selectedBorderColor = Accent.copy(alpha = .35f)
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (addMode) {
                        val newNames = newCategoryText
                            .split('+')
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinctBy { it.lowercase() }
                        repo.addCategoriesToStickers(
                            selectedStickerIds,
                            selectedCategoryIds,
                            newNames
                        )
                    } else {
                        repo.removeCategoriesFromStickers(
                            selectedStickerIds,
                            selectedCategoryIds
                        )
                    }
                    done()
                },
                enabled = selectedCategoryIds.isNotEmpty() || (addMode && newCategoryText.isNotBlank()),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text("套用") }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text("取消") }
        }
    )
}

@Composable
private fun LibraryTabs(
    currentPage: Int,
    pageOffset: Float,
    onSelect: (Int) -> Unit,
) {
    val labels = listOf("最近", "收藏", "全部")
    BoxWithConstraints(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(22.dp))
    ) {
        val tabWidth = maxWidth / labels.size
        val position = (currentPage + pageOffset).coerceIn(0f, (labels.size - 1).toFloat())

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = WarmSelected,
            modifier = Modifier
                .offset(x = tabWidth * position)
                .width(tabWidth)
                .fillMaxHeight()
        ) {}

        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = Ink,
                        fontWeight = if (index == currentPage && kotlin.math.abs(pageOffset) < .5f) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerGrid(
    repo: StickerRepository,
    list: List<StickerItem>,
    modifier: Modifier,
    selectionMode: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onSelectionChange: (String, Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val categories by repo.categories.collectAsState()
    var editTarget by remember { mutableStateOf<StickerItem?>(null) }
    var deleteTarget by remember { mutableStateOf<StickerItem?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(list, key = { it.id }) { sticker ->
            val selected = sticker.id in selectedIds
            Box {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (selected) WarmSelected else Tile,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .combinedClickable(
                            onClick = {
                                if (selectionMode) {
                                    onSelectionChange(sticker.id, !selected)
                                } else {
                                    scope.launch {
                                        runCatching {
                                            val file = repo.markUsedAndCache(sticker.id)
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                context.packageName + ".fileprovider",
                                                file
                                            )
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Sticker", uri))
                                        }.onSuccess {
                                            Toast.makeText(context, "已複製貼圖", Toast.LENGTH_SHORT).show()
                                        }.onFailure {
                                            Toast.makeText(context, "複製失敗：" + (it.message ?: "未知錯誤"), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onLongClick = {
                                if (selectionMode) onSelectionChange(sticker.id, !selected)
                                else editTarget = sticker
                            }
                        )
                ) {
                    AsyncImage(
                        sticker.localCachePath?.let(::File) ?: sticker.mediaUrl,
                        contentDescription = "貼圖",
                        modifier = Modifier.fillMaxSize().padding(6.dp)
                    )
                }
                if (!selectionMode) {
                    IconButton(
                        onClick = { repo.toggleFavorite(sticker.id) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(32.dp)
                    ) {
                        Icon(
                            if (sticker.favoriteAt != null) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (sticker.favoriteAt != null) "取消收藏" else "加入收藏",
                            tint = if (sticker.favoriteAt != null) Color(0xFFD6A13A) else Muted,
                            modifier = Modifier.size(23.dp)
                        )
                    }
                }
                if (selectionMode) {
                    Surface(
                        shape = CircleShape,
                        color = if (selected) Accent else Paper.copy(alpha = .92f),
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (selected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = "已選取",
                                    tint = Color.White,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editTarget?.let { target ->
        val fresh = repo.stickers.collectAsState().value.firstOrNull { it.id == target.id } ?: target
        var selectedIdsLocal by remember(fresh.id, fresh.categoryIds) { mutableStateOf(fresh.categoryIds.toSet()) }
        var newCategoryText by remember(fresh.id) { mutableStateOf("") }
        var pendingNewNames by remember(fresh.id) { mutableStateOf<List<String>>(emptyList()) }

        fun addCategoryFromInput() {
            val names = newCategoryText
                .split('+')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }

            if (names.isEmpty()) return

            names.forEach { name ->
                val existing = categories.firstOrNull { it.name.equals(name, ignoreCase = true) }
                if (existing != null) {
                    selectedIdsLocal = selectedIdsLocal + existing.id
                } else if (pendingNewNames.none { it.equals(name, ignoreCase = true) }) {
                    pendingNewNames = pendingNewNames + name
                }
            }
            newCategoryText = ""
        }

        AlertDialog(
            onDismissRequest = { editTarget = null },
            containerColor = Paper,
            tonalElevation = 0.dp,
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 500.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Tile,
                        modifier = Modifier.size(190.dp).align(Alignment.CenterHorizontally)
                    ) {
                        AsyncImage(
                            model = fresh.localCachePath?.let(::File) ?: fresh.mediaUrl,
                            contentDescription = "貼圖預覽",
                            modifier = Modifier.fillMaxSize().padding(8.dp)
                        )
                    }
                    Text("分類", fontWeight = FontWeight.SemiBold)

                    val assignedCategories = categories
                        .filter { it.id in selectedIdsLocal }
                        .sortedBy { it.name.lowercase() }

                    if (assignedCategories.isEmpty() && pendingNewNames.isEmpty()) {
                        Text(
                            "這張貼圖目前沒有分類",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 2.dp)
                        ) {
                            items(assignedCategories.size, key = { assignedCategories[it].id }) { index ->
                                val category = assignedCategories[index]
                                InputChip(
                                    selected = true,
                                    onClick = { selectedIdsLocal = selectedIdsLocal - category.id },
                                    label = { Text(category.name) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = "移除分類",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    colors = InputChipDefaults.inputChipColors(
                                        containerColor = Tile,
                                        labelColor = Ink,
                                        selectedContainerColor = WarmSelected,
                                        selectedLabelColor = Ink
                                    )
                                )
                            }
                            items(pendingNewNames.size, key = { "new|" + pendingNewNames[it].lowercase() }) { index ->
                                val name = pendingNewNames[index]
                                InputChip(
                                    selected = true,
                                    onClick = {
                                        pendingNewNames = pendingNewNames.filterNot {
                                            it.equals(name, ignoreCase = true)
                                        }
                                    },
                                    label = { Text(name) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = "移除新增分類",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    colors = InputChipDefaults.inputChipColors(
                                        selectedContainerColor = WarmSelected,
                                        selectedLabelColor = Ink
                                    )
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newCategoryText,
                            onValueChange = { newCategoryText = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("新增分類") },
                            placeholder = { Text("輸入分類名稱") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = Muted,
                                focusedContainerColor = Tile,
                                unfocusedContainerColor = Tile
                            )
                        )
                        Button(
                            onClick = { addCategoryFromInput() },
                            enabled = newCategoryText.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = Color.White,
                                disabledContainerColor = WarmCard,
                                disabledContentColor = Muted
                            )
                        ) {
                            Text("新增")
                        }
                    }
                    Text(
                        "只顯示這張貼圖自己的分類；點分類標籤上的 × 可從這張貼圖移除。",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(
                        onClick = {
                            editTarget = null
                            deleteTarget = fresh
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("刪除貼圖", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newCategoryText.isNotBlank()) addCategoryFromInput()
                        repo.updateStickerCategories(
                            fresh.id,
                            selectedIdsLocal.toList(),
                            pendingNewNames
                        )
                        editTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("儲存") }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text("取消") }
            }
        )
    }

    deleteTarget?.let { sticker ->
        DangerConfirmDialog(
            title = "刪除這張貼圖？",
            message = "會從 Sticker Saver 貼圖庫移除，並刪除本機貼圖檔案。",
            confirmLabel = "刪除",
            onConfirm = {
                repo.removeSticker(sticker.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Sources(repo: StickerRepository, sources: List<SourceRecord>, stickers: List<StickerItem>) {
    var editing by remember { mutableStateOf<SourceRecord?>(null) }
    var deleting by remember { mutableStateOf<SourceRecord?>(null) }
    var viewing by remember { mutableStateOf<SourceRecord?>(null) }
    BackHandler(enabled = viewing != null && editing == null && deleting == null) { viewing = null }

    Column(Modifier.fillMaxSize()) {
        if (viewing != null) {
            val source = viewing!!
            val related = stickers.filter { it.sourceUrl == source.url || it.id in source.stickerIds }.distinctBy { it.id }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewing = null }) { Icon(Icons.Outlined.ArrowBack, contentDescription = "返回來源紀錄") }
                Column(Modifier.weight(1f)) {
                    Text(source.author?.let { "@$it" } ?: "來源貼圖", fontWeight = FontWeight.Bold, color = Ink)
                    Text("已導入 " + related.size + " 張貼圖", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (related.isEmpty()) Empty("此來源目前沒有已導入的貼圖")
            else StickerGrid(repo = repo, list = related, modifier = Modifier.fillMaxSize())
        } else if (sources.isEmpty()) {
            Header("來源紀錄")
            Empty("還沒有來源紀錄")
        } else {
            Header("來源紀錄")
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sources.size) { i ->
                    val source = sources[i]
                    val related = stickers.filter { it.id in source.stickerIds }
                    PostSourceCard(
                        source = source,
                        related = related,
                        onEditNote = { editing = source },
                        onOpen = { viewing = source },
                        onDelete = { deleting = source },
                    )
                }
            }
        }
    }

    editing?.let { source ->
        var note by remember(source.id) { mutableStateOf(source.note) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Paper,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text("備註", color = Ink, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Muted,
                        focusedContainerColor = Tile,
                        unfocusedContainerColor = Tile
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        repo.updateNote(source.id, note)
                        editing = null
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = Color.White
                    )
                ) { Text("儲存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消", color = Ink) }
            }
        )
    }

    deleting?.let { source ->
        val count = stickers.count { it.sourceUrl == source.url || it.id in source.stickerIds }
        DangerConfirmDialog(
            title = "刪除這筆來源？",
            message = if (count > 0) {
                "會同時刪除這篇來源與其 $count 張貼圖及本機快取。"
            } else {
                "會刪除這篇來源紀錄。"
            },
            confirmLabel = "刪除",
            onConfirm = {
                repo.removeSource(source.id)
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PostSourceCard(
    source: SourceRecord,
    related: List<StickerItem>,
    onEditNote: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onDelete,
            ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = WarmCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val thumbModel: Any? = source.thumbnailUrl
                    ?: related.firstOrNull()?.localCachePath?.let(::File)
                    ?: related.firstOrNull()?.mediaUrl
                    ?: source.snapshotPath?.let(::File)

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Tile,
                    modifier = Modifier.size(48.dp)
                ) {
                    if (thumbModel != null) {
                        AsyncImage(
                            model = thumbModel,
                            contentDescription = "來源縮圖",
                            modifier = Modifier.fillMaxSize().padding(3.dp)
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Text("@", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        source.author?.let { "@$it" } ?: "Threads 貼文",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        source.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(related.size.toString() + " 張  ›", style = MaterialTheme.typography.bodySmall, color = Muted)
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (source.note.isNotBlank()) {
                    Surface(
                        modifier = Modifier.clickable(onClick = onEditNote),
                        shape = RoundedCornerShape(12.dp),
                        color = WarmSelected
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.EditNote,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Accent
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                source.note,
                                color = Ink,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    TextButton(onClick = onEditNote) {
                        Icon(Icons.Outlined.EditNote, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新增備註")
                    }
                }

                Spacer(Modifier.weight(1f))

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .combinedClickable(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
                                }.onFailure {
                                    Toast.makeText(context, "無法開啟來源網址", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onLongClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Threads URL", source.url))
                                Toast.makeText(context, "已複製網址", Toast.LENGTH_SHORT).show()
                            }
                        ),
                    color = Tile,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Muted.copy(alpha = .4f))
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("前往", color = Ink)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp), tint = Ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun Settings(
    repo: StickerRepository,
    checker: UpdateChecker,
    found: (UpdateInfo) -> Unit,
    failed: (String) -> Unit
) {
    val context = LocalContext.current
    var about by remember { mutableStateOf(false) }
    var categoryManager by remember { mutableStateOf(false) }
    var clearCacheConfirm by remember { mutableStateOf(false) }
    var clearStickerDataConfirm by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableLongStateOf(repo.stickerCacheSizeBytes()) }
    val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    var clipboardMonitor by remember { mutableStateOf(prefs.getBoolean("clipboard_monitor", false)) }

    Column(Modifier.fillMaxSize()) {
        Header("設定")
        SettingRow("鍵盤", "啟用 Sticker Saver 鍵盤", Icons.Outlined.Keyboard) {
            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Spacer(Modifier.height(10.dp))
        SettingSwitchRow(
            title = "監聽剪貼簿",
            sub = "App 使用中偵測 Threads 連結並詢問是否貼入",
            icon = Icons.Outlined.ContentPaste,
            checked = clipboardMonitor,
            onChecked = {
                clipboardMonitor = it
                prefs.edit().putBoolean("clipboard_monitor", it).apply()
            }
        )
        Spacer(Modifier.height(10.dp))
        SettingRow("分類標籤管理", "新增、重新命名或刪除分類", Icons.Outlined.Label) {
            categoryManager = true
        }
        Spacer(Modifier.height(10.dp))
        SettingRow(
            "清除快取",
            "已下載貼圖快取 · " + formatBytes(cacheSize),
            Icons.Outlined.DeleteSweep
        ) {
            cacheSize = repo.stickerCacheSizeBytes()
            clearCacheConfirm = true
        }
        Spacer(Modifier.height(10.dp))
        DangerSettingRow(
            title = "清除貼圖資料",
            sub = "刪除貼圖、來源、分類與相關本機檔案",
            icon = Icons.Outlined.DeleteForever
        ) {
            clearStickerDataConfirm = true
        }
        Spacer(Modifier.height(10.dp))
        SettingRow("關於", "版本 " + checker.currentVersion(), Icons.Outlined.Info) { about = true }
    }

    if (categoryManager) {
        CategoryManagerDialog(repo = repo, dismiss = { categoryManager = false })
    }

    if (clearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { clearCacheConfirm = false },
            containerColor = Paper,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(28.dp),
            title = { Text("清除貼圖快取？", color = Ink, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = WarmCard
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                tint = Accent
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "清除後，貼圖會在下次使用時重新下載並建立快取，第一次載入可能較慢。",
                                color = Ink,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text(
                        "這只會刪除已下載的貼圖媒體快取（目前 " + formatBytes(cacheSize) + "），不會刪除貼圖庫紀錄、分類或來源資料。",
                        color = Ink
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        repo.clearStickerCache()
                        cacheSize = repo.stickerCacheSizeBytes()
                        clearCacheConfirm = false
                        Toast.makeText(context, "貼圖快取已清除", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = Color.White
                    )
                ) { Text("清除快取") }
            },
            dismissButton = {
                TextButton(onClick = { clearCacheConfirm = false }) { Text("取消", color = Ink) }
            }
        )
    }

    if (clearStickerDataConfirm) {
        DangerConfirmDialog(
            title = "清除所有貼圖資料？",
            message = "將刪除所有貼圖、已下載檔案、來源紀錄、來源快照、分類與相關備註。App 設定會保留。此操作無法復原。",
            confirmLabel = "清除貼圖資料",
            onConfirm = {
                repo.clearStickerData()
                cacheSize = 0L
                clearStickerDataConfirm = false
            },
            onDismiss = { clearStickerDataConfirm = false }
        )
    }

    if (about) About(checker, { about = false }, found, failed)
}

@Composable
private fun CategoryManagerDialog(repo: StickerRepository, dismiss: () -> Unit) {
    val categories by repo.categories.collectAsState()
    val stickers by repo.stickers.collectAsState()
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<StickerCategory?>(null) }
    var deleteTarget by remember { mutableStateOf<StickerCategory?>(null) }
    val sortedCategories = remember(categories) { categories.sortedBy { it.name.lowercase() } }

    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Paper,
        tonalElevation = 0.dp,
        title = { Text("分類標籤管理") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("新增分類") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Muted,
                            focusedContainerColor = Tile,
                            unfocusedContainerColor = Tile
                        )
                    )
                    Button(
                        onClick = {
                            repo.ensureCategories(listOf(newName))
                            newName = ""
                        },
                        enabled = newName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color.White,
                            disabledContainerColor = WarmSelected,
                            disabledContentColor = Muted
                        )
                    ) { Text("新增") }
                }

                Text(
                    "共 " + sortedCategories.size + " 個分類",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )

                if (sortedCategories.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = WarmCard
                    ) {
                        Box(
                            Modifier.padding(vertical = 28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("目前沒有分類標籤", color = Muted)
                        }
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = WarmCard
                    ) {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 340.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(
                                count = sortedCategories.size,
                                key = { sortedCategories[it].id }
                            ) { index ->
                                val category = sortedCategories[index]
                                val count = stickers.count { category.id in it.categoryIds }

                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 14.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            category.name,
                                            color = Ink,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            " · " + count + " 張",
                                            color = Muted,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1
                                        )
                                    }

                                    IconButton(
                                        onClick = { renameTarget = category },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Edit,
                                            contentDescription = "重新命名分類",
                                            tint = Ink.copy(alpha = .8f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { deleteTarget = category },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = "刪除分類",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                if (index < sortedCategories.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                        color = Muted.copy(alpha = .16f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = dismiss) { Text("完成", color = Ink) }
        }
    )

    renameTarget?.let { category ->
        var value by remember(category.id) { mutableStateOf(category.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = Paper,
            tonalElevation = 0.dp,
            title = { Text("重新命名分類") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Muted,
                        focusedContainerColor = Tile,
                        unfocusedContainerColor = Tile
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        repo.renameCategory(category.id, value)
                        renameTarget = null
                    },
                    enabled = value.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = Color.White,
                        disabledContainerColor = WarmSelected,
                        disabledContentColor = Muted
                    )
                ) { Text("儲存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消", color = Ink) }
            }
        )
    }

    deleteTarget?.let { category ->
        val count = stickers.count { category.id in it.categoryIds }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = Paper,
            tonalElevation = 0.dp,
            title = { Text("刪除「" + category.name + "」？") },
            text = {
                Text(
                    if (count > 0) {
                        "這個分類目前用於 " + count + " 張貼圖。刪除後會從這些貼圖移除分類標籤，但貼圖本身不會被刪除。"
                    } else {
                        "將永久刪除此分類標籤。貼圖本身不會被刪除。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    repo.deleteCategory(category.id)
                    deleteTarget = null
                }) {
                    Text("刪除分類", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消", color = Ink) }
            }
        )
    }
}

@Composable
private fun About(checker: UpdateChecker, dismiss: () -> Unit, found: (UpdateInfo) -> Unit, failed: (String) -> Unit) {
    val context = LocalContext.current
    var auto by remember { mutableStateOf(checker.autoCheckEnabled) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var lastChecked by remember { mutableStateOf(checker.lastSuccessfulCheckText()) }
    var historyExpanded by remember { mutableStateOf(false) }
    var expandedVersions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var historyLoading by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<ReleaseHistoryItem>>(emptyList()) }
    var historyError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val buildNumber = remember {
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        }.getOrDefault(0L)
    }

    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Paper,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(28.dp),
        title = { Text("關於", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = WarmCard
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Sticker Saver", color = Ink, fontWeight = FontWeight.SemiBold)
                            Text(
                                "版本 " + checker.currentVersion() + " · Build " + buildNumber,
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted
                            )
                        }
                    }
                }

                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = WarmCard
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("自動更新", color = Ink, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "啟動 App 時自動檢查更新",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Muted
                                    )
                                }
                                Switch(
                                    checked = auto,
                                    onCheckedChange = {
                                        auto = it
                                        checker.autoCheckEnabled = it
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Accent,
                                        uncheckedThumbColor = Muted,
                                        uncheckedTrackColor = WarmSelected,
                                        uncheckedBorderColor = Muted.copy(alpha = .35f)
                                    )
                                )
                            }

                            HorizontalDivider(
                                Modifier.padding(vertical = 10.dp),
                                color = Muted.copy(alpha = .16f)
                            )

                            lastChecked?.let {
                                Text(
                                    "最近檢查：$it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            Button(
                                onClick = {
                                    if (!checking) {
                                        checking = true
                                        status = null
                                        scope.launch {
                                            runCatching { checker.check() }
                                                .onSuccess {
                                                    lastChecked = checker.lastSuccessfulCheckText()
                                                    if (it == null) status = "目前已是最新版本"
                                                    else {
                                                        dismiss()
                                                        found(it)
                                                    }
                                                }
                                                .onFailure { failed(it.message ?: "檢查更新失敗") }
                                            checking = false
                                        }
                                    }
                                },
                                enabled = !checking,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Accent,
                                    contentColor = Color.White,
                                    disabledContainerColor = WarmSelected,
                                    disabledContentColor = Muted
                                )
                            ) {
                                if (checking) {
                                    CircularProgressIndicator(
                                        Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                } else {
                                    Icon(Icons.Outlined.SystemUpdateAlt, null)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(if (checking) "檢查中…" else "檢查更新")
                            }
                            status?.let {
                                Text(
                                    it,
                                    Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted
                                )
                            }
                        }
                    }
                }

                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = WarmCard
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        val expanding = !historyExpanded
                                        historyExpanded = expanding
                                        if (!expanding) expandedVersions = emptySet()
                                        if (expanding && history.isEmpty() && !historyLoading) {
                                            historyLoading = true
                                            historyError = null
                                            scope.launch {
                                                runCatching { checker.recentReleases(5) }
                                                    .onSuccess { history = it }
                                                    .onFailure { historyError = it.message ?: "讀取更新紀錄失敗" }
                                                historyLoading = false
                                            }
                                        }
                                    }
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.History, contentDescription = null, tint = Accent)
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    "更新紀錄",
                                    color = Ink,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    if (historyExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = if (historyExpanded) "收合" else "展開",
                                    tint = Muted
                                )
                            }

                            if (historyExpanded) {
                                Spacer(Modifier.height(10.dp))
                                when {
                                    historyLoading -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(
                                                Modifier.size(17.dp),
                                                strokeWidth = 2.dp,
                                                color = Accent
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text("讀取中…", color = Muted, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    historyError != null -> {
                                        Text(
                                            historyError.orEmpty(),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    history.isEmpty() -> {
                                        Text("目前沒有更新紀錄", color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                    else -> {
                                        history.take(5).forEachIndexed { index, item ->
                                            if (index > 0) {
                                                HorizontalDivider(
                                                    Modifier.padding(vertical = 6.dp),
                                                    color = Muted.copy(alpha = .16f)
                                                )
                                            }
                                            val expanded = item.version in expandedVersions
                                            Column {
                                                Row(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .clickable {
                                                            expandedVersions =
                                                                if (expanded) expandedVersions - item.version
                                                                else expandedVersions + item.version
                                                        }
                                                        .padding(vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        "v" + item.version,
                                                        color = Ink,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Spacer(Modifier.weight(1f))
                                                    item.releaseDate?.let {
                                                        Text(
                                                            it,
                                                            color = Muted,
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                        Spacer(Modifier.width(6.dp))
                                                    }
                                                    Icon(
                                                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                                        contentDescription = if (expanded) "收合版本" else "展開版本",
                                                        tint = Muted,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }

                                                if (expanded) {
                                                    if (item.changes.isEmpty()) {
                                                        Text(
                                                            "此版本未提供簡短更新內容",
                                                            color = Muted,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
                                                        )
                                                    } else {
                                                        item.changes.forEach { change ->
                                                            Text(
                                                                "• " + change,
                                                                color = Muted,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                modifier = Modifier.padding(start = 2.dp, top = 3.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = dismiss) { Text("完成", color = Ink) }
        }
    )
}

@Composable
private fun DangerConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFFFFE6E3)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = Color(0xFF9B1C1C)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        message,
                        color = Color(0xFF7F1D1D)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White
                )
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Ink) }
        }
    )
}

@Composable
private fun DangerSettingRow(
    title: String,
    sub: String,
    icon: ImageVector,
    click: () -> Unit
) {
    val danger = MaterialTheme.colorScheme.error
    Surface(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = click),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFFFFF3F1)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = danger)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = danger)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A4A45))
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = danger)
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    sub: String,
    icon: ImageVector,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Surface(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Tile
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Switch(
                checked = checked,
                onCheckedChange = onChecked,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Accent
                )
            )
        }
    }
}

@Composable
private fun SettingRow(title: String, sub: String, icon: ImageVector, click: () -> Unit) {
    Surface(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = click),
        shape = RoundedCornerShape(20.dp),
        color = Tile
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Muted)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) bytes.toString() + " " + units[unit]
    else String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
}

private fun extractThreadsUrl(text: String): String? =
    Regex(
        "https?://(?:www\\.)?(?:threads\\.com|threads\\.net)/[^\\s]+",
        RegexOption.IGNORE_CASE
    ).find(text)?.value?.trimEnd('.', ',', ')', ']', '}')

private fun installDownloadedApk(context: Context, file: File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Toast.makeText(context, "請允許 Sticker Saver 安裝更新，返回後再按「安裝」", Toast.LENGTH_LONG).show()
        return
    }

    val uri = FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun parseCategoryNames(text: String, delimiter: CategoryDelimiter): List<String> {
    if (text.isBlank()) return emptyList()
    val parts = when (delimiter) {
        CategoryDelimiter.PLUS -> text.split('+')
        CategoryDelimiter.SLASH -> text.split('/')
        CategoryDelimiter.SPACE -> text.trim().split(Regex("\\s+"))
    }
    return parts.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
}