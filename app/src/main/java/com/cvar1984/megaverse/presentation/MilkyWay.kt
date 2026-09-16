package com.cvar1984.megaverse.presentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.cvar1984.megaverse.sky.Galactic

/**
 * The Milky Way, drawn per pixel on the GPU.
 *
 * Every other overlay in this app projects a handful of points forward onto the
 * glass. This one has to go the other way - for each pixel, which way is that
 * looking - and there are 147,000 of them a frame. That inverse is a divide and
 * three dot products per pixel, which is nothing on a GPU and hopeless on a watch
 * CPU, so it is the one thing here that is a shader.
 *
 * It is computed rather than sampled. A photographic panorama would mean shipping
 * a few hundred kilobytes and would still have to be unprojected the same way; the
 * band's brightness is a smooth function of galactic latitude and longitude, so the
 * function is the texture. Swapping in a real image later means adding one
 * `uniform shader` and sampling it where [brightness] is called - the rest of the
 * plumbing does not change.
 *
 * RuntimeShader arrived in API 33, so watches below it simply do not draw this.
 * Nothing else about the screen depends on it.
 */
object MilkyWay {
    /**
     * Required credit for the plate, under the terms it is published on. Shown in
     * the settings list rather than buried in a licence file, because CC BY asks
     * for the credit to stay with the image and be readable where it is used.
     */
    const val CREDIT = "Milky Way plate: ESO/S. Brunier, CC BY 4.0"

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private const val SOURCE = """
uniform float2 uCentre;      // where the aim lands on the glass
uniform float  uFocal;       // pixels per radian, as the rest of the screen uses
uniform float3 uRow0;        // watch axes straight to galactic, a row each
uniform float3 uRow1;
uniform float3 uRow2;
uniform float2 uTexSize;     // the panorama, in pixels
uniform float  uGain;        // how loud the band is drawn
uniform shader uSky;         // the panorama itself

half4 main(float2 frag) {
    // Undo the perspective divide. This is the one place in the app that runs the
    // projection backwards, and the only reason any of this is a shader: there are
    // 147,000 pixels a frame and each needs to know which way it is looking. The
    // aim is out through the back of the case, hence the -1.
    float2 d = frag - uCentre;
    float3 ray = normalize(float3(d.x / uFocal, -d.y / uFocal, -1.0));
    float3 g = float3(dot(uRow0, ray), dot(uRow1, ray), dot(uRow2, ray));

    // Galactic longitude and latitude, then the panorama's own mapping: the centre
    // of the plate is the galactic centre and longitude runs leftwards across it.
    float l = degrees(atan(g.y, g.x));
    float b = degrees(asin(clamp(g.z, -1.0, 1.0)));
    float u = fract(0.5 - l / 360.0);
    float v = clamp(0.5 - b / 180.0, 0.0, 1.0);

    half4 sky = uSky.eval(float2(u * uTexSize.x, v * uTexSize.y));

    // The plate's empty sky is not black - it sits around 0.03 - and its band runs
    // from about 0.19 out in Cygnus to 0.53 over the bulge. Scaling that straight
    // down would wash the whole watch face grey, and a power curve would crush the
    // fainter half of the band away. So the floor is lifted off first and the top
    // of the band is taken as white, which is the range actually worth showing.
    float lum = dot(float3(sky.r, sky.g, sky.b), float3(0.30, 0.59, 0.11));
    float a = clamp((lum - 0.035) / 0.515, 0.0, 1.0);
    a = pow(a, 0.9) * uGain;

    // Kept close to the plate's own colour rather than tinted, so the dust lanes
    // stay warm and the star clouds stay cold, the way the photograph has them.
    return half4(sky.r * a, sky.g * a, sky.b * a, a);
}
"""

    private var compiled: RuntimeShader? = null
    private var plate: BitmapShader? = null
    private var plateSize = floatArrayOf(1f, 1f)

    /**
     * The panorama, decoded once and kept for as long as the setting is on.
     *
     * 565 rather than full colour: it is a diffuse glow, the extra bits buy nothing
     * anyone can see at this size, and it halves what the watch has to hold.
     * Repeated across, because longitude wraps; clamped down the sides, because
     * latitude stops at the poles.
     */
    private fun plate(context: Context): BitmapShader = plate ?: run {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bitmap = BitmapFactory.decodeResource(
            context.resources, com.cvar1984.megaverse.R.drawable.milkyway, options
        )
        plateSize = floatArrayOf(bitmap.width.toFloat(), bitmap.height.toFloat())
        BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
            .also { plate = it }
    }

    /** Compiled once and kept; the source never changes, only its uniforms do. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun shader(): RuntimeShader =
        compiled ?: RuntimeShader(SOURCE).also { compiled = it }

    /**
     * Fills the whole canvas with the band as seen through [frame].
     *
     * [gain] is how far up it is turned: the real thing is faint, and this sits
     * under the grids and every object, so it has to read without drowning them.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun DrawScope.drawMilkyWay(
        context: Context,
        frame: FloatArray,
        latDeg: Double,
        lstDeg: Double,
        view: FloatArray,
        gain: Float,
    ) {
        val m = Galactic.fromDevice(frame, latDeg, lstDeg)
        val sky = plate(context)
        val shader = shader()
        shader.setInputShader("uSky", sky)
        shader.setFloatUniform("uTexSize", plateSize[0], plateSize[1])
        shader.setFloatUniform("uCentre", view[0], view[1])
        shader.setFloatUniform("uFocal", view[2])
        shader.setFloatUniform("uRow0", m[0], m[1], m[2])
        shader.setFloatUniform("uRow1", m[3], m[4], m[5])
        shader.setFloatUniform("uRow2", m[6], m[7], m[8])
        shader.setFloatUniform("uGain", gain)
        drawRect(ShaderBrush(shader))
    }
}
