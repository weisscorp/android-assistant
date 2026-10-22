package local.voice

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.*
import java.util.concurrent.Executors

class SpeechDatasetActivity:Activity() {
    private val palette by lazy {AssistantTheme(this)}
    private val store by lazy {SpeechDataset(this)}
    private var player:MediaPlayer?=null
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var recordButton:Button
    private lateinit var recordingStatus:TextView
    private val tick=object:Runnable {override fun run(){
        if(::recordButton.isInitialized)recordButton.text=if(SpeechDatasetRecorder.recording)"Остановить диктовку" else "Записать диктовку для обучения"
        if(::recordingStatus.isInitialized)recordingStatus.text=SpeechDatasetRecorder.message+(if(SpeechDatasetRecorder.recording)" · ${(System.currentTimeMillis()-SpeechDatasetRecorder.startedAt)/1000} с" else "")
        handler.postDelayed(this,1000)
    }}
    override fun onCreate(savedInstanceState:Bundle?) {super.onCreate(savedInstanceState);render()}
    private fun render() {
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(20),dp(20),dp(20));setBackgroundColor(palette.background)}
        fun label(text:String)=root.addView(TextView(this).apply {this.text=text;textSize=16f;setTextColor(palette.text);setPadding(0,dp(12),0,dp(12))})
        fun button(text:String,action:()->Unit):Button=Button(this).apply {this.text=text;isAllCaps=false;minHeight=dp(48);setOnClickListener {action()};root.addView(this)}
        button("← Параметры") {finish()};label("Датасет распознавания речи")
        recordButton=button(if(SpeechDatasetRecorder.recording)"Остановить диктовку" else "Записать диктовку для обучения") {
            if(SpeechDatasetRecorder.recording)startService(Intent(this,SpeechDatasetRecorder::class.java).setAction("stop")) else beginDictation()
        }
        recordingStatus=TextView(this).apply {setTextColor(palette.secondary);text=SpeechDatasetRecorder.message};root.addView(recordingStatus)
        label("Диктуйте сколько нужно: звук сохраняется частями по 5 минут. Используется подключённая гарнитура или микрофон телефона. Можно выключить экран. Текст разметит большая модель на компьютере.")
        label(store.status())
        label("Дополнительно можно сохранять голосовые запросы к ассистенту. Исправления человеком учитываются отдельно. Исправление не отправляет команду повторно.")
        val modes=arrayOf("Выключено","Только исправленные фразы","Все голосовые реплики")
        val values=listOf("off","corrected","all")
        button("Сбор: ${modes[values.indexOf(store.mode()).coerceAtLeast(0)]}") {
            AlertDialog.Builder(this).setTitle("Сбор датасета").setSingleChoiceItems(modes,values.indexOf(store.mode())) {dialog,index->store.setMode(values[index]);dialog.dismiss();render()}.setNegativeButton("Закрыть",null).show()
        }
        button("Синхронизировать с компьютером") {HomeLink.kick(this);Toast.makeText(this,"Записи поставлены на синхронизацию с хабом",Toast.LENGTH_SHORT).show()}
        val notes=(store.list()+store.candidates()).distinctBy {it.getString("id")}.sortedByDescending {it.getLong("createdAt")}
        notes.forEach {n->
            val id=n.getString("id")
            label("${n.getString("engine")} · ${n.getLong("durationMs")/1000.0} с · ${if(n.getBoolean("reviewed")) "Проверено" else "Не проверено"}\nASR: ${n.getString("recognized")}\nВерно: ${n.getString("corrected").ifBlank {"—"}}")
            button("Прослушать оригинал") {
                stopPlayback();runCatching {player=MediaPlayer().apply {setDataSource(store.audio(id,false).absolutePath);prepare();setOnCompletionListener {stopPlayback()};start()}}.onFailure {Toast.makeText(this,"Запись недоступна",Toast.LENGTH_SHORT).show()}
            }
            button("Исправить / подтвердить текст") {correct(this,id) {render()}}
            button("Удалить пример") {AlertDialog.Builder(this).setMessage("Удалить запись и исключить её из датасета?").setPositiveButton("Удалить") {_,_->stopPlayback();store.delete(id);render()}.setNegativeButton("Отмена",null).show()}
        }
        if(player!=null)button("Остановить запись") {stopPlayback()}
        val scroll=ScrollView(this).apply {addView(root)}
        scroll.setOnApplyWindowInsetsListener {v,insets->val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        setContentView(scroll);scroll.requestApplyInsets()
    }
    private fun beginDictation() {
        val missing=arrayOf(android.Manifest.permission.RECORD_AUDIO,android.Manifest.permission.BLUETOOTH_CONNECT).filter {checkSelfPermission(it)!=android.content.pm.PackageManager.PERMISSION_GRANTED}
        if(missing.isNotEmpty()){requestPermissions(missing.toTypedArray(),311);return}
        stopPlayback();startForegroundService(Intent(this,SpeechDatasetRecorder::class.java))
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);if(code==311 && permissions.all {checkSelfPermission(it)==android.content.pm.PackageManager.PERMISSION_GRANTED})beginDictation()}
    override fun onResume(){super.onResume();handler.post(tick)}
    override fun onPause(){handler.removeCallbacks(tick);super.onPause()}
    private fun stopPlayback() {player?.let {runCatching {it.stop()};it.release()};player=null}
    override fun onStop() {stopPlayback();super.onStop()}
    companion object {
        private val worker=Executors.newSingleThreadExecutor()
        fun correct(activity:Activity,id:String,done:(String)->Unit={}) {
            val store=SpeechDataset(activity)
            val sample=runCatching {store.read(id)}.getOrElse {Toast.makeText(activity,"Срок хранения исходной записи истёк",Toast.LENGTH_LONG).show();return}
            val input=EditText(activity).apply {setText(sample.getString("corrected").ifBlank {sample.getString("recognized")});minLines=2;maxLines=6}
            AlertDialog.Builder(activity).setTitle("Что вы сказали на записи?").setView(input).setMessage("ASR: ${sample.getString("recognized")}\nВведите точную расшифровку, включая разговорные слова.")
                .setPositiveButton("Сохранить исправление") {_,_->
                    val value=input.text.toString().trim()
                    worker.execute {
                        val result=runCatching {store.correct(id,value)}
                        activity.runOnUiThread {if(!activity.isDestroyed){
                            if(result.isSuccess){done(value);Toast.makeText(activity,"Запись и исправленный текст сохранены для обучения",Toast.LENGTH_LONG).show()}
                            else Toast.makeText(activity,result.exceptionOrNull()?.message ?: "Не удалось сохранить",Toast.LENGTH_LONG).show()
                        }}
                    }
                }.setNegativeButton("Отмена",null).show()
        }
    }
}
