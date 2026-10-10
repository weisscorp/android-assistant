package local.voice

/** One native state per capture stream, accessed only by its PCM worker. */
class EchoProcessor : AutoCloseable {
    companion object { init { System.loadLibrary("voicedsp") } }
    private external fun nativeCreate(): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeProcess(handle: Long, mic: ShortArray, reference: ShortArray, output: ShortArray)
    private var handle = nativeCreate()
    fun process(mic: ShortArray, reference: ShortArray, output: ShortArray) {
        check(handle != 0L)
        nativeProcess(handle, mic, reference, output)
    }
    override fun close() { if (handle != 0L) { nativeFree(handle); handle = 0 } }
}
