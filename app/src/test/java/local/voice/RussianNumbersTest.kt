package local.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class RussianNumbersTest {
    @Test fun readsSignificantZeroesAndLargeValues() {
        assertEquals("ноль", RussianNumbers.expand("0"))
        assertEquals("ноль ноль один два", RussianNumbers.expand("0012"))
        assertEquals("две тысячи четырнадцать", RussianNumbers.expand("2014"))
        assertEquals("один миллион двадцать одна тысяча двенадцать", RussianNumbers.expand("1021012"))
        assertEquals("пятьдесят два", RussianNumbers.expand("52"))
        assertEquals("девять миллиардов", RussianNumbers.expand("9000000000"))
    }
}
