package local.voice

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AudioSpectrumTest {
    private fun tone(hz:Double,amplitude:Double)=ShortArray(AudioSpectrum.SIZE){(sin(2*PI*hz*it/16000)*amplitude).toInt().toShort()}
    @Test fun frequenciesAndLoudnessComeFromPcm() {
        val low=AudioSpectrum.analyze(tone(250.0,12000.0))
        val high=AudioSpectrum.analyze(tone(3000.0,12000.0))
        assertTrue(low.indices.maxBy {low[it]} < high.indices.maxBy {high[it]})
        assertTrue(AudioSpectrum.analyze(tone(1000.0,12000.0)).max() > AudioSpectrum.analyze(tone(1000.0,1000.0)).max())
        assertTrue(low.all {it.isFinite() && it in 0f..1f})
    }
    @Test fun silenceDcMutedInputAndStaleSignalAreFlat() {
        assertTrue(AudioSpectrum.analyze(ShortArray(AudioSpectrum.SIZE)).all {it==0f})
        assertTrue(AudioSpectrum.analyze(ShortArray(AudioSpectrum.SIZE){10000}).all {it==0f})
        val spectrum=AudioSpectrum()
        spectrum.push(tone(1000.0,12000.0),false,1_000_000_000)
        assertTrue(spectrum.read(1_010_000_000).any {it>0f})
        assertTrue(spectrum.read(1_300_000_000).all {it==0f})
        spectrum.push(tone(1000.0,12000.0),true,1_020_000_000)
        assertTrue(spectrum.read(1_030_000_000).all {it==0f})
    }
}
