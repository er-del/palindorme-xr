package com.example.xr.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.example.xr.model.TrackingSourceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

enum class ActiveTrackingSource(val label: String) {
    GAME_ROTATION_VECTOR("Game Rotation Vector (3-DOF, Magnet-Safe)"),
    ROTATION_VECTOR("Rotation Vector (3-DOF + Compass)"),
    ACCEL_MAG_FUSION("Accelerometer + Magnetometer (3-DOF)"),
    ARCORE_SIX_DOF("ARCore Visual-Inertial (6-DOF)"),
    MANUAL_FALLBACK("Touch / Gamepad Fallback (No Gyro)")
}

data class HeadPoseSnapshot(
    val orientation: Quat = Quat.IDENTITY,
    val position: Vec3 = Vec3.ZERO,
    val angularVelocityRad: Vec3 = Vec3.ZERO,
    val timestampNs: Long = 0L,
    val source: ActiveTrackingSource = ActiveTrackingSource.MANUAL_FALLBACK,
    val isSixDof: Boolean = false
)

data class TrackingTelemetry(
    val source: ActiveTrackingSource = ActiveTrackingSource.MANUAL_FALLBACK,
    val sampleRateHz: Float = 0f,
    val hasGyroscope: Boolean = false,
    val hasGameRotationVector: Boolean = false,
    val predictionMs: Float = 25f,
    val isSixDofActive: Boolean = false,
    val linearAccelerationG: Float = 0f,
    val displayRotationDegrees: Int = 90
)

