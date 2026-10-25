package local.voice

import android.app.*
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit long dictation. Saves 5-minute pieces; never loads an ASR/LLM or speaks. */
class SpeechDatasetRecorder:Service() {
    companion object {
        @Volatile var recording=false; private set
        @Volatile var message="Готов к записи"; private set
        @Volatile var startedAt=0L; private set
        fun recover(context:android.content.Context) {
            Executors.newSingleThreadExecutor().also {executor->executor.execute {
                val store=SpeechDataset(context)
                context.filesDir.listFiles().orEmpty().filter {it.name.startsWith("speech-dataset-pending-") && it.extension=="wav"}.forEach {file->
                    runCatching {if(file.length()>3244){
                        val session=file.name.removePrefix("speech-dataset-pending-").removeSuffix(".wav")
                        RandomAccessFile(file,"rw").use {it.setLength(it.length()-(it.length()-44)%2);it.seek(0);it.write(AudioNotes.wavHeader((file.length()-44).toInt()))}
                        store.importRecording(file,session,MediaRecorder.AudioSource.MIC,0)
                    }else file.delete()}
                }
            };executor.shutdown()}
        }
    }
    private val stopping=AtomicBoolean()
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var mic:AudioRecord?=null
    private var wake:PowerManager.WakeLock?=null
    private var route:AudioRoute?=null
    @Volatile private var routeFailure:String?=null
    private var phoneMicrophone=true
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop") {stopCapture();if(!recording)stopSelf();return START_NOT_STICKY}
        if(recording)return START_NOT_STICKY
        try {
            VoiceConversation.stopActive()
            val notifications=getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("speech_dataset","Запись для обучения речи",NotificationManager.IMPORTANCE_LOW).apply {setSound(null,null)})
            val stop=PendingIntent.getService(this,1,Intent(this,SpeechDatasetRecorder::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open=PendingIntent.getActivity(this,1,Intent(this,SpeechDatasetActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification=Notification.Builder(this,"speech_dataset").setSmallIcon(R.drawable.ic_microphone).setContentTitle("Идёт запись для датасета")
                .setContentText("Запись продолжается при выключенном экране").setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null,"Остановить",stop).build()).build()
            startForeground(7314,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"local.voice:dataset").apply {setReferenceCounted(false);acquire()}
            recording=true;startedAt=System.currentTimeMillis();message="Записываю. Можно выключить экран."
            phoneMicrophone=runCatching {AudioRoute.headsets(this).isEmpty()}.getOrDefault(true)
            route=AudioRoute(this,phoneOnly=phoneMicrophone) {routeFailure="Микрофон отключился или начался звонок";stopCapture()}
            route!!.start(requireHeadset=!phoneMicrophone) {error->
                if(error!=null || stopping.get()) {message=error ?: "Запись остановлена";recording=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
                else worker.execute {capture()}
            }
        } catch(e:Exception) {message=e.message ?: "Не удалось начать запись";stopSelf()}
        return START_NOT_STICKY
    }
    private fun capture() {
        val store=SpeechDataset(this);val session=store.session()
        var chunk:File?=null
        val failure=runCatching {
            val manager=getSystemService(AudioManager::class.java)
            val communication=manager.communicationDevice
            val preferred=manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                if(phoneMicrophone)it.type==AudioDeviceInfo.TYPE_BUILTIN_MIC
                else communication!=null && it.type==communication.type && it.address==communication.address
            }
            check(preferred!=null){"Выбранный микрофон недоступен"}
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Нет разрешения на микрофон")
            val capture=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(6400,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT))).build()
            mic=capture;check(capture.state==AudioRecord.STATE_INITIALIZED){"Микрофон недоступен"}
            check(capture.setPreferredDevice(preferred)){"Не удалось выбрать микрофон"}
            if(stopping.get())return@runCatching
            capture.startRecording();check(capture.recordingState==AudioRecord.RECORDSTATE_RECORDING){"Микрофон занят"}
            while(!stopping.get()) {
                chunk=File(filesDir,"speech-dataset-pending-$session.wav")
                val file=chunk!!
                var bytes=0
                file.outputStream().use {out->
                    out.write(AudioNotes.wavHeader(0));val pcm=ByteArray(6400)
                    while(!stopping.get() && bytes<9_600_000) {
                        val count=capture.read(pcm,0,minOf(pcm.size,9_600_000-bytes))
                        if(count<=0 && stopping.get())break
                        check(count>0){"Микрофон отключился"}
                        check(capture.activeRecordingConfiguration?.isClientSilenced!=true){"Android заглушил микрофон. Запись завершена."}
                        out.write(pcm,0,count);bytes+=count
                    }
                    out.flush();out.fd.sync()
                }
                if(bytes>=3200) {
                    RandomAccessFile(file,"rw").use {it.seek(0);it.write(AudioNotes.wavHeader(bytes))}
                    store.importRecording(file,session,MediaRecorder.AudioSource.MIC,capture.routedDevice?.type ?: 0)
                } else file.delete()
                chunk=null
            }
        }.exceptionOrNull()
        runCatching {mic?.stop()};runCatching {mic?.release()};mic=null
        chunk?.let {file-> // Preserve a readable prefix if the microphone disconnects mid-piece.
            runCatching {if(file.length()>3244){RandomAccessFile(file,"rw").use {it.seek(0);it.write(AudioNotes.wavHeader((file.length()-44).toInt()))};store.importRecording(file,session,MediaRecorder.AudioSource.MIC,0)}else file.delete()}
        }
        message=routeFailure ?: failure?.message ?: "Запись сохранена. Части отправятся на компьютер через хаб."
        Handler(Looper.getMainLooper()).post {recording=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    }
    private fun stopCapture(){stopping.set(true);runCatching {mic?.stop()}}
    override fun onDestroy(){stopCapture();route?.close();route=null;wake?.let {if(it.isHeld)it.release()};worker.shutdown();recording=false;super.onDestroy()}
}
