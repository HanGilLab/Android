package io.github.hangillab.hangil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.VectorDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import io.github.hangillab.hangil.core.designsystem.R as DesignR

/** Resource render only: no Activity, vehicle access, repository writes, or product screen. */
@RunWith(AndroidJUnit4::class)
class SceneIllustrationRenderTest {
    @Test fun renderAtMultipleSizesOnLightAndDark() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.github.hangillab.hangil.debug", context.packageName)
        val assets =
            listOf(
                "prepare" to DesignR.drawable.il_hangil_prepare,
                "review" to DesignR.drawable.il_hangil_review,
                "finish" to DesignR.drawable.il_hangil_finish,
            )
        // Representative page backgrounds from the design tokens, not a theme integration test.
        val backgrounds = listOf("light" to Color.rgb(247, 250, 246), "dark" to Color.rgb(16, 30, 25))
        val directory = File(context.cacheDir, "illustration-preview").apply { mkdirs() }
        val board = Bitmap.createBitmap(1080, 480, Bitmap.Config.ARGB_8888)
        val boardCanvas = Canvas(board)
        backgrounds.forEachIndexed { row, (tone, background) ->
            boardCanvas.save()
            boardCanvas.clipRect(0, row * 240, 1080, (row + 1) * 240)
            boardCanvas.drawColor(background)
            boardCanvas.restore()
            assets.forEachIndexed { column, (name, id) ->
                val drawable = checkNotNull(context.getDrawable(id))
                assertTrue("$name must inflate as a native vector", drawable is VectorDrawable)
                for ((width, height) in listOf(144 to 96, 720 to 480)) {
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.bounds = Rect(0, 0, width, height)
                    drawable.draw(canvas)
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    assertTrue("$name must contain visible artwork", pixels.any { Color.alpha(it) > 0 })
                    // Transparent space at all outer edges catches viewport/translation clipping.
                    assertTrue((0 until width).all { Color.alpha(pixels[it]) == 0 && Color.alpha(pixels[(height - 1) * width + it]) == 0 })
                    assertTrue(
                        (0 until height).all {
                            Color.alpha(pixels[it * width]) == 0 &&
                                Color.alpha(pixels[it * width + width - 1]) == 0
                        },
                    )
                    val preview = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    Canvas(preview).apply {
                        drawColor(background)
                        drawBitmap(bitmap, 0f, 0f, null)
                    }
                    save(preview, File(directory, "illustration-$name-$width-$tone.png"))
                    preview.recycle()
                    bitmap.recycle()
                }
                drawable.bounds = Rect(column * 360 + 24, row * 240 + 16, column * 360 + 336, row * 240 + 224)
                drawable.draw(boardCanvas)
            }
        }
        save(board, File(directory, "illustrations-preview.png"))
        board.recycle()
    }

    private fun save(
        bitmap: Bitmap,
        destination: File,
    ) {
        destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
