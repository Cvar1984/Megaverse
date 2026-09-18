package com.cvar1984.megaverse.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.os.CancellationSignal
import androidx.core.util.Consumer
import androidx.core.location.LocationManagerCompat
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cvar1984.megaverse.sky.DeviceAim
import com.cvar1984.megaverse.sky.SkyCatalog
import com.cvar1984.megaverse.sky.SkyMath
import com.cvar1984.megaverse.sky.SkyObject
import com.cvar1984.megaverse.sky.SolarLunar
import com.cvar1984.megaverse.sky.julianDayFromEpochMillis

/**
 * How far from the present the sky may be moved, either way: ten million years.
 *
 * Not a fence, and not where the astronomy stops being any good - that is nearer a
 * century, and it is written down under Accuracy rather than enforced here. This is
 * only the place the arithmetic would stop being arithmetic. A Long of milliseconds
 * wraps eventually, and an offset that wrapped would throw the sky to the far side
 * of the epoch mid-turn, which reads as a crash rather than as a limit. So the
 * offset saturates instead, several thousand times short of where Long gives out.
 *
 * At thirty days a detent and fifty detents a second - faster than a wrist turns -
 * this is four weeks of unbroken spinning away. A hundred thousand years, which was
 * the first number here, is under seven hours of it, which is not the same as out of
 * reach. Nobody arrives here, and nothing stops them before it.
 *
 * The clamp lives in [SkyState.travel] alone, so the screens doing the moving cannot
 * disagree with it: they ask for a jump, and read back what they actually got.
 */
const val TRAVEL_LIMIT_MILLIS = 10_000_000L * 365 * 24 * 60 * 60 * 1000

/**
 * [offsetMillis] plus [byMillis], stopping at the ends rather than wrapping past them.
 *
 * Math.addExact is the one that knows whether an add overflowed, which is not
 * something worth working out by hand for the sake of avoiding an exception that is
 * never thrown in practice. Which end it stops at comes off the direction asked for,
 * since by the time it has overflowed the sum itself no longer says.
 */
internal fun saturatingAdd(offsetMillis: Long, byMillis: Long): Long =
    try {
        Math.addExact(offsetMillis, byMillis)
    } catch (overflow: ArithmeticException) {
        if (byMillis > 0) Long.MAX_VALUE else Long.MIN_VALUE
    }

/** Where one object is right now: the object, its direction, and how high it is. */
class Placed(val obj: SkyObject, val enu: DoubleArray, val altDeg: Double, val azDeg: Double)

/**
 * Where everything is, worked out for one instant and held between refreshes. Only
 * the rotation onto the screen is redone per frame, which is what keeps the whole
 * catalogue as cheap to draw as a single object.
 *
 * [millis] is the instant this shows, which is the present plus [offsetMillis] and
 * not necessarily now. The offset is carried along so that a refresh can tell a
 * snapshot that has merely aged from one that is of a different time altogether.
 */
class SkySnapshot(
    val millis: Long,
    val offsetMillis: Long,
    val jd: Double,
    val lstDeg: Double,
    val latDeg: Double,
    val sunEnu: DoubleArray,
    val placed: List<Placed>,
) {
    fun find(obj: SkyObject): Placed? = placed.firstOrNull { it.obj.id == obj.id }

    /**
     * Just the directions, for the nearest-object search. Held with the snapshot
     * rather than rebuilt per frame: the search runs on every sensor sample and the
     * list only changes when the catalogue is worked out again.
     */
    val directions: List<DoubleArray> by lazy(LazyThreadSafetyMode.NONE) { placed.map { it.enu } }
}

/**
 * The two live feeds the sky screen runs on: which way the watch is pointing, and
 * where on Earth it is standing.
 *
 * The Garmin build had to assemble the pointing frame itself out of a raw
 * accelerometer and magnetometer, flatten the field against gravity, and recover
 * the local magnetic declination by watching the system compass while the watch was
 * near level. Android ships all of that: TYPE_ROTATION_VECTOR is the fused,
 * gyro-stabilised attitude, SensorManager turns it into the same East-North-Up
 * matrix by the same construction, and GeomagneticField gives the declination
 * outright from the position rather than by inference.
 */
class SkyState(private val context: Context) : SensorEventListener {
    /** Whole-catalogue positions are worked out at most this often. */
    private val refreshMillis = 5_000L

    /** How long to leave a fix that came back with nothing before asking again. */
    private val retryMillis = 15_000L

    private val sensors = context.getSystemService(SensorManager::class.java)

