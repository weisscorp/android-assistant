package local.voice

/** HFP audio can end without another assistant intent or a selected-device change. */
internal class HeadsetAudioEnd(private val selectedAddress: String) {
    private var connected = false
    private var ended = false
    fun update(address: String?, state: Int, previous: Int): Boolean {
        if (ended || selectedAddress.isBlank() || !selectedAddress.equals(address, ignoreCase = true)) return false
        if (state == 12) connected = true // BluetoothHeadset.STATE_AUDIO_CONNECTED
        if (state != 10 || (!connected && previous != 12)) return false
        ended = true // BluetoothHeadset.STATE_AUDIO_DISCONNECTED, one termination per route.
        return true
    }
}
