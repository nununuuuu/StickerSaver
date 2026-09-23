package com.local.threadssticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch

private val PackPaper=Color(0xFFF7F5F2)
private val PackInk=Color(0xFF242220)
private val PackCard=Color(0xFFF0ECE6)

@Composable
fun ImagePacksScreen(store:ImagePackStore) {
    val context=LocalContext.current
    val packs by store.packs.collectAsState()
    val images by store.images.collectAsState()
    var selectedPack by remember { mutableStateOf<String?>(null) }
    var inPack by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf<ImagePack?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ImagePack?>(null) }
    var selectedImage by remember { mutableStateOf<PackImage?>(null) }
    var editing by remember { mutableStateOf<PackImage?>(null) }
    var showMove by remember { mutableStateOf<PackImage?>(null) }
    var pickForPack by remember { mutableStateOf<String?>(null) }
    var pickNew by remember { mutableStateOf(false) }
    var bulkEditing by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bulkMove by remember { mutableStateOf(false) }
    var bulkDeleteConfirm by remember { mutableStateOf(false) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragTouch by remember { mutableStateOf(Offset.Zero) }
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }
    var dropTargetId by remember { mutableStateOf<String?>(null) }
    var dragBeyondLast by remember { mutableStateOf(false) }
    var dragScrollInFlight by remember { mutableStateOf(false) }
    val packGridState=rememberLazyGridState()
    val dragScrollScope=rememberCoroutineScope()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if(uris.isNotEmpty()) {
            val packId=if(pickNew) {
                val name=newName.trim()
                if(name.isBlank()) null else store.createPack(name).id
            } else pickForPack
            if(!pickNew || packId!=null) {
                val (added,failed)=store.importImages(packId,uris)
                Toast.makeText(context,"已加入 $added 張${if(failed>0)"，$failed 張失敗" else ""}",Toast.LENGTH_LONG).show()
                if(pickNew && packId!=null) {selectedPack=packId;inPack=true}
            }
        }
        pickNew=false;pickForPack=null;creating=false
    }
    BackHandler(inPack || editing!=null || bulkEditing) {
        when {
            editing!=null -> editing=null
            bulkEditing -> {bulkEditing=false;selectedIds=emptySet()}
            else -> inPack=false
        }
    }
    if(editing!=null) {
        val item=editing!!
        PackImageEditor(item,store,onBack={editing=null},onSaved={editing=null})
        return
    }
    val currentPack=packs.firstOrNull {it.id==selectedPack}
    Column(Modifier.fillMaxSize().background(PackPaper).padding(horizontal=14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
            if(inPack) IconButton(onClick={
                bulkEditing=false;selectedIds=emptySet();inPack=false
            }) {Icon(Icons.Outlined.ArrowBack,"返回圖集")}
            Text(if(!inPack)"自訂圖集" else currentPack?.name?:"未分類圖片",
                style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=PackInk,
                modifier=Modifier.weight(1f),maxLines=1)
            if(!inPack) FilledTonalButton(onClick={newName="";creating=true}) {
                Icon(Icons.Outlined.Add,"新增圖集");Spacer(Modifier.width(4.dp));Text("新增")
            } else {
                if(!bulkEditing) IconButton(onClick={bulkEditing=true;selectedIds=emptySet()}) {
                    Icon(Icons.Outlined.Checklist,"批量編輯")
                }
                FilledTonalButton(onClick={pickNew=false;pickForPack=selectedPack;picker.launch("image/*")}) {
                    Icon(Icons.Outlined.Add,"加入")
                    Text("加入")
                }
            }
        }
        if(!inPack) {
            val unclassified=images.filter {it.packId==null}
            Row(Modifier.fillMaxWidth().clickable {selectedPack=null;inPack=true}
                .background(PackCard,RoundedCornerShape(16.dp)).padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.Collections,"未分類",modifier=Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Column {Text("未分類圖片",color=PackInk);Text("${unclassified.size} 張",color=Color.Gray)}
            }
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(columns=GridCells.Fixed(2),horizontalArrangement=Arrangement.spacedBy(10.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                items(packs,key={it.id}) { p->
                    val cover=images.firstOrNull {it.id==p.coverId}?:images.firstOrNull {it.packId==p.id}
                    Column(Modifier.fillMaxWidth().background(PackPaper,RoundedCornerShape(18.dp))
                        .clickable {selectedPack=p.id;inPack=true}.padding(8.dp)) {
                        if(cover!=null) AsyncImage(model=File(cover.display),contentDescription=p.name,
                            modifier=Modifier.fillMaxWidth().height(130.dp),contentScale=ContentScale.Fit)
                        else Box(Modifier.fillMaxWidth().height(116.dp),contentAlignment=Alignment.Center) {
                            Icon(Icons.Outlined.Collections,"空圖集",modifier=Modifier.size(42.dp))
                        }
                        Text(p.name,maxLines=1,color=PackInk)
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("${images.count {it.packId==p.id}} 張",color=Color.Gray,modifier=Modifier.weight(1f))
                            IconButton(onClick={rename=p;renameText=p.name},modifier=Modifier.size(34.dp)) {Icon(Icons.Outlined.Edit,"重新命名")}
                            IconButton(onClick={deleting=p},modifier=Modifier.size(34.dp)) {Icon(Icons.Outlined.Delete,"刪除圖集")}
                        }
                    }
                }
            }
        } else {
            val shown=images.filter {it.packId==selectedPack}.sortedBy {it.order}
            if(bulkEditing) {
                Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("已選 ${selectedIds.size} 張",color=PackInk,modifier=Modifier.weight(1f))
                    TextButton(onClick={selectedIds=if(selectedIds.size==shown.size) emptySet() else shown.map {it.id}.toSet()}) {
                        Text(if(selectedIds.size==shown.size)"取消全選" else "全選")
                    }
                    TextButton(onClick={bulkEditing=false;selectedIds=emptySet()}){Text("完成")}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(enabled=selectedIds.isNotEmpty(),onClick={bulkMove=true}) {
                        Icon(Icons.Outlined.DriveFileMove,"移動");Text("移動")
                    }
                    TextButton(enabled=selectedIds.isNotEmpty(),onClick={bulkDeleteConfirm=true}) {
                        Icon(Icons.Outlined.Delete,"刪除",tint=Color(0xFFB3261E))
                        Text("刪除",color=Color(0xFFB3261E))
                    }
                }
            }
            if(shown.isEmpty()) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                Text("尚無圖片，點右上角加入 PNG、JPG、JPEG 或 GIF",color=Color.Gray)
            } else LazyVerticalGrid(
                state=packGridState,
                columns=GridCells.Fixed(3),
                horizontalArrangement=Arrangement.spacedBy(8.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                items(shown,key={it.id}) { item->
                    val isSelected=item.id in selectedIds
                    val isDragging=!bulkEditing && draggingId==item.id
                    val isDropTarget=!bulkEditing && dropTargetId==item.id && draggingId!=null
                    // Keep all grid tiles stationary while the dragged tile follows the finger.
                    val draggedModifier=if(bulkEditing) Modifier else Modifier.pointerInput(item.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart={ touch->
                                val visible=packGridState.layoutInfo.visibleItemsInfo.firstOrNull {it.key==item.id}
                                draggingId=item.id
                                dragTouch=touch
                                dragOffset=Offset.Zero
                                dragOrigin=if(visible==null) touch else
                                    Offset(visible.offset.x.toFloat(),visible.offset.y.toFloat())+touch
                                dropTargetId=item.id
                                dragBeyondLast=false
                            },
                            onDrag={change,delta->
                                change.consume()
                                dragOffset+=delta
                                val pointer=dragOrigin+dragOffset
                                val target=packGridState.layoutInfo.visibleItemsInfo
                                    .firstOrNull {visible->
                                        visible.key!=item.id &&
                                        pointer.x>=visible.offset.x &&
                                        pointer.x<visible.offset.x+visible.size.width &&
                                        pointer.y>=visible.offset.y &&
                                        pointer.y<visible.offset.y+visible.size.height
                                    }
                                if(target!=null) {
                                    dropTargetId=target.key as? String
                                    dragBeyondLast=false
                                } else {
                                    // Empty space before the first / after the last tile
                                    // must still be a valid drop destination.
                                    val visible=packGridState.layoutInfo.visibleItemsInfo
                                        .filter {it.key is String}.sortedBy {it.index}
                                    val first=visible.firstOrNull()
                                    val last=visible.lastOrNull()
                                    if(first!=null && pointer.y<first.offset.y) {
                                        dropTargetId=shown.firstOrNull()?.id
                                        dragBeyondLast=false
                                    } else if(last!=null &&
                                        pointer.y>=last.offset.y+last.size.height) {
                                        dropTargetId=shown.lastOrNull()?.id
                                        dragBeyondLast=true
                                    }
                                }
                                val layout=packGridState.layoutInfo
                                val edge=70.dp.toPx()
                                val scroll=when {
                                    pointer.y<layout.viewportStartOffset+edge -> -18.dp.toPx()
                                    pointer.y>layout.viewportEndOffset-edge -> 18.dp.toPx()
                                    else -> 0f
                                }
                                if(scroll!=0f && !dragScrollInFlight) {
                                    dragScrollInFlight=true
                                    dragScrollScope.launch {
                                        try {packGridState.scrollBy(scroll)}
                                        finally {dragScrollInFlight=false}
                                    }
                                }
                            },
                            onDragEnd={
                                val target=dropTargetId
                                draggingId=null
                                dropTargetId=null
                                dragOffset=Offset.Zero
                                if(target!=null && target!=item.id) {
                                    if(dragBeyondLast) store.reorderToEnd(item.id)
                                    else store.reorderTo(item.id,target)
                                }
                            },
                            onDragCancel={
                                draggingId=null
                                dropTargetId=null
                                dragOffset=Offset.Zero
                            }
                        )
                    }
                    val currentTile=packGridState.layoutInfo.visibleItemsInfo.firstOrNull {it.key==item.id}
                    val finger=dragOrigin+dragOffset
                    val lift=if(isDragging && currentTile!=null)
                        Offset(
                            finger.x-currentTile.offset.x-dragTouch.x,
                            finger.y-currentTile.offset.y-dragTouch.y
                        ) else Offset.Zero
                    Column(
                        Modifier
                            .zIndex(if(isDragging) 2f else 0f)
                            .graphicsLayer {
                                if(isDragging) {
                                    translationX=lift.x
                                    translationY=lift.y
                                    scaleX=1.035f
                                    scaleY=1.035f
                                    shadowElevation=4.dp.toPx()
                                    shape=RoundedCornerShape(14.dp)
                                }
                            }
                            .background(
                                if(isSelected || isDragging || isDropTarget) PackCard else PackPaper,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                if(bulkEditing) selectedIds=if(isSelected) selectedIds-item.id else selectedIds+item.id
                                else selectedImage=item
                            }
                            .then(draggedModifier)
                            .padding(5.dp)
                    ) {
                        Box {
                            AsyncImage(model=File(item.display),contentDescription="檢視圖片",
                                modifier=Modifier.fillMaxWidth().height(114.dp),contentScale=ContentScale.Fit)
                            if(bulkEditing) Checkbox(
                                checked=isSelected,
                                onCheckedChange={checked->
                                    selectedIds=if(checked) selectedIds+item.id else selectedIds-item.id
                                },
                                modifier=Modifier.align(Alignment.TopEnd)
                            )
                        }
                        if(!bulkEditing && (item.mime=="image/gif" || item.edited)) Text(
                            if(item.mime=="image/gif")"GIF ▷" else "已編輯",
                            color=Color.Gray,style=MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

        }
    }
    if(creating) PackDialog(onDismissRequest={creating=false},title={Text("建立圖集")},
        text={PackNameField(newName,{newName=it},"圖集名稱")},
        confirmButton={TextButton(enabled=newName.isNotBlank(),onClick={
            pickNew=true;picker.launch("image/*")
        }){Text("選擇圖片")}},
        dismissButton={TextButton(onClick={creating=false}){Text("取消")}})
    if(rename!=null) PackDialog(onDismissRequest={rename=null},title={Text("重新命名")},
        text={PackNameField(renameText,{renameText=it},"圖集名稱")},
        confirmButton={TextButton(enabled=renameText.isNotBlank(),onClick={store.renamePack(rename!!.id,renameText);rename=null}){Text("儲存")}},
        dismissButton={TextButton(onClick={rename=null}){Text("取消")}})
    deleting?.let {pack->
        val count=images.count {it.packId==pack.id}
        PackDialog(onDismissRequest={deleting=null},title={Text("刪除「${pack.name}」？")},
            text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("此圖集有 ${count} 張圖片；手機相簿原檔不受影響。",color=Color.Gray)
                PackOption("只刪除圖集","保留 ${count} 張圖片，移至未分類。",onClick={
                    store.deletePack(pack.id,true);deleting=null
                })
                PackOption("刪除圖集與圖片","一併刪除圖集及其中 ${count} 張圖片在 Sticker Saver 內的紀錄。",danger=true,onClick={
                    store.deletePack(pack.id,false);deleting=null
                })
            }},
            confirmButton={TextButton(onClick={deleting=null}){Text("取消",color=PackInk)}})
    }
    selectedImage?.let {item->
        PackDialog(onDismissRequest={selectedImage=null},
            text={Column {
                AsyncImage(model=File(item.display),contentDescription="圖片預覽",modifier=Modifier.fillMaxWidth().height(190.dp))
                if(item.mime!="image/gif") PackAction("編輯圖片",Icons.Outlined.Edit) {editing=item;selectedImage=null}
                if(selectedPack!=null) PackAction("設為圖集封面",Icons.Outlined.Image) {
                    store.setCover(selectedPack!!,item.id);selectedImage=null
                }
                PackAction("移到其他圖集",Icons.Outlined.DriveFileMove) {showMove=item;selectedImage=null}
                PackAction("永久刪除此圖片",Icons.Outlined.Delete,danger=true) {
                    store.deleteImage(item.id);selectedImage=null
                }
            }},confirmButton={TextButton(onClick={selectedImage=null}){Text("關閉")}})
    }
    showMove?.let {item->
        PackDialog(onDismissRequest={showMove=null},title={Text("移動圖片")},
            text={LazyColumn {
                item {TextButton(onClick={store.move(item.id,null);showMove=null}){Text("未分類圖片")}}
                items(packs.size) {index->val pack=packs[index]
                    TextButton(onClick={store.move(item.id,pack.id);showMove=null}){Text(pack.name)}
                }
            }},confirmButton={TextButton(onClick={showMove=null}){Text("取消")}})
    }
    if(bulkMove) PackDialog(
        onDismissRequest={bulkMove=false},
        title={Text("移動 ${selectedIds.size} 張圖片")},
        text={LazyColumn {
            item {
                TextButton(enabled=selectedPack!=null,onClick={
                    store.moveMany(selectedIds,null)
                    selectedIds=emptySet();bulkMove=false;bulkEditing=false
                }){Text("未分類圖片")}
            }
            items(packs.size) {index->
                val target=packs[index]
                TextButton(enabled=target.id!=selectedPack,onClick={
                    store.moveMany(selectedIds,target.id)
                    selectedIds=emptySet();bulkMove=false;bulkEditing=false
                }){Text(target.name)}
            }
        }},
        confirmButton={TextButton(onClick={bulkMove=false}){Text("取消")}}
    )
    if(bulkDeleteConfirm) PackDialog(
        onDismissRequest={bulkDeleteConfirm=false},
        title={Text("刪除 ${selectedIds.size} 張圖片？")},
        text={Text("只會刪除 Sticker Saver 內選取的圖片，不會刪除手機相簿原檔。")},
        confirmButton={TextButton(onClick={
            store.deleteMany(selectedIds)
            selectedIds=emptySet();bulkEditing=false;bulkDeleteConfirm=false
        }){Text("刪除",color=Color(0xFFB3261E))}},
        dismissButton={TextButton(onClick={bulkDeleteConfirm=false}){Text("取消")}}
    )

}

