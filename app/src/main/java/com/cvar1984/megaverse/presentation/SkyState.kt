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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cvar1984.megaverse.sky.DeviceAim
import com.cvar1984.megaverse.sky.SkyCatalog
import com.cvar1984.megaverse.sky.SkyMath
import com.cvar1984.megaverse.sky.SkyObject
import com.cvar1984.megaverse.sky.SolarLunar
import com.cvar1984.megaverse.sky.julianDayFromEpochMillis

/** Where one object is right now: the object, its direction, and how high it is. */
class Placed(val obj: SkyObject, val enu: DoubleArray, val altDeg: Double, val azDeg: Double)

/**
 * Where everything is, worked out for one instant and held between refreshes. Only
 * the rotation onto the screen is redone per frame, which is what keeps the whole
 * catalogue as cheap to draw as a single object.
 */
class SkySnapshot(
    val millis: Long,
    val jd: Double,
    val lstDeg: Double,
    val latDeg: Double,
    val sunEnu: DoubleArray,
    val placed: List<Placed>,
) {
    fun find(obj: SkyObject): Placed? = placed.firstOrNull { it.obj.id == obj.id }
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
     * The sidereal time the equatorial grid is pinned to while it is being held
     * still. A new position drops the pin, since it makes the old reading wrong.
     */
    var gridLst: Double? = null
        private set

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
            manager.getCurrentLocation(provider, null, context.mainExecutor) { fix ->
                locating = false
                fix?.let { apply(it) }
            }
        }.onFailure { locating = false }
    }

    private fun apply(fix: Location) {
        val first = location == null
        location = fix

        // Declination is measured east-positive, the same convention the frame
        // rotation expects. Taken on the first fix and then left alone unless the
        // horizon frame is set to follow the position: the whole drawn sky hangs
        // off it, and a reference that stays put is easier to read against.
        if (first || Settings.dynAzimuth) {
            declination = GeomagneticField(
                fix.latitude.toFloat(), fix.longitude.toFloat(), fix.altitude.toFloat(), fix.time
            ).declination.toDouble()
        }

        // Stood somewhere else now, so nothing worked out for the old place still
        // answers: the catalogue positions, and the sidereal time the held-still
        // equatorial grid is pinned to.
        sky = null
        gridLst = null
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
        val now = System.currentTimeMillis()
        if (held != null && now - held.millis < refreshMillis) return

        val jd = julianDayFromEpochMillis(now)
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
            now, jd, lstDeg, lat, SkyMath.horizontalToEnu(sunAltAz[1], sunAltAz[0]), placed
        )

        // Pinned on the first reading after a fix, and held until the next one.
        if (gridLst == null) gridLst = lstDeg
    }
}
