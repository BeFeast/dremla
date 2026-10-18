package com.befeast.sleepmusic.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fetchImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/** Shared settings for the golden scenes. */
object Goldens {
    /** 960x540 dp at xhdpi = 1920x1080 px, the target box's native mode. */
    const val TV_1080P = "w960dp-h540dp-land-television-xhdpi"

    const val WIDTH_PX = 1920
    const val HEIGHT_PX = 1080

    /**
     * Strict comparison: any pixel whose colour differs at all counts (maxDistance 0), and a
     * single differing pixel fails the scene (changeThreshold 0). Actual and compare images go
     * to app/build/outputs/roborazzi, never next to the committed references.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(
            outputDirectoryPath = DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH,
            changeThreshold = 0f,
            imageComparator = SimpleImageComparator(maxDistance = 0f),
        ),
    )
}

/**
 * Asserts that the frame Roborazzi captures is exactly 1920x1080 px, then hands it to Roborazzi,
 * which records or verifies `app/src/test/screenshots/<fileName>` depending on the Gradle task
 * (plain `testDebugUnitTest` runs only the size assertion).
 */
fun SemanticsNodeInteraction.captureGolden(fileName: String) {
    val bitmap = fetchSemanticsNode().fetchImage(Goldens.options.recordOptions)
    assertNotNull("captured bitmap", bitmap)
    assertEquals("captured width in px", Goldens.WIDTH_PX, bitmap!!.width)
    assertEquals("captured height in px", Goldens.HEIGHT_PX, bitmap.height)
    captureRoboImage(fileName, roborazziOptions = Goldens.options)
}
