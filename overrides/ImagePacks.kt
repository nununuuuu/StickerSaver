package com.local.threadssticker

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Path
import android.os.Build
import android.graphics.ImageDecoder
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

data class ImagePack(val id:String,val name:String,val coverId:String?=null,val order:Int=0)
data class PackImage(val id:String,val packId:String?,val mime:String,val original:String,val display:String,
    val order:Int=0,val edited:Boolean=false)

class ImagePackStore(private val context: Context) {
    private val dir = File(context.filesDir,"image_packs").apply { mkdirs() }
    private val database = File(dir,"index.json")
    private val _packs=MutableStateFlow<List<ImagePack>>(emptyList())
    val packs=_packs.asStateFlow()
    private val _images=MutableStateFlow<List<PackImage>>(emptyList())
    val images=_images.asStateFlow()
    init { load() }
    @Synchronized private fun load() {
        runCatching {
            val o=JSONObject(database.readText())
            val ps=o.optJSONArray("packs")?:JSONArray()
            val ims=o.optJSONArray("images")?:JSONArray()
            _packs.value=(0 until ps.length()).mapNotNull { ps.optJSONObject(it) }.map {
                ImagePack(it.getString("id"),it.getString("name"),it.optString("coverId").takeIf { v->v.isNotBlank() },it.optInt("order"))
            }
            _images.value=(0 until ims.length()).mapNotNull { ims.optJSONObject(it) }.map {
                PackImage(it.getString("id"),it.optString("packId").takeIf { v->v.isNotBlank() },
                    it.getString("mime"),it.getString("original"),it.getString("display"),it.optInt("order"),it.optBoolean("edited"))
            }.filter { File(it.display).isFile && File(it.original).isFile }
        }
    }
    @Synchronized private fun save() {
        val obj=JSONObject().put("packs",JSONArray().apply {
            _packs.value.forEach { p->put(JSONObject().put("id",p.id).put("name",p.name).put("coverId",p.coverId?:"").put("order",p.order)) }
        }).put("images",JSONArray().apply {
            _images.value.forEach { p->put(JSONObject().put("id",p.id).put("packId",p.packId?:"")
                .put("mime",p.mime).put("original",p.original).put("display",p.display).put("order",p.order).put("edited",p.edited)) }
        })
        val temp=File(dir,"index.tmp")
        temp.writeText(obj.toString())
        if (!temp.renameTo(database)) { temp.copyTo(database,overwrite=true);temp.delete() }
    }
    @Synchronized fun createPack(name:String):ImagePack {
        require(name.trim().isNotEmpty())
        val p=ImagePack(UUID.randomUUID().toString(),name.trim(),order=_packs.value.size)
        _packs.value=_packs.value+p;save();return p
    }
    @Synchronized fun renamePack(id:String,name:String) { if(name.isBlank())return
        _packs.value=_packs.value.map { if(it.id==id)it.copy(name=name.trim()) else it };save()
    }
    @Synchronized fun setCover(id:String,imageId:String) {
        if(_images.value.none { it.id==imageId && it.packId==id })return
        _packs.value=_packs.value.map { if(it.id==id)it.copy(coverId=imageId) else it };save()
    }
    @Synchronized fun importImages(packId:String?,uris:List<Uri>):Pair<Int,Int> {
        var added=0;var failed=0
        for(uri in uris) {
            try {
                val mime=context.contentResolver.getType(uri)?.lowercase().orEmpty()
                val name=uri.lastPathSegment?.substringBefore('?')?.lowercase().orEmpty()
                val gif=mime=="image/gif" || (mime.isBlank() && name.endsWith(".gif"))
                val png=mime=="image/png" || (mime.isBlank() && name.endsWith(".png"))
                val jpeg=mime=="image/jpeg" || (mime.isBlank() && (name.endsWith(".jpg")||name.endsWith(".jpeg")))
                require(gif||png||jpeg) { "不支援的格式" }
                val id=UUID.randomUUID().toString()
                val file=File(dir,"$id.${if(gif)"gif" else if(png)"png" else "jpg"}")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("無法讀取圖片")
                    require(file.length() in 1..(60L*1024L*1024L)) { "圖片過大或空白" }
                    // Validate actual content, not only the filename or MIME.
                    val header=ByteArray(12)
                    file.inputStream().use { it.read(header) }
                    val isGif=String(header,0,6,Charsets.US_ASCII).startsWith("GIF8")
                    val isPng=header[0]==0x89.toByte() && header[1]==0x50.toByte() && header[2]==0x4e.toByte() && header[3]==0x47.toByte()
                    val isJpeg=header[0]==0xff.toByte() && header[1]==0xd8.toByte()
                    require(if(gif)isGif else if(png)isPng else isJpeg) { "圖片格式不符" }
                    _images.value=_images.value+PackImage(id,packId,if(gif)"image/gif" else if(png)"image/png" else "image/jpeg",file.absolutePath,file.absolutePath,
                        _images.value.count { it.packId==packId })
                    added++
                } catch(ex:Exception) { file.delete();throw ex }
            } catch(ex:Exception) {failed++}
        }
        ensureCovers();save();return added to failed
    }
    @Synchronized fun move(id:String,packId:String?) {
        if(packId!=null && _packs.value.none {it.id==packId})return
        _images.value=_images.value.map {if(it.id==id)it.copy(packId=packId,order=_images.value.count { i->i.packId==packId })else it}
        ensureCovers();save()
    }
    @Synchronized fun reorder(id:String,step:Int) {
        val item=_images.value.firstOrNull {it.id==id}?:return
        val list=_images.value.filter {it.packId==item.packId}.sortedBy {it.order}.toMutableList()
        val idx=list.indexOfFirst {it.id==id};val next=idx+step
        if(next !in list.indices)return
        val other=list[next];list[next]=item;list[idx]=other
        val ranks=list.mapIndexed {index,img->img.id to index}.toMap()
        _images.value=_images.value.map {if(it.id in ranks)it.copy(order=ranks.getValue(it.id))else it};ensureCovers();save()
    }
    @Synchronized fun deleteImage(id:String) {
        val old=_images.value.firstOrNull {it.id==id}?:return
        _images.value=_images.value.filterNot {it.id==id};ensureCovers();save()
        File(old.original).delete()
        if(old.display!=old.original)File(old.display).delete()
    }
    @Synchronized fun deletePack(id:String,keepImages:Boolean) {
        val toDelete=_images.value.filter {it.packId==id}
        _packs.value=_packs.value.filterNot {it.id==id}
        _images.value=if(keepImages)_images.value.map {if(it.packId==id)it.copy(packId=null)else it}
                      else _images.value.filterNot {it.packId==id}
        save()
        if(!keepImages)toDelete.forEach {img->File(img.original).delete();if(img.display!=img.original)File(img.display).delete()}
    }
    @Synchronized fun saveEdited(id:String,bitmap:Bitmap,asNew:Boolean):PackImage {
        val old=_images.value.firstOrNull {it.id==id}?:error("找不到圖片")
        require(old.mime!="image/gif")
        val outputId=if(asNew)UUID.randomUUID().toString() else old.id
        val rendered=File(dir,"$outputId-edited-${System.currentTimeMillis()}.png")
        rendered.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        val item=if(asNew) {
            val original=File(dir,"$outputId-original.png")
            File(old.original).copyTo(original)
            PackImage(outputId,old.packId,"image/png",original.absolutePath,rendered.absolutePath,old.order+1,true)
        } else old.copy(display=rendered.absolutePath,mime="image/png",edited=true)
        _images.value=if(asNew)_images.value+item else _images.value.map {if(it.id==id)item else it}
        save()
        if(!asNew && old.display!=old.original)File(old.display).delete()
        return item
    }
    private fun ensureCovers() {
        _packs.value=_packs.value.map { p->
            val imgs=_images.value.filter {it.packId==p.id}.sortedBy {it.order}
            if(imgs.none {it.id==p.coverId})p.copy(coverId=imgs.firstOrNull()?.id)else p
        }
    }
}
