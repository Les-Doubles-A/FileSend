package app.filesend.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GlassPolicyTest {
    // AC-F5-3 : pas de flou avant Android 12, meme si le systeme dit "active"
    @Test fun noBlurBeforeAndroid12() {
        assertEquals(0, GlassPolicy.blurRadius(29, true))
        assertEquals(0, GlassPolicy.blurRadius(30, true))
    }

    // AC-F5-3 : flou desactive par le systeme (economie d'energie, option) -> fond opaque
    @Test fun noBlurWhenSystemDisablesIt() {
        assertEquals(0, GlassPolicy.blurRadius(31, false))
        assertEquals(0, GlassPolicy.blurRadius(36, false))
    }

    @Test fun blurOnAndroid12PlusWhenAllowed() {
        assertEquals(GlassPolicy.BLUR_RADIUS_PX, GlassPolicy.blurRadius(31, true))
        assertEquals(GlassPolicy.BLUR_RADIUS_PX, GlassPolicy.blurRadius(36, true))
        // Les docs deconseillent plus de 150 px (cout) : on reste dans la fourchette conseillee.
        assert(GlassPolicy.BLUR_RADIUS_PX in 20..150)
    }

    @Test fun themesDiffer() {
        assertNotEquals(glassColors(true).solid, glassColors(false).solid)
        assertNotEquals(glassColors(true).text, glassColors(false).text)
    }
}
