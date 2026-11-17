package org.fossify.camera.helpers

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Single source of truth for "how is the device held", exposed as Surface.ROTATION_*.
 *
 * Ported from AOSP`s WindowOrientationListener, which is the algorithm the Xiaomi camera relies on
 * (it delegates the screen rotation to the system, so this is what gives it its feel):
 *
 * - a 200 ms low pass filter on the gravity vector, so hand tremor does not matter
 * - tilt angle: 0 = screen vertical (normal use), +90 = screen facing the sky (lying flat),
 *   -90 = screen facing the ground (held overhead in bed)
 * - beyond MAX_TILT (80 degrees, meaning less than 10 degrees away from lying flat) and while the
 *   device is held overhead no orientation is computed at all, the last rotation is kept. This is
 *   what stops the camera from flipping to portrait when it is pointed down at a table.
 * - 45 degrees of hysteresis between adjacent rotations and a per-rotation tilt tolerance
 * - a rotation must settle for 40 ms before it is reported, and is blocked for 300-500 ms after
 *   the device was lying flat, was put down, or was accelerated, which is what makes it sticky
 *
 * Only the gravity vector is used, no magnetometer, so there is no compass dependency.
 */
class OrientationManager private constructor(context: Context) {

    interface Listener {
        fun onDeviceRotationChanged(rotation: Int)
    }

    companion object {
        // 180 / PI, kept as a literal so it can be used in a const initializer
        private const val RADIANS_TO_DEGREES = 57.2957795f

        // the maximum sample inter-arrival time, longer gaps reset the low pass filter
        private const val MAX_FILTER_DELTA_TIME_NANOS = 1000L * 1000L * 1000L

        // the acceleration filter time constant, 100 ms is too small and 500 ms is too large
        private const val FILTER_TIME_CONSTANT_MS = 200.0f

        // tolerated deviation from gravity, we postpone choosing an orientation outside of it
        private const val NEAR_ZERO_MAGNITUDE = 1.0f
        private const val ACCELERATION_TOLERANCE = 4.0f
        private const val MIN_ACCELERATION_MAGNITUDE = 9.81f - ACCELERATION_TOLERANCE
        private const val MAX_ACCELERATION_MAGNITUDE = 9.81f + ACCELERATION_TOLERANCE

        // beyond this absolute tilt we completely ignore orientation data, too unstable there
        private const val MAX_TILT = 80

        // the tilt angle below which we conclude that the device is held overhead and lock it
        private const val TILT_OVERHEAD_ENTER = -40
        private const val TILT_OVERHEAD_EXIT = -15

        // the gap angle between adjacent orientations, creates a dead zone and prevents flapping
        private const val ADJACENT_ORIENTATION_ANGLE_GAP = 45

        // a predicted rotation must be stable for this long before it is reported
        private const val PROPOSAL_SETTLE_TIME_NANOS = 40L * 1000L * 1000L

        // and must not change too quickly after one of these events
        private const val PROPOSAL_MIN_TIME_SINCE_FLAT_ENDED_NANOS = 500L * 1000L * 1000L
        private const val PROPOSAL_MIN_TIME_SINCE_SWING_ENDED_NANOS = 300L * 1000L * 1000L
        private const val PROPOSAL_MIN_TIME_SINCE_ACCELERATION_ENDED_NANOS = 500L * 1000L * 1000L

        // if the tilt stays beyond FLAT_ANGLE for FLAT_TIME_NANOS the device is lying flat
        private const val FLAT_ANGLE = 80
        private const val FLAT_TIME_NANOS = 1000L * 1000L * 1000L

        // a tilt increase of SWING_AWAY_ANGLE_DELTA within SWING_TIME_NANOS means "putting down"
        private const val SWING_AWAY_ANGLE_DELTA = 20
        private const val SWING_TIME_NANOS = 300L * 1000L * 1000L

        private const val TILT_HISTORY_SIZE = 200

        // the tilt angle range for each rotation, the first value applies when facing down and
        // the second one when facing up, unnatural rotations are allowed a smaller range
        private val TILT_TOLERANCE = arrayOf(
            intArrayOf(-25, 70), // ROTATION_0
            intArrayOf(-25, 65), // ROTATION_90
            intArrayOf(-25, 60), // ROTATION_180
            intArrayOf(-25, 65) // ROTATION_270
        )

        @Volatile
        private var instance: OrientationManager? = null

        fun getInstance(context: Context): OrientationManager {
            return instance ?: synchronized(this) {
                instance ?: OrientationManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var handlerThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var sessionListener: SensorEventListener? = null
    private var isRegistered = false

    // all the fields below are only touched from the sensor thread

    private var lastFilteredTimestampNanos = Long.MIN_VALUE
    private var lastFilteredX = 0f
    private var lastFilteredY = 0f
    private var lastFilteredZ = 0f

    private val tiltHistory = FloatArray(TILT_HISTORY_SIZE)
    private val tiltHistoryTimestamps = LongArray(TILT_HISTORY_SIZE) { Long.MIN_VALUE }
    private var tiltHistoryIndex = 1

    private var flatTimestampNanos = Long.MIN_VALUE
    private var swingTimestampNanos = Long.MIN_VALUE
    private var accelerationTimestampNanos = Long.MIN_VALUE

    private var isOverhead = false

    // the rotation we are currently reporting, it doubles as the hysteresis reference
    private var currentRotation = Surface.ROTATION_0

    private var predictedRotation = -1
    private var predictedRotationTimestampNanos = Long.MIN_VALUE

    @Volatile
    var rotation: Int = Surface.ROTATION_0
        private set

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (sessionListener === this) {
                onGravityChanged(event)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        }
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }

        startIfNeeded()
        listener.onDeviceRotationChanged(rotation)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            stop()
        }
    }

    private fun startIfNeeded() {
        if (isRegistered) {
            return
        }

        val sensor = gravitySensor ?: return
        val manager = sensorManager ?: return

        val thread = HandlerThread("OrientationManager").also { it.start() }
        handlerThread = thread
        sensorHandler = Handler(thread.looper)

        resetState()

        // a session token, so events queued by a previous session are ignored
        sessionListener = sensorEventListener

        manager.registerListener(
            sensorEventListener,
            sensor,
            SensorManager.SENSOR_DELAY_GAME,
            sensorHandler
        )
        isRegistered = true
    }

    private fun stop() {
        if (!isRegistered) {
            return
        }

        isRegistered = false
        sessionListener = null
        sensorManager?.unregisterListener(sensorEventListener)
        handlerThread?.quitSafely()
        handlerThread = null
        sensorHandler = null
    }

    private fun onGravityChanged(event: SensorEvent) {
        val now = event.timestamp
        var x = event.values.getOrElse(0) { 0f }
        var y = event.values.getOrElse(1) { 0f }
        var z = event.values.getOrElse(2) { 0f }

        val then = lastFilteredTimestampNanos
        val isSampleSkipped: Boolean

        if (now < then
            || now > then + MAX_FILTER_DELTA_TIME_NANOS
            || (x == 0f && y == 0f && z == 0f)
        ) {
            resetState()
            isSampleSkipped = true
        } else {
            val timeDeltaMS = (now - then) * 0.000001f
            val alpha = timeDeltaMS / (FILTER_TIME_CONSTANT_MS + timeDeltaMS)
            x = alpha * (x - lastFilteredX) + lastFilteredX
            y = alpha * (y - lastFilteredY) + lastFilteredY
            z = alpha * (z - lastFilteredZ) + lastFilteredZ
            isSampleSkipped = false
        }

        lastFilteredTimestampNanos = now
        lastFilteredX = x
        lastFilteredY = y
        lastFilteredZ = z

        if (!isSampleSkipped) {
            processGravity(now, x, y, z)
        }

        val previousRotation = rotation
        if (predictedRotation >= 0 && isPredictedRotationAcceptable(now)) {
            currentRotation = predictedRotation
            rotation = predictedRotation
        }

        if (rotation != previousRotation) {
            dispatchRotation(rotation)
        }
    }

    private fun processGravity(now: Long, x: Float, y: Float, z: Float) {
        val magnitude = sqrt(x * x + y * y + z * z)
        if (magnitude < NEAR_ZERO_MAGNITUDE) {
            // free fall or the sensor was just turned on, we cannot tell which way is up
            clearPredictedRotation()
            return
        }

        if (isAccelerating(magnitude)) {
            accelerationTimestampNanos = now
        }

        // the angle between the up vector and the screen plane, -90..90 degrees
        // -90: horizontal facing the ground, 0: vertical, 90: horizontal facing the sky
        val tiltAngle = (asin((z / magnitude).coerceIn(-1f, 1f)) * RADIANS_TO_DEGREES).roundToInt()
        addTiltHistoryEntry(now, tiltAngle.toFloat())

        if (isFlat(now)) {
            flatTimestampNanos = now
        }
        if (isSwinging(now, tiltAngle.toFloat())) {
            swingTimestampNanos = now
        }

        isOverhead = when {
            tiltAngle <= TILT_OVERHEAD_ENTER -> true
            tiltAngle >= TILT_OVERHEAD_EXIT -> false
            else -> isOverhead
        }

        if (isOverhead || abs(tiltAngle) > MAX_TILT) {
            // cannot be determined reliably, keep whatever we last reported
            clearPredictedRotation()
            return
        }

        // the angle between the x-y projection of the up vector and the +y axis, clockwise
        var orientationAngle = (-atan2(-x, y) * RADIANS_TO_DEGREES).roundToInt()
        if (orientationAngle < 0) {
            orientationAngle += 360
        }

        var nearestRotation = (orientationAngle + 45) / 90
        if (nearestRotation == 4) {
            nearestRotation = 0
        }

        if (isTiltAngleAcceptable(nearestRotation, tiltAngle) &&
            isOrientationAngleAcceptable(nearestRotation, orientationAngle)
        ) {
            updatePredictedRotation(now, nearestRotation)
        } else {
            clearPredictedRotation()
        }
    }

    private fun isTiltAngleAcceptable(rotation: Int, tiltAngle: Int): Boolean {
        val tolerance = TILT_TOLERANCE[rotation]
        return tiltAngle >= tolerance[0] && tiltAngle <= tolerance[1]
    }

    /**
     * Takes the gap between adjacent orientations into account, this is the hysteresis that stops
     * the rotation from flapping around a 45 degree boundary.
     */
    private fun isOrientationAngleAcceptable(rotation: Int, orientationAngle: Int): Boolean {
        if (currentRotation < 0) {
            return true
        }

        if (rotation == currentRotation || rotation == (currentRotation + 1) % 4) {
            val lowerBound =
                rotation * 90 - 45 + ADJACENT_ORIENTATION_ANGLE_GAP / 2
            if (rotation == 0) {
                if (orientationAngle >= 315 && orientationAngle < lowerBound + 360) {
                    return false
                }
            } else if (orientationAngle < lowerBound) {
                return false
            }
        }

        if (rotation == currentRotation || rotation == (currentRotation + 3) % 4) {
            val upperBound =
                rotation * 90 + 45 - ADJACENT_ORIENTATION_ANGLE_GAP / 2
            if (rotation == 0) {
                if (orientationAngle <= 45 && orientationAngle > upperBound) {
                    return false
                }
            } else if (orientationAngle > upperBound) {
                return false
            }
        }

        return true
    }

    private fun isPredictedRotationAcceptable(now: Long): Boolean {
        if (now < predictedRotationTimestampNanos + PROPOSAL_SETTLE_TIME_NANOS) {
            return false
        }
        if (now < flatTimestampNanos + PROPOSAL_MIN_TIME_SINCE_FLAT_ENDED_NANOS) {
            return false
        }
        if (now < swingTimestampNanos + PROPOSAL_MIN_TIME_SINCE_SWING_ENDED_NANOS) {
            return false
        }
        if (now < accelerationTimestampNanos + PROPOSAL_MIN_TIME_SINCE_ACCELERATION_ENDED_NANOS) {
            return false
        }
        return true
    }

    private fun isAccelerating(magnitude: Float): Boolean {
        return magnitude < MIN_ACCELERATION_MAGNITUDE || magnitude > MAX_ACCELERATION_MAGNITUDE
    }

    private fun updatePredictedRotation(now: Long, rotation: Int) {
        if (predictedRotation != rotation) {
            predictedRotation = rotation
            predictedRotationTimestampNanos = now
        }
    }

    private fun clearPredictedRotation() {
        predictedRotation = -1
        predictedRotationTimestampNanos = Long.MIN_VALUE
    }

    private fun addTiltHistoryEntry(now: Long, tilt: Float) {
        tiltHistory[tiltHistoryIndex] = tilt
        tiltHistoryTimestamps[tiltHistoryIndex] = now
        tiltHistoryIndex = (tiltHistoryIndex + 1) % TILT_HISTORY_SIZE
        tiltHistoryTimestamps[tiltHistoryIndex] = Long.MIN_VALUE
    }

    private fun isFlat(now: Long): Boolean {
        var index = tiltHistoryIndex
        while (true) {
            index = nextTiltHistoryIndex(index)
            if (index < 0) {
                return false
            }
            if (tiltHistory[index] < FLAT_ANGLE) {
                return false
            }
            if (tiltHistoryTimestamps[index] + FLAT_TIME_NANOS <= now) {
                // the tilt stayed beyond FLAT_ANGLE for at least FLAT_TIME_NANOS
                return true
            }
        }
    }

    private fun isSwinging(now: Long, tilt: Float): Boolean {
        var index = tiltHistoryIndex
        while (true) {
            index = nextTiltHistoryIndex(index)
            if (index < 0) {
                return false
            }
            if (tiltHistoryTimestamps[index] + SWING_TIME_NANOS < now) {
                return false
            }
            if (tiltHistory[index] + SWING_AWAY_ANGLE_DELTA <= tilt) {
                return true
            }
        }
    }

    private fun nextTiltHistoryIndex(index: Int): Int {
        val nextIndex = (if (index == 0) TILT_HISTORY_SIZE else index) - 1
        return if (tiltHistoryTimestamps[nextIndex] != Long.MIN_VALUE) nextIndex else -1
    }

    private fun resetState() {
        lastFilteredTimestampNanos = Long.MIN_VALUE
        flatTimestampNanos = Long.MIN_VALUE
        swingTimestampNanos = Long.MIN_VALUE
        accelerationTimestampNanos = Long.MIN_VALUE
        isOverhead = false
        clearPredictedRotation()
        tiltHistoryTimestamps[0] = Long.MIN_VALUE
        tiltHistoryIndex = 1
    }

    private fun dispatchRotation(rotation: Int) {
        listeners.forEach { listener ->
            mainHandler.post {
                if (listeners.contains(listener)) {
                    listener.onDeviceRotationChanged(rotation)
                }
            }
        }
    }
}
