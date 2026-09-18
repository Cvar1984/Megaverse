package com.cvar1984.megaverse.presentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.cvar1984.megaverse.R
import com.cvar1984.megaverse.sky.SkyObject
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The real faces of the Sun, Moon and planets, wrapped onto a sphere.
 *
 * The maps are flat rectangles - longitude across, latitude down - and what the
 * screen wants is a disc. So each is warped once into a little round sprite the
 * exact size it will be drawn at, and from then on drawing a planet is drawing one
 * bitmap. The warp is the orthographic projection of a sphere: for every pixel of
 * the disc, which point of the surface is facing you there.
 *
 * Doing it this way rather than per pixel on the GPU is what keeps this working on
 * every watch the app runs on. [MilkyWay] has to be a shader because it covers the
 * whole screen and changes with every wrist movement; a planet is twenty pixels
 * across and its face does not change, so it is baked once and cached.
 *
 * What is deliberately not modelled: rotation and libration. The sub-observer
 * longitude really does drift, but at these radii a whole turn of Jupiter moves the
 * belts by less than a pixel of anything anyone could see. The Moon needs no choice
 * at all - it is locked to us, so the near side the map is centred on is the side
 * genuinely facing the watch.
 */
object PlanetTexture {
    /**
     * Required credit for the maps, under the terms they are published on. Shown in
     * the settings list beside the Milky Way's, because CC BY asks for the credit to
     * stay with the image and be readable where it is used.
     */
    const val CREDIT = "Planet maps: Solar System Scope, CC BY 4.0"

    /** Which map belongs to which body. Anything absent is drawn as a plain disc. */
    private val maps = mapOf(
        "sun" to R.drawable.tex_sun,
        "moon" to R.drawable.tex_moon,
        "mercury" to R.drawable.tex_mercury,
        "venus" to R.drawable.tex_venus,
        "mars" to R.drawable.tex_mars,
        "jupiter" to R.drawable.tex_jupiter,
        "saturn" to R.drawable.tex_saturn,
    )

    /**
     * Baked sprites, kept for the life of the process: at most one per body per
     * size, and the size only changes if the screen does. Seven discs of a few
     * hundred pixels each, so nothing worth evicting.
     */
    private val sprites = HashMap<Long, ImageBitmap>()

    /** The sphere for [obj] at [diameter] pixels across, or null if it has no map. */
    fun sphere(context: Context, obj: SkyObject, diameter: Int): ImageBitmap? {
        val res = maps[obj.id] ?: return null
        val key = (res.toLong() shl 20) or diameter.toLong()
        return sprites[key] ?: bake(context, res, diameter).also { sprites[key] = it }
    }

    /**
     * Warps one map into a disc [d] pixels across.
     *
     * The map is scaled down near the sprite's own size first. A 256-wide map across
     * a 20-pixel disc means one pixel covers a dozen texels, and picking one of the
     * dozen gives a speckled mess; letting the scaler average them first is what
     * makes the belts read as belts. Four times over is enough headroom for the
     * middle of the disc, where the surface is face-on and least compressed.
     */
    private fun bake(context: Context, res: Int, d: Int): ImageBitmap {
        val full = BitmapFactory.decodeResource(context.resources, res)
        val wide = (4 * d).coerceIn(16, full.width)
        val src =
            if (wide < full.width) Bitmap.createScaledBitmap(full, wide, wide / 2, true) else full
        val sw = src.width
        val sh = src.height
        val texels = IntArray(sw * sh)
        src.getPixels(texels, 0, sw, 0, 0, sw, sh)
        if (src !== full) full.recycle()

        val out = IntArray(d * d)
        val r = d / 2f
        for (py in 0 until d) {
            // Screen y runs down and north is up, so the vertical part of the surface
            // normal is negated: the top row of the sprite is the north pole.
            val ny = (py + 0.5f - r) / r
            for (px in 0 until d) {
                val nx = (px + 0.5f - r) / r
                val q = nx * nx + ny * ny

                // How much of this pixel the disc covers, so the rim is a clean edge
                // rather than a staircase. At twenty pixels across a hard edge shows.
                val cover = ((1f - sqrt(q)) * r + 0.5f).coerceIn(0f, 1f)
                if (cover <= 0f) continue

                // The third component of the surface normal: towards the viewer.
                val nz = sqrt((1f - q).coerceAtLeast(0f))
                val lat = asin((-ny).toDouble().coerceIn(-1.0, 1.0))
                val lon = atan2(nx.toDouble(), nz.toDouble())

                val tx = ((0.5 + lon / (2 * Math.PI)) * sw).toInt().coerceIn(0, sw - 1)
                val ty = ((0.5 - lat / Math.PI) * sh).toInt().coerceIn(0, sh - 1)
                val c = texels[ty * sw + tx]

                // Premultiplied, which is how Android reads an ARGB_8888 bitmap. Only
                // the rim is part-transparent, but skipping this haloes it.
                val a = (cover * 255f).toInt()
                val red = ((c shr 16 and 0xFF) * a) / 255
                val green = ((c shr 8 and 0xFF) * a) / 255
                val blue = ((c and 0xFF) * a) / 255
                out[py * d + px] = (a shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Bitmap.createBitmap(out, d, d, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}