    // The fused attitude first, then the non-gyro version for a watch without one.
    private val attitude: Sensor? =
        sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensors?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    /** A watch with no compass can never aim, however long the fix takes. */
    val hasCompass = attitude != null

    /** The world's axes in the watch's own coordinates, swung onto true north. */
    var frame by mutableStateOf<FloatArray?>(null)
        private set

    var location by mutableStateOf<Location?>(null)
        private set

    var sky by mutableStateOf<SkySnapshot?>(null)
        private set

    /** True while an active fix is being waited on, for the status line. */
    var locating by mutableStateOf(false)
        private set

    /**
     * Held as state rather than asked for on each draw, so that granting the
     * permission redraws the screen that was waiting on it.
     */
    var locationAllowed by mutableStateOf(hasLocationPermission())
        private set

    /**
     * How far the sky being shown is from the present, in milliseconds. Zero is now,
     * which is what it is almost always set to.
     *
     * Deliberately not a setting and deliberately not persisted. A watch that came
     * back up showing last Tuesday's sky, with nothing on screen to say why, would
     * simply look broken; the offset is a place you have gone rather than a
     * preference you hold, so it lives as long as the app does and no longer.
     *
     * Free to run: the only bound is [TRAVEL_LIMIT_MILLIS], which is where the
     * arithmetic gives out rather than where the answers do. Where the answers give
     * out is a separate matter and a much nearer one - the planetary elements are
     * Schlyter's, stated for 1900 to 2100, and the lunar series drifts as you leave
     * its epoch - but that is a thing to know about a reading, not a reason to stop
     * a wrist mid-turn.
     */
    var timeOffsetMillis by mutableLongStateOf(0L)
        private set

    /**
     * Which of [STEPS] a tap or a turn of the crown moves by.
     *
     * Held here rather than on the screen that sets it, because it is not that
     * screen's business: the crown moves the sky from the sky screen too, and the
     * step you last chose is the step you meant on both.
     */
    var travelStep by mutableIntStateOf(DEFAULT_STEP)

    /**
     * Moves the sky to [offsetMillis] from now, clamped to the range the maths is
     * good for.
     */
    fun travel(offsetMillis: Long) {
        timeOffsetMillis = offsetMillis.coerceIn(-TRAVEL_LIMIT_MILLIS, TRAVEL_LIMIT_MILLIS)

        // Worked out here and now rather than left to the one-second tick that keeps
        // the real sky up to date. That tick is right for a sky that moves a
        // fifteenth of a degree in a second and wrong for one being dragged by a
        // wrist: a turn of the crown would change this number immediately and move
        // nothing on screen until the tick came round, so a spin arrived as a single
        // jump up to a second late with every position in between thrown away. That
        // is the whole of what made travelling feel slow, and it was never the size
        // of the step.
        refreshSky()
    }

    /**
     * Moves the sky by [byMillis] from wherever it already is.
     *
     * The crown calls this for every detent of a spin, so it goes through
     * [saturatingAdd] rather than adding directly: an offset near the limit would
     * otherwise wrap to the far side of the epoch mid-turn, and [travel] would then
     * be clamping a number that had already become nonsense.
     */
    fun travelBy(byMillis: Long) = travel(saturatingAdd(timeOffsetMillis, byMillis))

    private var declination = 0.0
    private var running = false
    private var lastFixAt = 0L
    private val rotationMatrix = FloatArray(9)

    fun start() {
        if (running) return
        running = true
        attitude?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        ensureLocation()
    }

    /**
     * Asks for a position unless one has already arrived. Every screen that places
     * anything needs this, and the calendar can be reached from the menu without
     * passing through a sky screen, so it cannot be left to whichever screen
     * happens to start the sensors.
     */
    fun ensureLocation() {
        locationAllowed = hasLocationPermission()
        if (location == null) requestLocation(force = true)
    }

    /** Called once the permission dialog has been answered, either way. */
    fun onPermissionResult() {
        locationAllowed = hasLocationPermission()
        if (locationAllowed) requestLocation(force = true)
    }

    fun stop() {
        if (!running) return
        running = false
        sensors?.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        // Some devices report five values where the matrix call only accepts four.
        val values =
            if (event.values.size > 4) event.values.copyOf(4) else event.values
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        // A fresh array each time: Compose compares by reference, and a matrix
        // rewritten in place would never look like a change.
        frame = DeviceAim.applyDeclination(rotationMatrix.copyOf(), declination)
    }

    fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Asks for the position again once the interval has run out. One shot rather
     * than a continuous feed: the setting asks for a fix every few minutes, and
     * leaving the receiver streaming between them would cost far more battery.
     */
    fun refreshLocation() {
        if (locating) return
        val since = System.currentTimeMillis() - lastFixAt

        // A first attempt that came back with nothing is not a reason to stop
        // asking. "One fix only" means stop once there is one, not give up if the
        // first one misses - and indoors the first one usually does.
        if (location == null) {
            if (since >= retryMillis) requestLocation(force = true)
            return
        }

        val minutes = Settings.locationMinutes
        if (minutes <= 0) return
        if (since < minutes * 60_000L) return
        requestLocation(force = false)
    }

    // The permission is checked on the first line, and every call is wrapped in
    // runCatching so a permission revoked between the check and the call is caught
    // rather than crashing. Lint follows neither the helper nor runCatching, so it
    // has to be told.
    @android.annotation.SuppressLint("MissingPermission")
    private fun requestLocation(force: Boolean) {
        if (!hasLocationPermission()) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        lastFixAt = System.currentTimeMillis()

        // Whatever fix is already cached, first. The sky only needs to know which
        // kilometre you are standing in, so a stale fix answers while a fresh one is
        // still being acquired.
        if (force && location == null) {
            manager.allProviders
                .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { apply(it) }
        }

        val provider = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return

        locating = location == null
        runCatching {
            // The compat forms rather than the framework ones: getCurrentLocation
            // landed in API 30 and getMainExecutor in 28, and both are backported
            // here to the oldest watch Wear Compose will run on.
            LocationManagerCompat.getCurrentLocation(
                manager,
                provider,
                // Typed, because the two overloads differ only in which
                // CancellationSignal they take and a bare null picks neither.
                null as CancellationSignal?,
                ContextCompat.getMainExecutor(context),
                Consumer<Location?> { fix ->
                    locating = false
                    fix?.let { apply(it) }
                },
            )
        }.onFailure { locating = false }
    }

    private fun apply(fix: Location) {
        location = fix

        // Declination is measured east-positive, the same convention the frame
        // rotation expects, and it is taken from every fix. It is a property of
        // where you are standing: holding on to the one worked out where you last
        // stood does not keep the sky steady, it aims the whole of it slightly wrong.
        declination = GeomagneticField(
            fix.latitude.toFloat(), fix.longitude.toFloat(), fix.altitude.toFloat(), fix.time
        ).declination.toDouble()

        // Stood somewhere else now, so the catalogue positions worked out for the
        // old place no longer answer.
        sky = null
    }

    /**
     * Works the catalogue out again if it is due. The sky turns 15 degrees an hour,
     * so five seconds moves it a small fraction of a pixel, while running the
     * orbital maths for 35 objects at frame rate would cost far more than drawing
     * them does.
     */
    fun refreshSky() {
        val fix = location ?: return
        val held = sky
        val shown = System.currentTimeMillis() + timeOffsetMillis
        // Two reasons to work it out again, and they are different reasons: the held
        // one has aged out, or it is of another time entirely. Comparing the instants
        // alone would miss a jump backwards, which reads as a snapshot from the
        // future and so as one that is not due yet.
        if (held != null && held.offsetMillis == timeOffsetMillis &&
            shown - held.millis < refreshMillis
        ) return

        val jd = julianDayFromEpochMillis(shown)
        val lat = fix.latitude
        val lstDeg = SkyMath.lst(jd, fix.longitude)

        val sunRaDec = SolarLunar.sunPosition(jd)
        val sunAltAz = SkyMath.raDecToAltAz(sunRaDec[0], sunRaDec[1], lat, lstDeg)

        val placed = SkyCatalog.objects.map { obj ->
            val raDec = SkyCatalog.raDec(obj, jd)
            val altAz = SkyMath.raDecToAltAz(raDec[0], raDec[1], lat, lstDeg)
            // raDecToAltAz answers for Earth's centre; correct it to the watch on
            // the surface, and for the atmosphere bending the view.
            val alt = SkyMath.apparentAltitude(altAz[0], SkyCatalog.horizontalParallax(obj, jd))
            Placed(obj, SkyMath.horizontalToEnu(altAz[1], alt), alt, altAz[1])
        }

        sky = SkySnapshot(
            shown, timeOffsetMillis, jd, lstDeg, lat,
            SkyMath.horizontalToEnu(sunAltAz[1], sunAltAz[0]), placed,
        )
    }
}