@Composable
private fun PackDialog(
    onDismissRequest:()->Unit,
    title:(@Composable ()->Unit)?=null,
    text:(@Composable ()->Unit)?=null,
    confirmButton:@Composable ()->Unit,
    dismissButton:(@Composable ()->Unit)?=null,
) {
    AlertDialog(
        onDismissRequest=onDismissRequest,
        title=title,
        text=text,
        confirmButton=confirmButton,
        dismissButton=dismissButton,
        containerColor=PackPaper,
        titleContentColor=PackInk,
        textContentColor=PackInk,
        tonalElevation=0.dp,
        shape=RoundedCornerShape(26.dp)
    )
}

@Composable
private fun PackNameField(value:String,onValueChange:(String)->Unit,hint:String) {
    OutlinedTextField(
        value=value,
        onValueChange=onValueChange,
        modifier=Modifier.fillMaxWidth(),
        singleLine=true,
        placeholder={Text(hint,color=Color(0xFF9E9993))},
        colors=OutlinedTextFieldDefaults.colors(
            focusedTextColor=PackInk,unfocusedTextColor=PackInk,
            focusedBorderColor=PackInk,unfocusedBorderColor=Color(0xFF9E9993),
            focusedContainerColor=PackPaper,unfocusedContainerColor=PackPaper,
            cursorColor=PackInk
        ),
        shape=RoundedCornerShape(14.dp)
    )
}

