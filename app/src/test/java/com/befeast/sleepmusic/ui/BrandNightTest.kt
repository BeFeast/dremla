package com.befeast.sleepmusic.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.befeast.sleepmusic.R
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BrandNightTest {
    @Test fun everyNightResourceAndUiColorMatchesTheExactPalette() {
        val expected = mapOf(
            "ground" to 0xff000000.toInt(), "panel" to 0xff15100f.toInt(),
            "line" to 0xff2b1c18.toInt(), "key" to 0xff4a3229.toInt(),
            "track" to 0xff2a1512.toInt(), "ember" to 0xffb8443c.toInt(),
            "text" to 0xffad7a5b.toInt(), "text2" to 0xffa67253.toInt(),
            "focus_fill" to 0xff2c1715.toInt(),
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun resources(prefix: String) = R.color::class.java.fields
            .filter { it.name.startsWith(prefix) }
            .associate { it.name.removePrefix(prefix) to context.getColor(it.getInt(null)) }
        assertEquals("Every night resource must have its approved value", expected, resources("brand_night_"))

        // Color is an inline class: its JVM property getters return packed longs and have
        // mangled names. Discover getters (including computed properties), not a token list.
        val uiColors = BrandColors::class.java.declaredMethods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType }
            .associate { getter ->
                val token = getter.name.substringAfter("get").substringBefore('-')
                    .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
                token to Color((getter.invoke(BrandColors) as Long).toULong()).toArgb()
            }
        assertEquals("Every UI color must be an approved night token", expected, uiColors)
        val launcher = resources("brand_launcher_")
        assertTrue("Launcher palette must be discovered", launcher.isNotEmpty())
        uiColors.forEach { (token, argb) ->
            assertFalse("$token must not use a launcher color", argb in launcher.values)
            assertTrue("$token exceeds the blue-channel limit", (argb and 255) <= 91)
        }
    }
    @Test fun archivoInterChainBuildsAtEveryWeightWithoutSilentFallback() {
        // A window creates Paint before composing; reproduce that native-runtime initialization.
        android.graphics.Paint()
        val chained = BrandFonts::class.java.getDeclaredMethod("chained", Context::class.java, Int::class.javaPrimitiveType)
        chained.isAccessible = true
        try { chained.invoke(BrandFonts, ApplicationProvider.getApplicationContext<Context>(), 400) }
        catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
        val families = BrandFonts.families(ApplicationProvider.getApplicationContext<Context>())
        assertEquals(setOf(300, 400, 600), families.keys)
        families.values.forEach { assertFalse("Must be the chained platform family, not plain Archivo", it is FontListFontFamily) }
    }
}