class HeadTrackingEngine(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private val latestSnapshot = AtomicReference(HeadPoseSnapshot())
    private val poseHistory = PoseHistoryBuffer(capacity = 128)
    private val _telemetry = MutableStateFlow(TrackingTelemetry())
    val telemetry: StateFlow<TrackingTelemetry> = _telemetry.asStateFlow()

    @Volatile
    private var yawOffsetRad: Float = 0f
    @Volatile
    private var manualYawDeg: Float = 0f
    @Volatile
    private var manualPitchDeg: Float = 0f
    @Volatile
    private var predictionMs: Float = 25f
    @Volatile
    private var requestedMode: TrackingSourceMode = TrackingSourceMode.AUTO
    @Volatile
    private var displayRotation: Int = Surface.ROTATION_90
    @Volatile
    private var arCorePoseOverride: Pair<Quat, Vec3>? = null
    @Volatile
    private var arCorePoseTimestampNs: Long = 0L

    private var rawLastOrientation = Quat.IDENTITY
    private var lastSampleTimeNs = 0L
    private var estimatedSampleRateHz = 0f
    private var sampleCounter = 0
    private var rateWindowStartNs = 0L

    private val orientationFilter = QuaternionOneEuroFilter(minCutoff = 26.0f, beta = 0.90f)
    private val angVelFilter = Vec3OneEuroFilter(minCutoff = 2.5f, beta = 0.1f)
    private val accelValues = FloatArray(3)
    private val magValues = FloatArray(3)
    private val rotMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false

    private var activeSensorSource: ActiveTrackingSource = ActiveTrackingSource.MANUAL_FALLBACK

    fun start(mode: TrackingSourceMode = TrackingSourceMode.AUTO) {
        requestedMode = mode
        stop()

        val hasGyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        val gameRot = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val rotVec = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val linAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        val thread = HandlerThread("HeadTrackingSensorThread").apply { start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        sensorHandler = handler

        when {
            mode == TrackingSourceMode.MANUAL_ONLY -> {
                activeSensorSource = ActiveTrackingSource.MANUAL_FALLBACK
            }
            mode == TrackingSourceMode.AUTO && gameRot != null -> {
                sensorManager.registerListener(this, gameRot, SensorManager.SENSOR_DELAY_FASTEST, handler)
                activeSensorSource = ActiveTrackingSource.GAME_ROTATION_VECTOR
            }
            rotVec != null -> {
                sensorManager.registerListener(this, rotVec, SensorManager.SENSOR_DELAY_FASTEST, handler)
                activeSensorSource = ActiveTrackingSource.ROTATION_VECTOR
            }
            accel != null && mag != null -> {
                sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, handler)
                sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME, handler)
                activeSensorSource = ActiveTrackingSource.ACCEL_MAG_FUSION
            }
            else -> {
                activeSensorSource = ActiveTrackingSource.MANUAL_FALLBACK
            }
        }

        if (linAccel != null) {
            sensorManager.registerListener(this, linAccel, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }

        _telemetry.value = _telemetry.value.copy(
            source = activeSensorSource,
            hasGyroscope = hasGyro,
            hasGameRotationVector = gameRot != null,
            predictionMs = predictionMs
        )
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
        orientationFilter.reset()
        angVelFilter.reset()
        poseHistory.clear()
        lastSampleTimeNs = 0L
    }

    fun setDisplayRotation(surfaceRotation: Int) {
        displayRotation = surfaceRotation
        val deg = when (surfaceRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        _telemetry.value = _telemetry.value.copy(displayRotationDegrees = deg)
    }

    fun setPredictionMs(ms: Float) {
        predictionMs = ms.coerceIn(0f, 45f)
        _telemetry.value = _telemetry.value.copy(predictionMs = predictionMs)
    }

    fun setTrackingMode(mode: TrackingSourceMode) {
        if (mode != requestedMode) {
            start(mode)
        }
    }

    fun recenter() {
        val currentRaw = rawLastOrientation
        yawOffsetRad = -currentRaw.extractYawRadians()
        manualYawDeg = 0f
        manualPitchDeg = 0f
        publishPose(currentRaw, Vec3.ZERO, SystemClock.elapsedRealtimeNanos())
    }

    fun applyManualLookDelta(deltaYawDeg: Float, deltaPitchDeg: Float) {
        manualYawDeg = (manualYawDeg + deltaYawDeg) % 360f
        manualPitchDeg = (manualPitchDeg + deltaPitchDeg).coerceIn(-80f, 80f)
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (activeSensorSource == ActiveTrackingSource.MANUAL_FALLBACK) {
            val q = Quat.fromEulerDegrees(manualYawDeg, manualPitchDeg, 0f)
            rawLastOrientation = q
            val snap = HeadPoseSnapshot(
                orientation = q,
                position = Vec3.ZERO,
                angularVelocityRad = Vec3.ZERO,
                timestampNs = nowNs,
                source = ActiveTrackingSource.MANUAL_FALLBACK,
                isSixDof = false
            )
            latestSnapshot.set(snap)
            poseHistory.record(nowNs, q, Vec3.ZERO)
        } else {
            publishPose(rawLastOrientation, Vec3.ZERO, nowNs)
        }
    }

    fun updateArCoreSixDofPose(orientation: Quat?, position: Vec3?) {
        if (orientation != null && position != null) {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            arCorePoseOverride = orientation to position
            arCorePoseTimestampNs = nowNs
            val yawOffset = Quat.fromAxisAngle(Vec3.UP, yawOffsetRad)
            val manualOffset = Quat.fromEulerDegrees(manualYawDeg, manualPitchDeg, 0f)
            val worldQ = (yawOffset * orientation * manualOffset).normalized()
            poseHistory.record(nowNs, worldQ, position)
        } else {
            arCorePoseOverride = null
        }
    }

    /**
     * Lock-free read from the GL thread. Applies motion-to-photon prediction without allocations.
     */
    fun getPredictedPose(): HeadPoseSnapshot {
        val arOverride = arCorePoseOverride
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (arOverride != null && (nowNs - arCorePoseTimestampNs) < 250_000_000L) {
            val yawOffset = Quat.fromAxisAngle(Vec3.UP, yawOffsetRad)
            val manualOffset = Quat.fromEulerDegrees(manualYawDeg, manualPitchDeg, 0f)
            return HeadPoseSnapshot(
                orientation = (yawOffset * arOverride.first * manualOffset).normalized(),
                position = arOverride.second,
                angularVelocityRad = Vec3.ZERO,
                timestampNs = arCorePoseTimestampNs,
                source = ActiveTrackingSource.ARCORE_SIX_DOF,
                isSixDof = true
            )
        }

        val snap = latestSnapshot.get()
        val ageSec = ((nowNs - snap.timestampNs) * 1e-9f).coerceIn(0f, 0.045f)
        val lookaheadSec = (predictionMs * 0.001f) + ageSec * 0.5f
        val predicted = Quat.predictOrientation(snap.orientation, snap.angularVelocityRad, lookaheadSec)
        return snap.copy(orientation = predicted)
    }

    /**
     * Returns the interpolated head pose at a specific camera frame capture timestamp
     * (in elapsedRealtimeNanos) so hands stay world-locked during head rotation.
     */
    fun getPoseAtTimestamp(timestampNs: Long): HeadPoseSnapshot {
        val current = latestSnapshot.get()
        val sample = poseHistory.getPoseAt(timestampNs) ?: return current
        return current.copy(
            orientation = sample.orientation,
            position = sample.position,
            timestampNs = sample.timestampNs
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        val nowNs = event.timestamp
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                val (axisX, axisY) = when (displayRotation) {
                    Surface.ROTATION_0 -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    else -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X // ROTATION_90 landscape-left
                }
                SensorManager.remapCoordinateSystem(rotMatrix, axisX, axisY, remappedMatrix)
                val worldQuat = androidRemappedMatrixToGlQuat(remappedMatrix)
                processOrientationSample(worldQuat, nowNs)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelValues, 0, 3)
                hasAccel = true
                if (hasMag) computeAccelMagFusion(nowNs)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magValues, 0, 3)
                hasMag = true
                if (hasAccel) computeAccelMagFusion(nowNs)
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val mag = kotlin.math.sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                )
                val gForce = mag / 9.81f
                _telemetry.value = _telemetry.value.copy(linearAccelerationG = gForce)
            }
        }
    }

    /**
     * Converts a 3x3 Android rotation matrix (from remapCoordinateSystem) into a head-to-GL-world Quat.
     * Android world rows: 0=East, 1=North, 2=Up -> GL world rows: 0=East, 1=Up, 2=-North.
     */
    private fun androidRemappedMatrixToGlQuat(r: FloatArray): Quat {
        val m00 = r[0]; val m01 = r[1]; val m02 = r[2]
        val m10 = r[6]; val m11 = r[7]; val m12 = r[8]
        val m20 = -r[3]; val m21 = -r[4]; val m22 = -r[5]
        val trace = m00 + m11 + m22
        return if (trace > 0f) {
            val s = kotlin.math.sqrt(trace + 1f) * 2f
            Quat((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s).normalized()
        } else if (m00 > m11 && m00 > m22) {
            val s = kotlin.math.sqrt(1f + m00 - m11 - m22) * 2f
            Quat(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s).normalized()
        } else if (m11 > m22) {
            val s = kotlin.math.sqrt(1f + m11 - m00 - m22) * 2f
            Quat((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s).normalized()
        } else {
            val s = kotlin.math.sqrt(1f + m22 - m00 - m11) * 2f
            Quat((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s).normalized()
        }
    }

    private fun computeAccelMagFusion(nowNs: Long) {
        if (SensorManager.getRotationMatrix(rotMatrix, null, accelValues, magValues)) {
            SensorManager.getOrientation(rotMatrix, orientationAngles)
            val yawDeg = Math.toDegrees(-orientationAngles[0].toDouble()).toFloat()
            val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
            val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
            processOrientationSample(Quat.fromEulerDegrees(yawDeg, pitchDeg, rollDeg), nowNs)
        }
    }

    private fun processOrientationSample(rawQuat: Quat, timestampNs: Long) {
        val smoothedQuat = orientationFilter.filter(rawQuat, timestampNs)
        val dt = if (lastSampleTimeNs > 0L) ((timestampNs - lastSampleTimeNs) * 1e-9f) else 0f
        var filteredVel = Vec3.ZERO
        if (dt in 0.0005f..0.1f) {
            val delta = smoothedQuat * rawLastOrientation.conjugate()
            val sinHalf = kotlin.math.sqrt(delta.x * delta.x + delta.y * delta.y + delta.z * delta.z)
            if (sinHalf > 1e-5f) {
                val angle = 2f * kotlin.math.atan2(sinHalf, abs(delta.w))
                val sign = if (delta.w < 0f) -1f else 1f
                val axis = Vec3(delta.x / sinHalf, delta.y / sinHalf, delta.z / sinHalf) * sign
                val rawVel = axis * (angle / dt)
                filteredVel = angVelFilter.filter(rawVel, timestampNs)
            }
        }
        rawLastOrientation = smoothedQuat
        lastSampleTimeNs = timestampNs
        publishPose(smoothedQuat, filteredVel, timestampNs)

        sampleCounter++
        if (rateWindowStartNs == 0L) rateWindowStartNs = timestampNs
        val windowElapsed = timestampNs - rateWindowStartNs
        if (windowElapsed >= 500_000_000L) {
            estimatedSampleRateHz = sampleCounter * (1e9f / windowElapsed)
            sampleCounter = 0
            rateWindowStartNs = timestampNs
            _telemetry.value = _telemetry.value.copy(
                source = if (arCorePoseOverride != null) ActiveTrackingSource.ARCORE_SIX_DOF else activeSensorSource,
                sampleRateHz = estimatedSampleRateHz,
                isSixDofActive = arCorePoseOverride != null
            )
        }
    }

    private fun publishPose(rawQuat: Quat, angVel: Vec3, timestampNs: Long) {
        val yawAdjust = Quat.fromAxisAngle(Vec3.UP, yawOffsetRad)
        val manualAdjust = Quat.fromEulerDegrees(manualYawDeg, manualPitchDeg, 0f)
        val adjusted = (yawAdjust * rawQuat * manualAdjust).normalized()
        latestSnapshot.set(
            HeadPoseSnapshot(
                orientation = adjusted,
                position = Vec3.ZERO,
                angularVelocityRad = angVel,
                timestampNs = timestampNs,
                source = activeSensorSource,
                isSixDof = false
            )
        )
        poseHistory.record(timestampNs, adjusted, Vec3.ZERO)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

