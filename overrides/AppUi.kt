package com.local.threadssticker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
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
private enum class LibraryTab { RECENT, FREQUENT, ALL }

@Composable
fun StickerApp(activity: ComponentActivity, initialSharedText: String?) {
    val repo = (activity.application as StickerApplication).repository
    val stickers by repo.stickers.collectAsState()
    val sources by repo.sources.collectAsState()
    var tab by remember { mutableStateOf(Tab.HOME) }
    val context = LocalContext.current
    val checker = remember { UpdateChecker(context) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (checker.autoCheckEnabled) {
            runCatching { checker.check() }.onSuccess { update = it }.onFailure { error = it.message }
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent,
            secondary = Muted,
            background = Paper,
            surface = Paper,
            surfaceVariant = WarmCard,
            onPrimary = Color.White,
            onBackground = Ink,
            onSurface = Ink
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
                    Tab.HOME -> Home(activity, repo, stickers, initialSharedText)
                    Tab.LIBRARY -> Library(repo, stickers)
                    Tab.SOURCES -> Sources(repo, sources, stickers)
                    Tab.SETTINGS -> Settings(repo, checker, { update = it }, { error = it })
                }
            }
        }

        update?.let { info ->
            AlertDialog(
                onDismissRequest = { update = null },
                title = { Text("發現新版本 v" + info.version) },
                text = {
                    LazyColumn {
                        if (info.features.isNotEmpty()) {
                            item { Text("新功能", fontWeight = FontWeight.Bold) }
                            items(info.features.size) { i -> Text("• " + info.features[i]) }
                            item { Spacer(Modifier.height(10.dp)) }
                        }
                        if (info.fixes.isNotEmpty()) {
                            item { Text("修正功能", fontWeight = FontWeight.Bold) }
                            items(info.fixes.size) { i -> Text("• " + info.fixes[i]) }
                            item { Spacer(Modifier.height(10.dp)) }
                        }
                        item { Text("Threads 網頁結構及解析方式可能變動，未更新程式可能導致部分功能失效。", color = MaterialTheme.colorScheme.error) }
                    }
                },
                dismissButton = { TextButton(onClick = { update = null }) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl ?: info.releaseUrl)))
                        update = null
                    }) { Text("更新") }
                }
            )
        }

        error?.let { msg ->
            AlertDialog(
                onDismissRequest = { error = null },
                title = { Text("檢查更新失敗") },
                text = { Text(msg) },
                confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }
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
private fun Header(title: String, sub: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(sub, color = Muted)
    }
}