@Composable
private fun PackAction(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,
    danger:Boolean=false,onClick:()->Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min=48.dp)
            .clickable(onClick=onClick).padding(horizontal=8.dp,vertical=10.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Icon(icon,contentDescription=null,tint=if(danger)Color(0xFFAF332B) else PackInk,
            modifier=Modifier.size(20.dp))
        Text(label,color=if(danger)Color(0xFFAF332B) else PackInk,
            style=MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PackOption(title:String,description:String,danger:Boolean=false,onClick:()->Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp,if(danger)Color(0xFFD6ABA6) else PackCard,RoundedCornerShape(16.dp))
            .clickable(onClick=onClick).padding(14.dp),
        verticalArrangement=Arrangement.spacedBy(4.dp)
    ) {
        Text(title,color=if(danger)Color(0xFFAF332B) else PackInk,fontWeight=FontWeight.SemiBold)
        Text(description,color=Color.Gray,style=MaterialTheme.typography.bodySmall)
    }
}

private fun readImage(context:Context,uri:Uri):Bitmap?=runCatching {
    val bytes=context.contentResolver.openInputStream(uri)?.use {it.readBytes()}?:return@runCatching null
    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    var sample=1
    while(max(bounds.outWidth,bounds.outHeight)/sample>1600)sample*=2
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample})
        ?.copy(Bitmap.Config.ARGB_8888,true)
}.getOrNull()

