package dev.omalauncher.pp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.toArgb
import dev.omalauncher.pp.theme.ColorSpec
import dev.omalauncher.pp.theme.ForkStyle
import dev.omalauncher.pp.theme.Style

/**
 * Everything in style.toml's [icons] that changes how an app icon is drawn (from Omalauncher+'s icon settings): when
 * it changes, every icon is drawn again. [pack] and [packStamp] name the icon pack and when it was last updated.
 */
@Immutable
data class IconLook(
    val pack: String? = null,
    val packStamp: Long = 0L,
    /** Icon edge in px. */
    val px: Int,
    val shape: String = ForkStyle.SHAPE_SYSTEM,
    val backdrop: Boolean = true,
    val grayscale: Boolean = false,
    val tintTop: Int? = null,
    val tintBottom: Int? = null,
) {
    companion object {
        /**
         * The look [style] asks for at [density], drawn at least at the drawer's 52 dp, bigger for big icons. Reads
         * [Palette] for a tint in a theme color, so a new theme redraws them.
         */
        fun of(
            style: Style,
            density: Float,
            packStamp: Long,
        ): IconLook {
            fun tint(spec: String) =
                when (spec) {
                    ColorSpec.NONE -> null
                    ColorSpec.THEME -> Palette.accent
                    else -> hexColor(spec) ?: Palette.named(spec)
                }?.toArgb()
            val top = tint(style[ForkStyle.ICON_TINT])
            return IconLook(
                pack = style[ForkStyle.ICON_PACK],
                packStamp = packStamp,
                px = (52 * density * maxOf(1.0, style[ForkStyle.ICON_SIZE]).toFloat()).toInt().coerceAtLeast(48),
                shape = style[ForkStyle.ICON_SHAPE],
                backdrop = style[ForkStyle.ICON_BACKDROP],
                grayscale = style[ForkStyle.ICON_GRAYSCALE],
                // The gradient's bottom only with its top: the Style menu hides it while there's no tint, so it can't be cleared then.
                tintTop = top,
                tintBottom = top?.let { tint(style[ForkStyle.ICON_TINT_BOTTOM]) },
            )
        }
    }
}

/**
 * Finishes a drawn app icon as [look] says: its shape crops it (a circle, or a squircle at ~30% radius), grayscale
 * drains its color, and its tints recolor it: one color multiplies, two shade a duotone gradient top to bottom keeping
 * the light. Silhouettes for the monochrome style stay as drawn. Returns [source] itself when there's nothing to do.
 */
fun finishIcon(
    source: Bitmap,
    look: IconLook,
): Bitmap {
    val shape = look.shape
    val gray = look.grayscale
    val top = look.tintTop
    val bottom = look.tintBottom
    var bmp = source
    if (shape != ForkStyle.SHAPE_SYSTEM) {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val path = Path()
        val half = bmp.width / 2f
        if (shape == ForkStyle.SHAPE_CIRCLE) {
            path.addCircle(half, half, half, Path.Direction.CW)
        } else {
            path.addRoundRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), half * 0.6f, half * 0.6f, Path.Direction.CW)
        }
        Canvas(out).run {
            drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG))
            drawBitmap(bmp, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
        }
        bmp = out
    }
    if (gray && (top == null || bottom == null)) {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        Canvas(out).drawBitmap(bmp, 0f, 0f, paint)
        bmp = out
    }
    if (top != null && bottom != null) {
        bmp = duotone(bmp, top, bottom)
    } else if (top != null) {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = PorterDuffColorFilter(top, PorterDuff.Mode.MULTIPLY) }
        Canvas(out).drawBitmap(bmp, 0f, 0f, paint)
        bmp = out
    }
    return bmp
}

/**
 * Shades [bmp] with a vertical duotone gradient, dark pixels toward [top] and
 * light toward [bottom], keeping every pixel's own light. Done per pixel:
 * bitmap canvases can't rely on blend modes, which need hardware rendering.
 */
private fun duotone(
    bmp: Bitmap,
    top: Int,
    bottom: Int,
): Bitmap {
    val w = bmp.width
    val h = bmp.height
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
    val topHsv = FloatArray(3)
    val bottomHsv = FloatArray(3)
    val pixelHsv = FloatArray(3)
    val outHsv = FloatArray(3)
    Color.RGBToHSV(top shr 16 and 0xff, top shr 8 and 0xff, top and 0xff, topHsv)
    Color.RGBToHSV(bottom shr 16 and 0xff, bottom shr 8 and 0xff, bottom and 0xff, bottomHsv)
    // Hue turns the short way around.
    var hueSpan = bottomHsv[0] - topHsv[0]
    if (hueSpan > 180f) hueSpan -= 360f
    if (hueSpan < -180f) hueSpan += 360f
    for (y in 0 until h) {
        // The gradient runs top to bottom; each pixel keeps its own light.
        val t = if (h <= 1) 0f else y.toFloat() / (h - 1)
        val hue = (topHsv[0] + hueSpan * t + 360f) % 360f
        val saturation = (topHsv[1] + (bottomHsv[1] - topHsv[1]) * t).coerceIn(0f, 1f)
        for (x in 0 until w) {
            val i = y * w + x
            val pixel = pixels[i]
            Color.RGBToHSV(pixel shr 16 and 0xff, pixel shr 8 and 0xff, pixel and 0xff, pixelHsv)
            outHsv[0] = hue
            outHsv[1] = saturation
            outHsv[2] = pixelHsv[2]
            pixels[i] = Color.HSVToColor(pixel ushr 24, outHsv)
        }
    }
    // Mutable, as a badge is drawn on it after.
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
}