@Composable
private fun Home(activity: ComponentActivity, repo: StickerRepository, stickers: List<StickerItem>, shared: String?) {
    var input by remember { mutableStateOf(shared.orEmpty()) }
    var post by remember { mutableStateOf(true) }
    var comments by remember { mutableStateOf(false) }
    var allComments by remember { mutableStateOf(false) }
    var topText by remember { mutableStateOf("5") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<ParseProgress?>(null) }
    val cancel = remember { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Header("Sticker Saver", "貼上 Threads 連結並選擇解析範圍") }
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
                        placeholder = { Text("貼上 Threads 貼文連結") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Muted
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("解析來源", fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            post,
                            { if (!loading) post = it },
                            colors = CheckboxDefaults.colors(checkedColor = Accent)
                        )
                        Text("貼文")
                        Spacer(Modifier.width(12.dp))
                        Checkbox(
                            comments,
                            { if (!loading) comments = it },
                            colors = CheckboxDefaults.colors(checkedColor = Accent)
                        )
                        Text("留言")
                    }
                    if (comments) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                !allComments,
                                { if (!loading) allComments = false },
                                colors = RadioButtonDefaults.colors(selectedColor = Accent)
                            )
                            Text("前")
                            OutlinedTextField(
                                value = topText,
                                onValueChange = { v -> if (!loading && v.length <= 3 && v.all(Char::isDigit)) topText = v },
                                modifier = Modifier.width(84.dp),
                                enabled = !loading && !allComments,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Text("則有貼圖留言")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                allComments,
                                { if (!loading) allComments = true },
                                colors = RadioButtonDefaults.colors(selectedColor = Accent)
                            )
                            Text("全部有貼圖留言")
                        }
                        if (allComments) {
                            Text(
                                "目前會解析 Threads 初始載入資料中可取得的所有有貼圖留言。",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    if (!loading) {
                        Button(
                            onClick = {
                                if (!post && !comments) {
                                    message = "請至少選擇貼文或留言"
                                    return@Button
                                }
                                cancel.set(false)
                                loading = true
                                message = null
                                progress = null
                                scope.launch {
                                    runCatching {
                                        repo.parseAndSave(
                                            input,
                                            ParseOptions(
                                                parsePost = post,
                                                parseComments = comments,
                                                commentLoadMode = if (allComments) CommentLoadMode.ALL else CommentLoadMode.TOP,
                                                topCommentCount = topText.toIntOrNull()?.coerceIn(1, 200) ?: 5
                                            ),
                                            cancel,
                                            { progress = it }
                                        )
                                    }.onSuccess { result ->
                                        message = if (result.cancelled) {
                                            "已停止後續解析；已完成 " + result.completedTasks + "/" + result.plannedTasks + "，結果已保留"
                                        } else {
                                            "完成：貼文 " + result.postMedia.size + "、留言 " + result.commentMedia.size
                                        }
                                        val sourceId = AppStore.stableId(result.sourceUrl)
                                        if (repo.sources.value.firstOrNull { it.id == sourceId }?.snapshotPath == null) {
                                            SourceSnapshotter.capture(activity, sourceId, result.sourceUrl)?.let { repo.attachSnapshot(sourceId, it) }
                                        }
                                    }.onFailure { message = it.message ?: "解析失敗" }
                                    loading = false
                                }
                            },
                            enabled = input.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) { Text("開始解析") }
                    } else {
                        OutlinedButton(
                            onClick = {
                                cancel.set(true)
                                message = "已要求停止；目前項目完成後不再開始後續解析"
                            },
                            modifier = Modifier.fillMaxWidth()
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
                        Text(it.currentLabel + " · " + it.completedTasks + "/" + it.plannedTasks, style = MaterialTheme.typography.bodySmall)
                    }
                    message?.let { Text(it, Modifier.padding(top = 10.dp)) }
                }
            }
        }

        if (stickers.isNotEmpty()) item {
            Text("最近加入", Modifier.padding(horizontal = 20.dp, vertical = 18.dp), fontWeight = FontWeight.SemiBold)
            StickerGrid(repo, stickers.takeLast(8).reversed(), Modifier.heightIn(max = 360.dp))
        }
    }
}