private class PackEditorView(context:Context,initial:Bitmap):View(context) {
    var bitmap:Bitmap=initial;private set
    private val history=mutableListOf<Bitmap>()
    val canUndo:Boolean get()=history.isNotEmpty()
    fun saveStep(){history.add(bitmap.copy(Bitmap.Config.ARGB_8888,true));if(history.size>16)history.removeAt(0)}
    fun undoStep(){if(history.isNotEmpty()){bitmap=history.removeAt(history.lastIndex);strokes.clear();invalidate()}}
    fun restoreBitmap(value:Bitmap){bitmap=value.copy(Bitmap.Config.ARGB_8888,true);strokes.clear();invalidate()}
    var text:String=""; var textX=0.5f;var textY=0.5f
    var drawMode=false
    var onStrokeFinished:(()->Unit)?=null
    var overlay:Bitmap?=null
    var overlayX=0.5f;var overlayY=0.5f;var overlayScale=0.45f
    private val strokes=mutableListOf<MutableList<Pair<Float,Float>>>()
    private val imagePaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(AndroidColor.rgb(240,236,230))
        val w=width.toFloat();val h=height.toFloat()
        val scale=min(w/bitmap.width,h/bitmap.height)
        val dw=bitmap.width*scale;val dh=bitmap.height*scale
        val left=(w-dw)/2;val top=(h-dh)/2
        canvas.drawBitmap(bitmap,null,RectF(left,top,left+dw,top+dh),imagePaint)
        overlay?.let {ov->
            val ow=dw*overlayScale;val oh=ow*ov.height/ov.width
            val x=left+dw*overlayX-ow/2;val y=top+dh*overlayY-oh/2
            canvas.drawBitmap(ov,null,RectF(x,y,x+ow,y+oh),imagePaint)
        }
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=AndroidColor.RED;strokeWidth=5f;style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
        for(points in strokes) {val path=Path()
            points.forEachIndexed {index,p->val x=left+p.first*dw;val y=top+p.second*dh
                if(index==0)path.moveTo(x,y)else path.lineTo(x,y)
            }
            canvas.drawPath(path,paint)
        }
        if(text.isNotBlank()){
            val tp=Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=34f*scale.coerceAtMost(2f);color=AndroidColor.WHITE;typeface=android.graphics.Typeface.DEFAULT_BOLD
                setShadowLayer(3f,2f,2f,AndroidColor.BLACK);textAlign=Paint.Align.CENTER}
            canvas.drawText(text,left+dw*textX,top+dh*textY,tp)
        }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(!drawMode)return false
        val scale=min(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
        val dw=bitmap.width*scale;val dh=bitmap.height*scale
        val x=((event.x-(width-dw)/2)/dw).coerceIn(0f,1f)
        val y=((event.y-(height-dh)/2)/dh).coerceIn(0f,1f)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{strokes.add(mutableListOf(x to y));invalidate();return true}
            MotionEvent.ACTION_MOVE->{strokes.lastOrNull()?.add(x to y);invalidate();return true}
            MotionEvent.ACTION_UP->{invalidate();onStrokeFinished?.invoke();return true}
        }
        return true
    }
    fun rotate(){saveStep();val m=Matrix().apply{postRotate(90f)}
        bitmap=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,m,true);strokes.clear();invalidate()
    }
    fun cropSquare(){saveStep();val size=min(bitmap.width,bitmap.height)
        bitmap=Bitmap.createBitmap(bitmap,(bitmap.width-size)/2,(bitmap.height-size)/2,size,size)
        strokes.clear();invalidate()
    }
    fun undoStroke(){if(strokes.isNotEmpty())strokes.removeAt(strokes.lastIndex);invalidate()}
    fun render():Bitmap {
        val result=Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888)
        val c=Canvas(result)
        c.drawBitmap(bitmap,0f,0f,imagePaint)
        overlay?.let {ov->
            val ow=bitmap.width*overlayScale;val oh=ow*ov.height/ov.width
            val x=bitmap.width*overlayX-ow/2;val y=bitmap.height*overlayY-oh/2
            c.drawBitmap(ov,null,RectF(x,y,x+ow,y+oh),imagePaint)
        }
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=AndroidColor.RED;strokeWidth=max(2f,bitmap.width/150f);style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
        strokes.forEach {points->val path=Path();points.forEachIndexed {i,p->
            if(i==0)path.moveTo(p.first*bitmap.width,p.second*bitmap.height)
            else path.lineTo(p.first*bitmap.width,p.second*bitmap.height)
        };c.drawPath(path,paint)}
        if(text.isNotBlank()) {
            val tp=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=AndroidColor.WHITE;textAlign=Paint.Align.CENTER;textSize=max(28f,bitmap.width/15f)
                typeface=android.graphics.Typeface.DEFAULT_BOLD;setShadowLayer(4f,2f,2f,AndroidColor.BLACK)
            }
            c.drawText(text,bitmap.width*textX,bitmap.height*textY,tp)
        }
        return result
    }
}

