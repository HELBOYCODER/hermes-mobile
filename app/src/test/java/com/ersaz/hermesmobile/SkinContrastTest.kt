package com.ersaz.hermesmobile

import androidx.compose.ui.graphics.Color
import com.ersaz.hermesmobile.ui.theme.Skins
import com.ersaz.hermesmobile.ui.theme.contrastRatio
import com.ersaz.hermesmobile.ui.theme.ensureContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinContrastTest {
    @Test
    fun everySkinsSecondaryTextIsReadableOnItsBackgrounds() {
        for (skin in Skins.all) {
            for ((role, fg) in listOf("dim" to skin.dim, "label" to skin.label, "text" to skin.text, "title" to skin.title,
                "warn" to skin.warn, "error" to skin.error, "ok" to skin.ok)) {
                for ((bgName, bg) in listOf("bg" to skin.bg, "surface" to skin.surface)) {
                    val ratio = contrastRatio(fg, bg)
                    assertTrue("${skin.name}: $role on $bgName is %.2f:1".format(ratio), ratio >= Skins.MIN_TEXT_CONTRAST - 0.01)
                }
            }
        }
    }

    @Test
    fun passingColorsAreLeftUntouched() {
        val white = Color.White
        assertEquals(white, ensureContrast(white, listOf(Color.Black), Color.White))
        assertEquals(21.0, contrastRatio(Color.White, Color.Black), 0.01)
    }

    @Test
    fun accentsKeepTheirBrandColor() {
        // Only text roles are adjusted; the default skin's accent is unchanged.
        assertEquals(Color(0xFF8AB4FF), Skins.byName("nous").accent)
    }
}
