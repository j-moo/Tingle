package com.ssafy.e106.features.profile

import com.ssafy.e106.features.profile.photo.*
import kotlin.test.*

class SquareCropTest {
    @Test fun portraitAndLandscapeStartCenteredWithoutEmptyPixels() {
        assertEquals(CropFrame(500, 0, 2000), SquareCrop().frame(3000, 2000))
        assertEquals(CropFrame(0, 500, 2000), SquareCrop().frame(2000, 3000))
    }
    @Test fun everyPanZoomAndExtremeInputRemainsInsideOriginal() {
        for ((w, h) in listOf(1 to 1, 13 to 4000, 4000 to 13, 1080 to 1920)) {
            for (zoom in listOf(-1f, 1f, 2f, 4f, 20f, Float.NaN)) {
                for (x in listOf(-10f, 0f, .5f, 1f, 10f, Float.NaN)) {
                    val f = SquareCrop(zoom, x, x).frame(w, h)
                    assertTrue(f.side >= 1 && f.x >= 0 && f.y >= 0)
                    assertTrue(f.x + f.side <= w && f.y + f.side <= h)
                }
            }
        }
    }
    @Test fun dragFollowsFingerAndDoesNotStickBeyondEdges() {
        val original = SquareCrop(2f).constrained(2000, 1000)
        val moved = original.move(100f, 0f, 500f, 2000, 1000)
        assertEquals(original.frame(2000, 1000).x - 100, moved.frame(2000, 1000).x)
        val edge = original.move(100000f, 0f, 500f, 2000, 1000)
        assertEquals(0, edge.frame(2000, 1000).x)
        assertTrue(edge.move(-10f, 0f, 500f, 2000, 1000).frame(2000, 1000).x > 0)
        assertEquals(original, original.move(1f, 1f, 0f, 2000, 1000))
    }
    @Test fun constrainedCropKeepsSamePixelsForPreviewAndExport() {
        for (crop in listOf(SquareCrop(), SquareCrop(2.3f, .9f, .1f), SquareCrop(4f, 1f, 0f))) {
            assertEquals(crop.frame(1920, 1080), crop.constrained(1920, 1080).frame(1920, 1080))
        }
    }
}
