package leafconnect

import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * Status fields for one vehicle, read from the battery payload (kilometres and
 * Celsius), plus the unit rules of the selected region. The localized labels
 * are built in the UI; this holds the numbers and the conversion.
 *
 * `tempCelsius` falls back to the interior temperature record carried in the
 * vehicle prefs, so a start can show the temperature before the slow battery
 * fetch lands.
 */
data class VehicleStatus(
    val socPercent: Int?,
    val tempCelsius: String?,
    val pluginState: String?,
    val rangeKmOn: Double?,
    val rangeKmOff: Double,
    val miles: Boolean,
    val fahrenheit: Boolean,
) {

    /** Temperature in the session's unit; a payload that is not a number keeps its text. */
    fun tempValue(): String? = tempCelsius?.let { raw ->
        if (!fahrenheit) return@let raw
        val celsius = raw.toDoubleOrNull() ?: return@let raw
        AppLocale.formatOneDecimal(AppLocale.fahrenheitFromC(celsius))
    }

    /** True when the unit string to show is °F. */
    val usesFahrenheit: Boolean get() = fahrenheit

    /**
     * Range values in the session's unit, rounded to whole numbers. Null when
     * the payload carries no range. A missing HVAC-off range stays the payload
     * sentinel (-1) rather than being invented, as in the original app.
     */
    fun rangeValues(): Pair<Int, Int>? {
        val on = rangeKmOn ?: return null
        return if (miles) {
            Pair(AppLocale.milesFromKm(on).roundToInt(), AppLocale.milesFromKm(rangeKmOff).roundToInt())
        } else {
            Pair(on.toInt(), rangeKmOff.toInt())
        }
    }

    val hasContent: Boolean get() = socPercent != null || tempCelsius != null || pluginState != null || rangeKmOn != null

    companion object {
        fun fromPayload(
            store: SettingsStore,
            battery: JsonObject?,
            fallbackTempRecords: JsonObject?,
        ): VehicleStatus {
            val records = battery?.objectAt("batteryRecords")
            val soc = records?.objectAt("batteryStatus")?.objectAt("soc")?.double("value", -1.0)
            val temp = battery?.objectAt("temperatureRecords")?.text("inc_temp")
                ?: fallbackTempRecords?.double("inc_temp", 0.0)?.toString()
            return VehicleStatus(
                socPercent = soc?.takeIf { it >= 0 }?.toInt(),
                tempCelsius = temp?.takeIf { it.isNotEmpty() },
                pluginState = records?.text("pluginState")?.takeIf { it.isNotEmpty() },
                rangeKmOn = records?.double("cruisingRangeAcOn", -1.0)?.takeIf { it >= 0 },
                rangeKmOff = records?.double("cruisingRangeAcOff", -1.0) ?: -1.0,
                miles = AppLocale.usesMiles(store),
                fahrenheit = AppLocale.usesFahrenheit(store),
            )
        }
    }
}
