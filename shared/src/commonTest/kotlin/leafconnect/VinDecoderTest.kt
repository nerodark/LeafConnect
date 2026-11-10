package leafconnect

import kotlin.test.Test
import kotlin.test.assertTrue

class VinDecoderTest {

    @Test
    fun decodesValidNorthAmericanVin() {
        val result = VinDecoder.decode("1N4AL3AP3CC123456")
        assertTrue(result is VinResult.Success)
        val info = (result as VinResult.Success).info
        assertTrue(info.vin == "1N4AL3AP3CC123456")
        assertTrue(info.region == "North America")
        assertTrue(info.country == "United States")
    }

    @Test
    fun rejectsShortVin() {
        assertTrue(VinDecoder.decode("1N4AL3AP3CC12345") is VinResult.Failure)
    }

    @Test
    fun rejectsLettersIoQ() {
        assertTrue(VinDecoder.decode("1N4AL3APOCC123456") is VinResult.Failure)
    }

    @Test
    fun checkDigitIsValidCharacter() {
        val digit = VinDecoder.computeCheckDigit("1N4AL3AP3CC123456")
        assertTrue(digit == 'X' || digit in '0'..'9')
    }
}
