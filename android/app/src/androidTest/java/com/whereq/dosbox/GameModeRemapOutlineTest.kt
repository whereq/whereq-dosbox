package com.whereq.dosbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The remap outline on the gamepad, on a device.
 *
 * While the remap mode is on, the buttons that can be remapped get an
 * outline, and the outline has to be visible on the button it marks. The FIRE
 * button is filled with the gold, so a gold outline on it is invisible - the
 * button looks like any other one, and the user does not know it can be
 * remapped.
 *
 * The unit test (`RemapOutlineTest`) holds the rule; this test looks at the
 * button the rule exists for, on the real gamepad, and checks the **pixels**:
 * the outline the app drew has to be there, and it has to be the dark colour
 * and not the gold of the button underneath it.
 *
 * The gamepad is laid out by hand, at the size of a landscape phone, so the
 * test does not depend on the screen it runs on: the gamepad builds its pads
 * from the width of the letterbox bars, and on a narrow screen there are no
 * pads to look at.
 */
@RunWith(AndroidJUnit4::class)
class GameModeRemapOutlineTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /**
     * The activity, used as the context of the gamepad.
     *
     * The context matters: the chips are built from `TextView`s, and a
     * `TextView` created with the *application* context has no theme to
     * resolve its text appearance from. The real gamepad is built with the
     * activity, and so is this one.
     */
    private lateinit var activity: WhereqDosboxActivity

    @Before
    fun launchTheApp() {
        val intent = android.content.Intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            WhereqDosboxActivity::class.java
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = instrumentation.startActivitySync(intent) as WhereqDosboxActivity
    }

    @Test
    fun theFireButtonIsOutlinedInADarkColour() {
        val gamepad = theGamepad()
        val fire = findChip(gamepad, "FIRE")
        assertNotNull("the ACTION profile should have a FIRE button", fire)

        val picture = renderTheButton(fire!!)
        assertTrue("the FIRE button should be drawable", picture.width > 0 && picture.height > 0)

        // The outline is drawn along the edge of the button, so the top row
        // of pixels is the outline. It used to be the gold of the button
        // itself, which is exactly what made it invisible.
        val outline = picture.getPixel(picture.width / 2, 0)
        assertEquals(
            "the outline of the FIRE button should be the dark colour, but the " +
                "top row of the button is ${hex(outline)}",
            GameModeView.COLOR_TEXT_ON_GOLD,
            outline
        )

        // And the button underneath it is still the gold.
        assertTrue(
            "the FIRE button should still be filled with the gold",
            contains(picture, GameModeView.COLOR_GOLD)
        )
        assertTrue(
            "the outline must not be the colour of the button it marks",
            outline != GameModeView.COLOR_GOLD
        )
    }

    @Test
    fun theDarkButtonsAreOutlinedInGold() {
        val gamepad = theGamepad()

        // USE is one of the key-sending buttons: a dark chip, so the gold
        // outline is the one that shows on it.
        val use = findChip(gamepad, "USE")
        assertNotNull("the ACTION profile should have a USE button", use)

        val picture = renderTheButton(use!!)
        val outline = picture.getPixel(picture.width / 2, 0)
        assertEquals(
            "a dark button should get the gold outline, but the top row of " +
                "the button is ${hex(outline)}",
            GameModeView.COLOR_GOLD,
            outline
        )
    }

    /* ------------------------------------------------------------------ */

    /**
     * The gamepad, built and in remap mode.
     *
     * It is laid out by hand, at the size of a landscape phone, because the
     * gamepad builds its pads from the width of the letterbox bars and only
     * does that once it has a size.
     */
    private fun theGamepad(): GameModeView {
        val gamepad = GameModeView(activity)

        onMain {
            // The FIRE button is on the ACTION profile's face diamond.
            gamepad.setProfile(GameModeView.Profile.ACTION, notify = false)
            layOut(gamepad)
        }
        // The layout posts the build of the pads; let it run.
        instrumentation.waitForIdleSync()

        // And then the remap mode, which is what draws the outline. It
        // rebuilds the whole bar, so the new buttons need a layout of their
        // own before they have a size.
        onMain {
            gamepad.enterRemapModeForTest()
            layOut(gamepad)
        }
        instrumentation.waitForIdleSync()

        assertNotNull(
            "the ACTION profile should have a FIRE button",
            findChip(gamepad, "FIRE")
        )
        return gamepad
    }

    /** Measures and lays the gamepad out at the size of a landscape phone. */
    private fun layOut(gamepad: View) {
        // The rebuild replaces the buttons with new ones, and a view whose
        // size has not changed skips its measure pass - so the new buttons
        // would never be measured, and their labels would have no size.
        // forceLayout() asks for the measure pass to happen anyway.
        gamepad.forceLayout()
        gamepad.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        gamepad.layout(0, 0, WIDTH, HEIGHT)
    }

    /** @return the chip with that label inside [view], or null. */
    private fun findChip(view: View, label: String): View? {
        var found: View? = null
        onMain {
            val text = collect(view).filterIsInstance<TextView>()
                .firstOrNull { it.text.toString() == label }
            // The chip is the container of the label.
            found = text?.parent as? View
        }
        return found
    }

    /** Every view below [view], [view] itself included. */
    private fun collect(view: View): List<View> {
        val views = mutableListOf<View>()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                views.addAll(collect(view.getChildAt(i)))
            }
        }
        views.add(view)
        return views
    }

    /** Draws the button on its own, so its pixels can be read. */
    private fun renderTheButton(button: View): Bitmap {
        var bitmap: Bitmap? = null
        onMain {
            if (button.width > 0 && button.height > 0) {
                bitmap = Bitmap.createBitmap(
                    button.width, button.height, Bitmap.Config.ARGB_8888
                ).also { button.draw(Canvas(it)) }
            }
        }
        assertNotNull("the button should be drawable", bitmap)
        return bitmap!!
    }

    /** @return true when the picture holds a pixel of that colour. */
    private fun contains(picture: Bitmap, colour: Int): Boolean {
        for (y in 0 until picture.height) {
            for (x in 0 until picture.width) {
                if (picture.getPixel(x, y) == colour) {
                    return true
                }
            }
        }
        return false
    }

    /** Runs [block] on the UI thread and returns its result. */
    private fun <T> onMain(block: () -> T): T {
        var result: Any? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun hex(colour: Int): String = String.format("#%08X", colour)

    private companion object {
        /** A landscape phone: the 4:3 picture in the middle, bars on the sides. */
        const val WIDTH = 2400
        const val HEIGHT = 1080
    }
}