@Composable
private fun Library(repo: StickerRepository, stickers: List<StickerItem>) {
    var selected by remember { mutableStateOf(LibraryTab.RECENT) }
    val shown = when (selected) {
        LibraryTab.RECENT -> stickers.takeLast(8).reversed()
        LibraryTab.FREQUENT -> stickers.sortedWith(compareByDescending<StickerItem> { it.useCount }.thenByDescending { it.lastUsedAt ?: 0L }).take(8)
        LibraryTab.ALL -> stickers.asReversed()
    }

    Column(Modifier.fillMaxSize()) {
        Header("貼圖庫", "最新、常用與全部貼圖")
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LibraryFilter("最新", selected == LibraryTab.RECENT) { selected = LibraryTab.RECENT }
            LibraryFilter("常用", selected == LibraryTab.FREQUENT) { selected = LibraryTab.FREQUENT }
            LibraryFilter("全部", selected == LibraryTab.ALL) { selected = LibraryTab.ALL }
        }
        Spacer(Modifier.height(12.dp))
        if (shown.isEmpty()) {
            Empty("還沒有貼圖")
        } else {
            StickerGrid(repo, shown, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RowScope.LibraryFilter(text: String, selected: Boolean, click: () -> Unit) {
    Surface(
        modifier = Modifier.weight(1f).clickable(onClick = click),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) WarmSelected else Color.Transparent
    ) {
        Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(text, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerGrid(repo: StickerRepository, list: List<StickerItem>, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var removeTarget by remember { mutableStateOf<StickerItem?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(list, key = { it.id }) { sticker ->
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Tile,
                modifier = Modifier
                    .aspectRatio(1f)
                    .combinedClickable(
                        onClick = {
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
                        },
                        onLongClick = { removeTarget = sticker }
                    )
            ) {
                AsyncImage(
                    sticker.localCachePath?.let(::File) ?: sticker.mediaUrl,
                    contentDescription = "貼圖",
                    modifier = Modifier.fillMaxSize().padding(6.dp)
                )
            }
        }
    }

    removeTarget?.let { sticker ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("移除這張貼圖？") },
            text = { Text("只會從 Sticker Saver 貼圖庫移除這張貼圖。") },
            confirmButton = {
                TextButton(onClick = {
                    repo.removeSticker(sticker.id)
                    removeTarget = null
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun Sources(repo: StickerRepository, sources: List<SourceRecord>, stickers: List<StickerItem>) {
    var editing by remember { mutableStateOf<SourceRecord?>(null) }

    Column(Modifier.fillMaxSize()) {
        Header("來源紀錄", "保留你貼入的 Threads 貼文與備註")
        if (sources.isEmpty()) {
            Empty("還沒有來源紀錄")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sources.size) { i ->
                    val source = sources[i]
                    val related = stickers.filter { it.id in source.stickerIds }
                    PostSourceCard(source, related) { editing = source }
                }
            }
        }
    }

    editing?.let { source ->
        var note by remember(source.id) { mutableStateOf(source.note) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("備註") },
            text = { OutlinedTextField(note, { note = it }, minLines = 3) },
            confirmButton = {
                TextButton(onClick = {
                    repo.updateNote(source.id, note)
                    editing = null
                }) { Text("儲存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun PostSourceCard(source: SourceRecord, related: List<StickerItem>, click: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = click),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = WarmCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = WarmSelected, modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("@", fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(source.author?.let { "@$it" } ?: "Threads 貼文", fontWeight = FontWeight.SemiBold)
                    Text(
                        source.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(related.size.toString() + " 張", style = MaterialTheme.typography.bodySmall, color = Muted)
            }

            source.postText?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }

            if (related.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    related.take(3).forEach { sticker ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Tile,
                            modifier = Modifier.size(76.dp)
                        ) {
                            AsyncImage(
                                sticker.localCachePath?.let(::File) ?: sticker.mediaUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().padding(4.dp)
                            )
                        }
                    }
                    if (related.size > 3) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = WarmSelected,
                            modifier = Modifier.size(76.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("+" + (related.size - 3), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            if (source.note.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(source.note, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Settings(repo: StickerRepository, checker: UpdateChecker, found: (UpdateInfo) -> Unit, failed: (String) -> Unit) {
    val context = LocalContext.current
    var about by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var size by remember { mutableLongStateOf(repo.cacheSizeBytes()) }

    Column(Modifier.fillMaxSize()) {
        Header("設定", "鍵盤、快取與版本")
        SettingRow("鍵盤", "啟用 Sticker Saver 鍵盤", Icons.Outlined.Keyboard) {
            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Spacer(Modifier.height(10.dp))
        SettingRow("貼圖快取", formatBytes(size), Icons.Outlined.Storage) { clear = true }
        Spacer(Modifier.height(10.dp))
        SettingRow("關於", "版本 " + checker.currentVersion(), Icons.Outlined.Info) { about = true }
    }

    if (clear) {
        AlertDialog(
            onDismissRequest = { clear = false },
            title = { Text("清除貼圖快取？") },
            text = { Text("來源連結與備註會保留。") },
            confirmButton = {
                TextButton(onClick = {
                    repo.clearStickerCache()
                    size = repo.cacheSizeBytes()
                    clear = false
                }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } }
        )
    }

    if (about) About(checker, { about = false }, found, failed)
}

@Composable
private fun About(checker: UpdateChecker, dismiss: () -> Unit, found: (UpdateInfo) -> Unit, failed: (String) -> Unit) {
    var auto by remember { mutableStateOf(checker.autoCheckEnabled) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("關於") },
        text = {
            Column {
                Text("Sticker Saver", fontWeight = FontWeight.SemiBold)
                Text("版本 " + checker.currentVersion())
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自動更新")
                        Text("啟動 App 時自動檢查更新", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        auto,
                        {
                            auto = it
                            checker.autoCheckEnabled = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Accent
                        )
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        if (!checking) {
                            checking = true
                            status = null
                            scope.launch {
                                runCatching { checker.check() }
                                    .onSuccess {
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.SystemUpdateAlt, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (checking) "檢查中…" else "檢查更新")
                }
                status?.let { Text(it, Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("完成") } }
    )
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

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1073741824L -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> bytes.toString() + " B"
}