@Composable
private fun PackImageEditor(item:PackImage,store:ImagePackStore,onBack:()->Unit,onSaved:()->Unit) {
    val context=LocalContext.current
    val original=remember(item.id) { BitmapFactory.decodeFile(item.original)?.let { bitmap->
        var result=bitmap
        while(max(result.width,result.height)>1600)result=Bitmap.createScaledBitmap(result,result.width/2,result.height/2,true)
        result.copy(Bitmap.Config.ARGB_8888,true)
    } }
    if(original==null) {LaunchedEffect(Unit){onBack()};return}
    val editor=remember(item.id){PackEditorView(context,original)}
    var caption by remember(item.id) {mutableStateOf("")}
    var drawing by remember(item.id) {mutableStateOf(false)}
    var savePrompt by remember {mutableStateOf(false)}
    var cropPrompt by remember {mutableStateOf(false)}
    var discardPrompt by remember {mutableStateOf(false)}
    var dirty by remember {mutableStateOf(false)}
    var undoRevision by remember {mutableIntStateOf(0)}
    var strokeRevision by remember {mutableIntStateOf(0)}
    var overlayScale by remember {mutableFloatStateOf(0.45f)}
    var overlayX by remember {mutableFloatStateOf(0.5f)}
    var overlayY by remember {mutableFloatStateOf(0.5f)}
    var textX by remember {mutableFloatStateOf(0.5f)}
    var textY by remember {mutableFloatStateOf(0.5f)}
    val overlayPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->
        if(uri!=null && context.contentResolver.getType(uri)!="image/gif"){
            editor.overlay=readImage(context,uri);dirty=true;editor.invalidate()
        } else if(uri!=null)Toast.makeText(context,"疊圖僅支援靜態圖片",Toast.LENGTH_SHORT).show()
    }
    editor.onStrokeFinished={dirty=true;strokeRevision++}
    val requestBack={
        editor.drawMode=false
        drawing=false
        if(cropPrompt) cropPrompt=false
        else if(dirty) discardPrompt=true
        else onBack()
    }
    BackHandler {requestBack()}
    Column(Modifier.fillMaxSize().background(PackPaper).padding(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=requestBack){Text("取消",color=PackInk)}
            Text("梗圖編輯器",modifier=Modifier.weight(1f),color=PackInk)
            TextButton(enabled=undoRevision>0 || strokeRevision>0,onClick={
                if(strokeRevision>0){editor.undoStroke();strokeRevision--}
                else {editor.undoStep();undoRevision--}
                dirty=true
            }) {Icon(Icons.Outlined.Undo,"撤銷")}
            Button(onClick={editor.drawMode=false;drawing=false;savePrompt=true}){Text("保存")}
        }
        AndroidView(factory={editor},modifier=Modifier.fillMaxWidth().weight(1f))
        OutlinedTextField(caption,onValueChange={caption=it;editor.text=it;dirty=true;editor.invalidate()},
            label={Text("梗圖文字")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Text("文字水平位置",style=MaterialTheme.typography.labelSmall)
        Slider(value=textX,onValueChange={textX=it;editor.textX=it;dirty=true;editor.invalidate()})
        Text("文字垂直位置",style=MaterialTheme.typography.labelSmall)
        Slider(value=textY,onValueChange={textY=it;editor.textY=it;dirty=true;editor.invalidate()})
        if(editor.overlay!=null) {
            Text("疊圖大小／水平位置／垂直位置",style=MaterialTheme.typography.labelSmall)
            Slider(overlayScale,onValueChange={overlayScale=it;editor.overlayScale=it;dirty=true;editor.invalidate()},valueRange=0.1f..1f)
            Slider(overlayX,onValueChange={overlayX=it;editor.overlayX=it;dirty=true;editor.invalidate()})
            Slider(overlayY,onValueChange={overlayY=it;editor.overlayY=it;dirty=true;editor.invalidate()})
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={cropPrompt=true}){Text("方形裁切")}
            TextButton(onClick={editor.rotate();dirty=true;undoRevision++}){Text("旋轉")}
            TextButton(onClick={drawing=!drawing;editor.drawMode=drawing}){Text(if(drawing)"停止塗鴉" else "塗鴉")}
            TextButton(enabled=strokeRevision>0,onClick={editor.undoStroke();strokeRevision--;dirty=true}){Text("復原塗鴉")}
            TextButton(onClick={overlayPicker.launch("image/*")}){Text("疊圖")}
        }
    }
    if(cropPrompt) PackDialog(onDismissRequest={cropPrompt=false},
        title={Text("套用方形裁切？")},text={Text("可取消裁切並返回編輯畫面。")},
        confirmButton={Button(onClick={editor.cropSquare();undoRevision++;dirty=true;cropPrompt=false}){Text("套用裁切")}},
        dismissButton={TextButton(onClick={cropPrompt=false}){Text("取消裁切")}})
    if(discardPrompt) PackDialog(onDismissRequest={discardPrompt=false},
        title={Text("放棄未保存的編輯？")},
        confirmButton={TextButton(onClick={discardPrompt=false;onBack()}){Text("放棄變更",color=Color(0xFFAF332B))}},
        dismissButton={TextButton(onClick={discardPrompt=false}){Text("繼續編輯")}})
    if(savePrompt) PackDialog(onDismissRequest={savePrompt=false},title={Text("儲存編輯結果")},
        text={Text("更新目前圖片，或另存為同圖集內的新圖片？手機相簿原檔不會修改。")},
        confirmButton={Column {
            TextButton(onClick={runCatching{store.saveEdited(item.id,editor.render(),false)}.onSuccess{onSaved()}.onFailure{
                Toast.makeText(context,"儲存失敗：${it.message}",Toast.LENGTH_LONG).show()
            }}) {Text("更新目前圖片")}
            TextButton(onClick={runCatching{store.saveEdited(item.id,editor.render(),true)}.onSuccess{onSaved()}.onFailure{
                Toast.makeText(context,"儲存失敗：${it.message}",Toast.LENGTH_LONG).show()
            }}) {Text("另存新圖片")}
        }},dismissButton={TextButton(onClick={savePrompt=false}){Text("繼續編輯")}})
}
