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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.max
import kotlin.math.min

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
    BackHandler(inPack || editing!=null) { if(editing!=null) editing=null else inPack=false }
    if(editing!=null) {
        val item=editing!!
        PackImageEditor(item,store,onBack={editing=null},onSaved={editing=null})
        return
    }
    val currentPack=packs.firstOrNull {it.id==selectedPack}
    Column(Modifier.fillMaxSize().background(PackPaper).padding(horizontal=14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
            if(inPack) IconButton(onClick={inPack=false}) {Icon(Icons.Outlined.ArrowBack,"返回圖集")}
            Text(if(!inPack)"自訂圖集" else currentPack?.name?:"未分類圖片",
                style=MaterialTheme.typography.titleLarge,color=PackInk,modifier=Modifier.weight(1f))
            if(!inPack) FilledTonalButton(onClick={newName="";creating=true}) {
                Icon(Icons.Outlined.Add,"新增圖集");Spacer(Modifier.width(4.dp));Text("新增")
            } else FilledTonalButton(onClick={pickNew=false;pickForPack=selectedPack;picker.launch("image/*")}) {
                Icon(Icons.Outlined.Add,"加入圖片");Text("加入圖片")
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
                    Column(Modifier.fillMaxWidth().background(PackCard,RoundedCornerShape(18.dp))
                        .clickable {selectedPack=p.id;inPack=true}.padding(8.dp)) {
                        if(cover!=null) AsyncImage(model=File(cover.display),contentDescription=p.name,
                            modifier=Modifier.fillMaxWidth().height(116.dp))
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
            if(shown.isEmpty()) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                Text("尚無圖片，點右上角加入 PNG、JPG、JPEG 或 GIF",color=Color.Gray)
            } else LazyVerticalGrid(columns=GridCells.Fixed(3),horizontalArrangement=Arrangement.spacedBy(8.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(shown,key={it.id}) { item->
                    Column(Modifier.background(PackCard,RoundedCornerShape(14.dp)).clickable {selectedImage=item}.padding(5.dp)) {
                        AsyncImage(model=File(item.display),contentDescription="檢視圖片",
                            modifier=Modifier.fillMaxWidth().height(92.dp))
                        Text(if(item.mime=="image/gif")"GIF ▷" else if(item.edited)"已編輯" else "圖片",
                            color=Color.Gray,style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    if(creating) AlertDialog(onDismissRequest={creating=false},title={Text("建立圖集")},
        text={OutlinedTextField(newName,{newName=it},label={Text("圖集名稱")},singleLine=true)},
        confirmButton={TextButton(enabled=newName.isNotBlank(),onClick={
            pickNew=true;picker.launch("image/*")
        }){Text("選擇圖片")}},
        dismissButton={TextButton(onClick={creating=false}){Text("取消")}})
    if(rename!=null) AlertDialog(onDismissRequest={rename=null},title={Text("重新命名")},
        text={OutlinedTextField(renameText,{renameText=it},label={Text("圖集名稱")})},
        confirmButton={TextButton(enabled=renameText.isNotBlank(),onClick={store.renamePack(rename!!.id,renameText);rename=null}){Text("儲存")}},
        dismissButton={TextButton(onClick={rename=null}){Text("取消")}})
    deleting?.let {pack->
        AlertDialog(onDismissRequest={deleting=null},title={Text("刪除「${pack.name}」？")},
            text={Text("此圖集有 ${images.count {it.packId==pack.id}} 張圖片。請選擇保留圖片或一併刪除；手機相簿原檔不受影響。")},
            confirmButton={Column {
                TextButton(onClick={store.deletePack(pack.id,true);deleting=null}) {Text("只刪圖集，圖片移至未分類")}
                TextButton(onClick={store.deletePack(pack.id,false);deleting=null}) {Text("刪除圖集與其中所有圖片",color=Color(0xFFB3261E))}
            }},dismissButton={TextButton(onClick={deleting=null}){Text("取消")}})
    }
    selectedImage?.let {item->
        AlertDialog(onDismissRequest={selectedImage=null},title={Text(if(item.mime=="image/gif")"GIF 動圖" else "圖片管理")},
            text={Column {
                AsyncImage(model=File(item.display),contentDescription="圖片預覽",modifier=Modifier.fillMaxWidth().height(190.dp))
                if(item.mime!="image/gif") TextButton(onClick={editing=item;selectedImage=null}) {Text("編輯圖片")}
                if(selectedPack!=null) TextButton(onClick={store.setCover(selectedPack!!,item.id);selectedImage=null}) {Text("設為圖集封面")}
                TextButton(onClick={store.reorder(item.id,-1);selectedImage=null}) {Text("向前移動")}
                TextButton(onClick={store.reorder(item.id,1);selectedImage=null}) {Text("向後移動")}
                TextButton(onClick={showMove=item;selectedImage=null}) {Text("移到其他圖集")}
                TextButton(onClick={store.deleteImage(item.id);selectedImage=null}) {Text("永久刪除此圖片",color=Color(0xFFB3261E))}
            }},confirmButton={TextButton(onClick={selectedImage=null}){Text("關閉")}})
    }
    showMove?.let {item->
        AlertDialog(onDismissRequest={showMove=null},title={Text("移動圖片")},
            text={LazyColumn {
                item {TextButton(onClick={store.move(item.id,null);showMove=null}){Text("未分類圖片")}}
                items(packs.size) {index->val pack=packs[index]
                    TextButton(onClick={store.move(item.id,pack.id);showMove=null}){Text(pack.name)}
                }
            }},confirmButton={TextButton(onClick={showMove=null}){Text("取消")}})
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
    var text:String=""; var textX=0.5f;var textY=0.5f
    var drawMode=false
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
            MotionEvent.ACTION_UP->{invalidate();return true}
        }
        return true
    }
    fun rotate(){val m=Matrix().apply{postRotate(90f)}
        bitmap=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,m,true);strokes.clear();invalidate()
    }
    fun cropSquare(){val size=min(bitmap.width,bitmap.height)
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
    var overlayScale by remember {mutableFloatStateOf(0.45f)}
    var overlayX by remember {mutableFloatStateOf(0.5f)}
    var overlayY by remember {mutableFloatStateOf(0.5f)}
    var textX by remember {mutableFloatStateOf(0.5f)}
    var textY by remember {mutableFloatStateOf(0.5f)}
    val overlayPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->
        if(uri!=null && context.contentResolver.getType(uri)!="image/gif"){
            editor.overlay=readImage(context,uri);editor.invalidate()
        } else if(uri!=null)Toast.makeText(context,"疊圖僅支援靜態圖片",Toast.LENGTH_SHORT).show()
    }
    Column(Modifier.fillMaxSize().background(PackPaper).padding(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack){Icon(Icons.Outlined.ArrowBack,"取消")}
            Text("梗圖編輯器",modifier=Modifier.weight(1f),color=PackInk)
            Button(onClick={editor.drawMode=false;savePrompt=true}){Text("完成")}
        }
        AndroidView(factory={editor},modifier=Modifier.fillMaxWidth().weight(1f))
        OutlinedTextField(caption,onValueChange={caption=it;editor.text=it;editor.invalidate()},
            label={Text("梗圖文字")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Text("文字水平位置",style=MaterialTheme.typography.labelSmall)
        Slider(value=textX,onValueChange={textX=it;editor.textX=it;editor.invalidate()})
        Text("文字垂直位置",style=MaterialTheme.typography.labelSmall)
        Slider(value=textY,onValueChange={textY=it;editor.textY=it;editor.invalidate()})
        if(editor.overlay!=null) {
            Text("疊圖大小／水平位置／垂直位置",style=MaterialTheme.typography.labelSmall)
            Slider(overlayScale,onValueChange={overlayScale=it;editor.overlayScale=it;editor.invalidate()},valueRange=0.1f..1f)
            Slider(overlayX,onValueChange={overlayX=it;editor.overlayX=it;editor.invalidate()})
            Slider(overlayY,onValueChange={overlayY=it;editor.overlayY=it;editor.invalidate()})
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={editor.cropSquare()}){Text("方形裁切")}
            TextButton(onClick={editor.rotate()}){Text("旋轉")}
            TextButton(onClick={drawing=!drawing;editor.drawMode=drawing}){Text(if(drawing)"停止塗鴉" else "塗鴉")}
            TextButton(onClick={editor.undoStroke()}){Text("復原")}
            TextButton(onClick={overlayPicker.launch("image/*")}){Text("疊圖")}
        }
    }
    if(savePrompt) AlertDialog(onDismissRequest={savePrompt=false},title={Text("儲存編輯結果")},
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
