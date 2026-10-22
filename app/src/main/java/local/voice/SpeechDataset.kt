package local.voice

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** A user-invoked utterance only. Never retains idle audio or stop-command monitoring. */
internal class SpeechSampleBuffer {
    private val raw = ByteArrayOutputStream()
    private val clean = ByteArrayOutputStream()
    var silenced = false; private set
    fun append(input: ShortArray, decoded: ShortArray, muted: Boolean) {
        silenced = silenced || muted
        if (raw.size() + input.size * 2 > 960000) return // 30 seconds, bounded even on model failure.
        fun bytes(pcm: ShortArray) = ByteArray(pcm.size * 2).also { out -> pcm.forEachIndexed { i,v -> out[i*2]=v.toByte(); out[i*2+1]=(v.toInt() shr 8).toByte() } }
        raw.write(bytes(input)); clean.write(bytes(decoded))
    }
    fun pcm(cleaned: Boolean) = if (cleaned) clean.toByteArray() else raw.toByteArray()
}

internal class SpeechDataset(private val context: Context) {
    private val root = File(context.filesDir,"speech-dataset").apply { mkdirs() }
    private val candidates = File(context.cacheDir,"speech-candidates").apply { mkdirs() }
    fun mode() = context.getSharedPreferences("settings",0).getString("speech_dataset_mode","off") ?: "off"
    fun setMode(value: String) { require(value in setOf("off","corrected","all"));context.getSharedPreferences("settings",0).edit().putString("speech_dataset_mode",value).commit() }
    fun session() = UUID.randomUUID().toString()
    private fun file(dir:File,id:String,suffix:String):File {require(UUID.fromString(id).toString()==id);return File(dir,"$id$suffix")}
    private fun write(file:File, bytes:ByteArray) {
        val atomic=AtomicFile(file);val stream=atomic.startWrite()
        try {stream.write(bytes);atomic.finishWrite(stream)} catch(e:Exception){atomic.failWrite(stream);throw e}
    }
    fun importRecording(recording:File,session:String,source:Int,device:Int):String = synchronized(lock) {
        val data=recording.readBytes();check(data.size>3244 && data.size<=9_600_044)
        check(root.walkTopDown().filter {it.isFile}.sumOf {it.length()}+data.size<2048L*1024*1024){"Датасет заполнен (2 ГБ)"}
        val id=UUID.randomUUID().toString();val sha=HomeKnowledge.sha(data)
        write(file(root,id,"-raw.wav"),data)
        val note=JSONObject().put("id",id).put("sessionId",session).put("createdAt",System.currentTimeMillis())
            .put("durationMs",(data.size-44).toLong()*1000/32000).put("engine","recording").put("recognized","").put("corrected","")
            .put("reviewed",false).put("deleted",false).put("origin","phone").put("captureSource",source).put("deviceType",device).put("sampleRate",16000)
            .put("rawSha256",sha).put("cleanSha256",sha).put("revision",1)
        write(file(root,id,".json"),note.toString().toByteArray());recording.delete();HomeLink.kick(context);id
    }
    fun save(buffer:SpeechSampleBuffer, text:String, engine:String, session:String, source:Int, device:Int):String? = synchronized(lock) {
        val selected=mode();if(selected=="off")return null
        val raw=buffer.pcm(false);val clean=buffer.pcm(true);if(raw.size<3200 || raw.size!=clean.size || buffer.silenced)return null
        val id=UUID.randomUUID().toString();val dir=if(selected=="all")root else candidates
        // Bound both the reviewed dataset and temporary candidates; never evict reviewed speech.
        if(root.walkTopDown().filter {it.isFile}.sumOf {it.length()} + raw.size*2 > 2048L*1024*1024) {
            context.getSharedPreferences("settings",0).edit().putString("speech_dataset_status","Датасет заполнен (2 ГБ). Выгрузите записи или удалите ненужные.").apply();return null
        }
        val rawWav=AudioNotes.wavHeader(raw.size)+raw;val cleanWav=AudioNotes.wavHeader(clean.size)+clean
        write(file(dir,id,"-raw.wav"),rawWav);write(file(dir,id,"-clean.wav"),cleanWav)
        val note=JSONObject().put("id",id).put("sessionId",session).put("createdAt",System.currentTimeMillis())
            .put("durationMs",raw.size*1000/32000).put("engine",engine).put("recognized",text.take(4000)).put("corrected","")
            .put("reviewed",false).put("deleted",false).put("origin","phone").put("captureSource",source).put("deviceType",device).put("sampleRate",16000)
            .put("rawSha256",HomeKnowledge.sha(rawWav)).put("cleanSha256",HomeKnowledge.sha(cleanWav)).put("revision",1)
        write(file(dir,id,".json"),note.toString().toByteArray())
        pruneCandidates();if(selected=="all")HomeLink.kick(context);id
    }
    private fun entries(dir:File)=dir.listFiles().orEmpty().filter {it.name.endsWith(".json")}.map {JSONObject(AtomicFile(it).readFully().toString(Charsets.UTF_8))}.sortedByDescending {it.getLong("createdAt")}
    fun list():List<JSONObject> = synchronized(lock) {entries(root).filter {!it.optBoolean("deleted")}}
    fun outgoing():List<JSONObject> = synchronized(lock) {entries(root)}
    fun delete(id:String) = synchronized(lock) {
        val value=read(id);val persistent=file(root,id,".json").exists()
        if(persistent) {value.put("deleted",true).put("reviewed",false).put("corrected","").put("revision",value.getLong("revision")+1);write(file(root,id,".json"),value.toString().toByteArray())}
        else file(candidates,id,".json").delete()
        listOf("-raw.wav","-clean.wav").forEach {suffix->file(root,id,suffix).delete();file(candidates,id,suffix).delete()}
        HomeLink.kick(context)
    }
    fun candidates():List<JSONObject> = synchronized(lock) {pruneCandidates();entries(candidates)}
    private fun pruneCandidates() {
        entries(candidates).forEachIndexed { index,n ->
            if(index>=12 || System.currentTimeMillis()-n.getLong("createdAt")>24*60*60_000L) {
                listOf(".json","-raw.wav","-clean.wav").forEach {file(candidates,n.getString("id"),it).delete()}
            }
        }
    }
    fun read(id:String):JSONObject = synchronized(lock) {
        val path=file(root,id,".json").takeIf {it.exists()} ?: file(candidates,id,".json")
        JSONObject(AtomicFile(path).readFully().toString(Charsets.UTF_8))
    }
    fun correct(id:String,text:String):JSONObject = synchronized(lock) {
        require(text.trim().length in 1..4000)
        val value=read(id)
        if(!file(root,id,".json").exists()) {
            val bytes=listOf("-raw.wav","-clean.wav").map {file(candidates,id,it).readBytes()}
            check(root.walkTopDown().filter {it.isFile}.sumOf {it.length()}+bytes.sumOf {it.size}<=2048L*1024*1024) {"Датасет заполнен"}
            listOf("-raw.wav","-clean.wav").forEachIndexed {i,suffix->write(file(root,id,suffix),bytes[i])}
        }
        value.put("corrected",text.trim()).put("reviewed",true).put("revision",value.getLong("revision")+1)
        write(file(root,id,".json"),value.toString().toByteArray())
        listOf(".json","-raw.wav","-clean.wav").forEach {file(candidates,id,it).delete()}
        HomeLink.kick(context);value
    }
    fun audio(id:String,clean:Boolean):File = synchronized(lock) {
        val suffix=if(clean)"-clean.wav" else "-raw.wav"
        file(root,id,suffix).takeIf {it.exists()} ?: if(clean && file(root,id,"-raw.wav").exists() && read(id).getString("rawSha256")==read(id).getString("cleanSha256")) file(root,id,"-raw.wav") else file(candidates,id,suffix)
    }
    fun status():String = synchronized(lock) {
        val items=list();"Примеров: ${items.size}; проверено: ${items.count {it.getBoolean("reviewed")}}.\n"+
            "Недавних реплик для исправления: ${candidates().size}.\n"+
            context.getSharedPreferences("settings",0).getString("speech_dataset_status","").orEmpty()
    }
    companion object {private val lock=Any()}
}
