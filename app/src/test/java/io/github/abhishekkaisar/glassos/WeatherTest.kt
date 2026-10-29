package io.github.abhishekkaisar.glassos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class WeatherTest {
    private val reply = """{"latitude":47.6,"longitude":-122.3,"current_units":{"time":"iso8601","interval":"seconds",
        "temperature_2m":"°F","weather_code":"wmo code","is_day":""},
        "current":{"time":"2026-10-28T21:45","interval":900,"temperature_2m":51.4,"weather_code":61,"is_day":0}}"""

    @Test fun everyWmoCodeHasASky() {
        val expected = mapOf(0 to Sky.CLEAR, 1 to Sky.PARTLY, 2 to Sky.PARTLY, 3 to Sky.CLOUDY, 45 to Sky.FOG, 48 to Sky.FOG,
            51 to Sky.DRIZZLE, 55 to Sky.DRIZZLE, 56 to Sky.FREEZING, 67 to Sky.FREEZING, 61 to Sky.RAIN, 82 to Sky.RAIN,
            71 to Sky.SNOW, 77 to Sky.SNOW, 86 to Sky.SNOW, 95 to Sky.STORM, 99 to Sky.STORM)
        for ((code, sky) in expected) assertEquals("code $code", sky, Weather.sky(code))
        // A code Open-Meteo adds later still draws something.
        assertEquals(Sky.STORM, Weather.sky(97))
        assertEquals(Sky.CLOUDY, Weather.sky(42))
    }

    @Test fun unitFollowsTheCountryUnlessChosen() {
        assertTrue(Weather.fahrenheit("auto", "US"))
        assertTrue(Weather.fahrenheit("auto", "pr"))
        assertFalse(Weather.fahrenheit("auto", "GB"))
        assertFalse(Weather.fahrenheit("auto", ""))
        assertTrue(Weather.fahrenheit("f", "DE"))
        assertFalse(Weather.fahrenheit("c", "US"))
    }

    @Test fun placeIsRoundedAndWrittenTheSameInEveryLocale() {
        val was = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("47.6,-122.3", Weather.placeKey(47.6062, -122.3321))
            assertEquals("-33.9,151.2", Weather.placeKey(-33.8688, 151.2093))
            val url = Weather.url("47.6,-122.3", fahrenheit = true)
            assertTrue(url, url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=47.6&longitude=-122.3&"))
            assertTrue(url.contains("current=temperature_2m,weather_code,is_day"))
            assertTrue(url.contains("temperature_unit=fahrenheit"))
            assertTrue(Weather.url("0.0,0.0", fahrenheit = false).contains("temperature_unit=celsius"))
        } finally { Locale.setDefault(was) }
    }

    @Test fun replyIsParsedAndCheckedAgainstTheUnitAskedFor() {
        val w = Weather.parse(reply, fahrenheit = true, place = "47.6,-122.3", now = 1000L)!!
        assertEquals(51.4, w.temp, 0.001)
        assertEquals("51°", w.degrees)
        assertEquals(Sky.RAIN, w.sky)
        assertFalse(w.day)
        assertEquals(1000L, w.at)
        // Open-Meteo answered in Fahrenheit, but Celsius was asked for: not shown as Celsius.
        assertNull(Weather.parse(reply, fahrenheit = false, place = "47.6,-122.3", now = 0L))
        for (bad in listOf(null, "", "{}", "not json", """{"current":{"temperature_2m":"warm","weather_code":1}}""",
                """{"current":{"temperature_2m":500,"weather_code":1}}""", """{"current":{"temperature_2m":10,"weather_code":120}}""",
                """{"current":{"weather_code":1}}""", "x".repeat(70_000))) {
            assertNull(bad?.take(40), Weather.parse(bad, true, "0.0,0.0", 0L))
        }
        // No units block (or no is_day): taken as asked, and as day.
        val bare = Weather.parse("""{"current":{"temperature_2m":-3.6,"weather_code":71}}""", false, "0.0,0.0", 0L)!!
        assertEquals("-4°", bare.degrees)
        assertTrue(bare.day)
    }

    @Test fun onlyAFreshReadingForThisPlaceAndUnitShows() {
        val w = WeatherNow(60.0, true, 0, true, 10_000L, "47.6,-122.3")
        assertTrue(Weather.usable(w, true, "47.6,-122.3", 10_000L + Weather.STALE_MS))
        assertFalse(Weather.usable(w, true, "47.6,-122.3", 10_000L + Weather.STALE_MS + 1))
        assertFalse(Weather.usable(w, false, "47.6,-122.3", 10_000L))
        assertFalse(Weather.usable(w, true, "34.1,-118.2", 10_000L))
        // A clock set back a little since the reading (a time correction) keeps it; set back by hours, it goes.
        assertTrue(Weather.usable(w, true, "47.6,-122.3", 9_000L))
        assertFalse(Weather.usable(w, true, "47.6,-122.3", 10_000L - Weather.STALE_MS - 1))
        assertFalse(Weather.usable(null, true, "47.6,-122.3", 10_000L))
    }

    @Test fun cachedReadingRoundTrips() {
        val w = WeatherNow(-1.5, false, 95, false, 123L, "51.5,-0.1")
        val back = Weather.decode(Weather.encode(w))!!
        assertEquals(listOf(w.temp, w.fahrenheit, w.code, w.day, w.at, w.place), listOf(back.temp, back.fahrenheit, back.code, back.day, back.at, back.place))
        assertNull(Weather.decode(null))
        assertNull(Weather.decode("{\"temp\":1}"))
    }

    @Test fun backupsCarryTheChoiceButNeverTheReading() {
        val settings = DisplayBackup(weather = true, weatherUnit = "c")
        val text = BackupCodec.encode(BackupData(display = settings), "test", 0)
        val back = (BackupCodec.decode(text) as BackupResult.Ok).data.display!!
        assertEquals(true, back.weather)
        assertEquals("c", back.weatherUnit)
        assertFalse(text.contains("temp"))
        val older = BackupCodec.encode(BackupData(display = DisplayBackup(sound = true)), "test", 0)
        val none = (BackupCodec.decode(older) as BackupResult.Ok).data.display!!
        assertNull(none.weather)
        assertNull(none.weatherUnit)
        for (bad in listOf("kelvin", "F")) {
            val doc = JSONObject(text).apply { getJSONObject("display").put("weatherUnit", bad) }
            assertTrue(bad, BackupCodec.decode(doc.toString()) is BackupResult.Error)
        }
    }
}
