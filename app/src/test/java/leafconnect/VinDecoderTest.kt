package leafconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VinDecoderTest {

    @Test
    fun decodesValidNorthAmericanVin() {
        val result = VinDecoder.decode("1N4AL3AP3CC123456")
        assertTrue(result is VinResult.Success)
        val info = (result as VinResult.Success).info
        assertEquals("1N4AL3AP3CC123456", info.vin)
        assertEquals("North America", info.region)
        assertEquals("United States", info.country)
    }

    @Test
    fun rejectsShortVin() {
        val result = VinDecoder.decode("1N4AL3AP3CC12345")
        assertTrue(result is VinResult.Failure)
    }

    @Test
    fun rejectsLettersIoQ() {
        val result = VinDecoder.decode("1N4AL3APOCC123456")
        assertTrue(result is VinResult.Failure)
    }

    @Test
    fun checkDigitIsValidCharacter() {
        val digit = VinDecoder.computeCheckDigit("1N4AL3AP3CC123456")
        assertTrue(digit == 'X' || digit in '0'..'9')
    }
}
