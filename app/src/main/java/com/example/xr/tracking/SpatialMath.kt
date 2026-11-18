package com.example.xr.tracking

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec3(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f
) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float) = Vec3(x * scalar, y * scalar, z * scalar)
    operator fun div(scalar: Float) = if (abs(scalar) > 1e-6f) Vec3(x / scalar, y / scalar, z / scalar) else this

    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3): Vec3 = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val len = length()
        return if (len > 1e-6f) Vec3(x / len, y / len, z / len) else Vec3(0f, 0f, -1f)
    }

    fun distanceTo(other: Vec3): Float = (this - other).length()

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val FORWARD = Vec3(0f, 0f, -1f)
        val UP = Vec3(0f, 1f, 0f)
        val RIGHT = Vec3(1f, 0f, 0f)
    }
}

data class Quat(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val w: Float = 1f
) {
    fun normalized(): Quat {
        val len = sqrt(x * x + y * y + z * z + w * w)
        return if (len > 1e-6f) Quat(x / len, y / len, z / len, w / len) else IDENTITY
    }

    fun conjugate(): Quat = Quat(-x, -y, -z, w)

    operator fun times(o: Quat): Quat = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z
    ).normalized()

    fun rotate(v: Vec3): Vec3 {
        val tx = 2f * (y * v.z - z * v.y)
        val ty = 2f * (z * v.x - x * v.z)
        val tz = 2f * (x * v.y - y * v.x)
        return Vec3(
            v.x + w * tx + (y * tz - z * ty),
            v.y + w * ty + (z * tx - x * tz),
            v.z + w * tz + (x * ty - y * tx)
        )
    }

    /**
     * Writes a 4x4 column-major rotation matrix into [out] without allocations.
     */
    fun toMatrix4x4(out: FloatArray) {
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z

        out[0] = 1f - 2f * (yy + zz)
        out[1] = 2f * (xy + wz)
        out[2] = 2f * (xz - wy)
        out[3] = 0f

        out[4] = 2f * (xy - wz)
        out[5] = 1f - 2f * (xx + zz)
        out[6] = 2f * (yz + wx)
        out[7] = 0f

        out[8] = 2f * (xz + wy)
        out[9] = 2f * (yz - wx)
        out[10] = 1f - 2f * (xx + yy)
        out[11] = 0f

        out[12] = 0f
        out[13] = 0f
        out[14] = 0f
        out[15] = 1f
    }

    fun extractYawRadians(): Float {
        val fwd = rotate(Vec3.FORWARD)
        return atan2(-fwd.x, -fwd.z)
    }

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        fun fromAxisAngle(axis: Vec3, angleRad: Float): Quat {
            val half = angleRad * 0.5f
            val s = sin(half)
            val n = axis.normalized()
            return Quat(n.x * s, n.y * s, n.z * s, cos(half)).normalized()
        }

        fun fromEulerDegrees(yawDeg: Float, pitchDeg: Float, rollDeg: Float): Quat {
            val yaw = yawDeg * (PI.toFloat() / 180f)
            val pitch = pitchDeg * (PI.toFloat() / 180f)
            val roll = rollDeg * (PI.toFloat() / 180f)
            val cy = cos(yaw * 0.5f)
            val sy = sin(yaw * 0.5f)
            val cp = cos(pitch * 0.5f)
            val sp = sin(pitch * 0.5f)
            val cr = cos(roll * 0.5f)
            val sr = sin(roll * 0.5f)
            return Quat(
                x = cr * sp * cy + sr * cp * sy,
                y = cr * cp * sy - sr * sp * cy,
                z = sr * cp * cy - cr * sp * sy,
                w = cr * cp * cy + sr * sp * sy
            ).normalized()
        }

        fun slerp(a: Quat, b: Quat, t: Float): Quat {
            val clampedT = t.coerceIn(0f, 1f)
            var bx = b.x
            var by = b.y
            var bz = b.z
            var bw = b.w
            var dot = a.x * bx + a.y * by + a.z * bz + a.w * bw
            if (dot < 0f) {
                dot = -dot
                bx = -bx
                by = -by
                bz = -bz
                bw = -bw
            }
            if (dot > 0.9995f) {
                return Quat(
                    a.x + clampedT * (bx - a.x),
                    a.y + clampedT * (by - a.y),
                    a.z + clampedT * (bz - a.z),
                    a.w + clampedT * (bw - a.w)
                ).normalized()
            }
            val theta0 = acos(dot.coerceIn(-1f, 1f))
            val theta = theta0 * clampedT
            val sinTheta = sin(theta)
            val sinTheta0 = sin(theta0)
            val s0 = cos(theta) - dot * sinTheta / sinTheta0
            val s1 = sinTheta / sinTheta0
            return Quat(
                s0 * a.x + s1 * bx,
                s0 * a.y + s1 * by,
                s0 * a.z + s1 * bz,
                s0 * a.w + s1 * bw
            ).normalized()
        }

        fun predictOrientation(current: Quat, angularVelocityRadPerSec: Vec3, dtSeconds: Float): Quat {
            if (dtSeconds <= 0f) return current
            val speed = angularVelocityRadPerSec.length()
            if (speed < 1e-4f) return current
            val angle = (speed * dtSeconds).coerceAtMost(0.35f) // safety cap ~20 deg
            val delta = fromAxisAngle(angularVelocityRadPerSec / speed, angle)
            return (delta * current).normalized()
        }
    }
}

data class Ray(
    val origin: Vec3,
    val direction: Vec3
) {
    /**
     * Intersects this ray with a finite rectangle centered at [quadCenter] with unit right/up vectors.
     * Returns (t, u, v) where u,v are in [0, 1] (top-left origin for Android Canvas/Touch) or null if missed.
     */
    fun intersectQuad(
        quadCenter: Vec3,
        quadRight: Vec3,
        quadUp: Vec3,
        quadNormal: Vec3,
        width: Float,
        height: Float
    ): QuadHit? {
        val denom = direction.dot(quadNormal)
        if (abs(denom) < 1e-5f) return null
        val t = (quadCenter - origin).dot(quadNormal) / denom
        if (t <= 0.05f) return null

        val hitPoint = origin + direction * t
        val rel = hitPoint - quadCenter
        val localX = rel.dot(quadRight)
        val localY = rel.dot(quadUp)

        val halfW = width * 0.5f
        val halfH = height * 0.5f
        if (abs(localX) > halfW || abs(localY) > halfH) return null

        val u = (localX / width) + 0.5f
        val v = 0.5f - (localY / height) // top = 0, bottom = 1
        return QuadHit(distance = t, u = u.coerceIn(0f, 1f), v = v.coerceIn(0f, 1f), worldPoint = hitPoint)
    }
}

data class QuadHit(
    val distance: Float,
    val u: Float,
    val v: Float,
    val worldPoint: Vec3
)

/**
 * Adaptive One-Euro Filter for low-jitter at rest and minimal lag during fast motion.
 */
class OneEuroFilter(
    private val minCutoff: Float = 1.5f,
    private val beta: Float = 0.05f,
    private val dCutoff: Float = 1.0f
) {
    private var prevValue: Float = 0f
    private var prevDeriv: Float = 0f
    private var prevTimeNs: Long = 0L
    private var initialized: Boolean = false

    fun reset() {
        initialized = false
        prevValue = 0f
        prevDeriv = 0f
        prevTimeNs = 0L
    }

    fun filter(value: Float, timestampNs: Long): Float {
        if (!initialized) {
            initialized = true
            prevValue = value
            prevDeriv = 0f
            prevTimeNs = timestampNs
            return value
        }
        val dt = ((timestampNs - prevTimeNs) * 1e-9f).coerceIn(1e-4f, 0.2f)
        prevTimeNs = timestampNs

        val rawDeriv = (value - prevValue) / dt
        val aD = alpha(dt, dCutoff)
        val ed = aD * rawDeriv + (1f - aD) * prevDeriv
        prevDeriv = ed

        val cutoff = minCutoff + beta * abs(ed)
        val a = alpha(dt, cutoff)
        val filtered = a * value + (1f - a) * prevValue
        prevValue = filtered
        return filtered
    }

    private fun alpha(dt: Float, cutoff: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}

class Vec3OneEuroFilter(
    minCutoff: Float = 2.0f,
    beta: Float = 0.08f
) {
    private val fx = OneEuroFilter(minCutoff, beta)
    private val fy = OneEuroFilter(minCutoff, beta)
    private val fz = OneEuroFilter(minCutoff, beta)

    fun reset() {
        fx.reset()
        fy.reset()
        fz.reset()
    }

    fun filter(v: Vec3, timestampNs: Long): Vec3 = Vec3(
        fx.filter(v.x, timestampNs),
        fy.filter(v.y, timestampNs),
        fz.filter(v.z, timestampNs)
    )
}

/**
 * Adaptive One-Euro filter in SO(3) using velocity-scaled SLERP so static micro-jitter
 * is smoothed without introducing perceptible lag during fast head turns.
 */
class QuaternionOneEuroFilter(
    private val minCutoff: Float = 24.0f,
    private val beta: Float = 0.85f,
    private val dCutoff: Float = 1.0f
) {
    private var prevQuat: Quat = Quat.IDENTITY
    private var prevDerivRad: Float = 0f
    private var prevTimeNs: Long = 0L
    private var initialized: Boolean = false

    fun reset() {
        initialized = false
        prevQuat = Quat.IDENTITY
        prevDerivRad = 0f
        prevTimeNs = 0L
    }

    fun filter(q: Quat, timestampNs: Long): Quat {
        val normQ = q.normalized()
        if (!initialized) {
            initialized = true
            prevQuat = normQ
            prevDerivRad = 0f
            prevTimeNs = timestampNs
            return normQ
        }
        val dt = ((timestampNs - prevTimeNs) * 1e-9f).coerceIn(1e-4f, 0.1f)
        prevTimeNs = timestampNs

        val delta = normQ * prevQuat.conjugate()
        val sinHalf = sqrt(delta.x * delta.x + delta.y * delta.y + delta.z * delta.z)
        val angle = 2f * atan2(sinHalf, abs(delta.w))
        val rawSpeed = angle / dt

        val aD = alpha(dt, dCutoff)
        val ed = aD * rawSpeed + (1f - aD) * prevDerivRad
        prevDerivRad = ed

        val cutoff = minCutoff + beta * ed
        val a = alpha(dt, cutoff)
        val filtered = Quat.slerp(prevQuat, normQ, a)
        prevQuat = filtered
        return filtered
    }

    private fun alpha(dt: Float, cutoff: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}

data class TimestampedPose(
    val timestampNs: Long,
    val orientation: Quat,
    val position: Vec3
)

/**
 * Thread-safe ring buffer of timestamped head poses (in elapsedRealtimeNanos)
 * used to look up and SLERP-interpolate the head pose at a camera frame's capture timestamp.
 */
class PoseHistoryBuffer(private val capacity: Int = 128) {
    private val entries = arrayOfNulls<TimestampedPose>(capacity)
    private var writeIndex = 0
    private var count = 0
    private val lock = Any()

    fun clear() = synchronized(lock) {
        for (i in entries.indices) entries[i] = null
        writeIndex = 0
        count = 0
    }

    fun record(timestampNs: Long, orientation: Quat, position: Vec3 = Vec3.ZERO) = synchronized(lock) {
        if (timestampNs <= 0L) return@synchronized
        entries[writeIndex] = TimestampedPose(timestampNs, orientation, position)
        writeIndex = (writeIndex + 1) % capacity
        if (count < capacity) count++
    }

    fun size(): Int = synchronized(lock) { count }

    /**
     * Returns an interpolated pose at [targetTimestampNs] (elapsedRealtimeNanos).
     * If [targetTimestampNs] is older than the buffer, clamps to the oldest pose;
     * if newer, clamps to the latest pose.
     */
    fun getPoseAt(targetTimestampNs: Long): TimestampedPose? = synchronized(lock) {
        if (count == 0) return@synchronized null
        if (targetTimestampNs <= 0L) {
            val latestIdx = (writeIndex - 1 + capacity) % capacity
            return@synchronized entries[latestIdx]
        }

        var before: TimestampedPose? = null
        var after: TimestampedPose? = null
        var oldest: TimestampedPose? = null
        var newest: TimestampedPose? = null

        val startIdx = if (count < capacity) 0 else writeIndex
        for (i in 0 until count) {
            val idx = (startIdx + i) % capacity
            val item = entries[idx] ?: continue
            if (oldest == null || item.timestampNs < oldest.timestampNs) oldest = item
            if (newest == null || item.timestampNs > newest.timestampNs) newest = item
            if (item.timestampNs <= targetTimestampNs) {
                if (before == null || item.timestampNs >= before.timestampNs) before = item
            }
            if (item.timestampNs >= targetTimestampNs) {
                if (after == null || item.timestampNs <= after.timestampNs) after = item
            }
        }

        return@synchronized when {
            before != null && after != null -> {
                val span = after.timestampNs - before.timestampNs
                if (span <= 0L) {
                    before
                } else {
                    val t = ((targetTimestampNs - before.timestampNs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
                    TimestampedPose(
                        timestampNs = targetTimestampNs,
                        orientation = Quat.slerp(before.orientation, after.orientation, t),
                        position = Vec3(
                            before.position.x + (after.position.x - before.position.x) * t,
                            before.position.y + (after.position.y - before.position.y) * t,
                            before.position.z + (after.position.z - before.position.z) * t
                        )
                    )
                }
            }
            before != null -> before
            after != null -> after
            else -> newest ?: oldest
        }
    }
}

object ClockDomainConverter {
    /**
     * Converts a camera frame timestamp into SystemClock.elapsedRealtimeNanos() domain
     * based on CameraMetadata SENSOR_INFO_TIMESTAMP_SOURCE (0 = UNKNOWN/uptime, 1 = REALTIME).
     */
    fun cameraTimestampToElapsedRealtimeNs(
        cameraTimestampNs: Long,
        isRealtimeClockSource: Boolean,
        elapsedRealtimeNsNow: Long,
        uptimeNsNow: Long
    ): Long {
        if (cameraTimestampNs <= 0L) return elapsedRealtimeNsNow
        if (isRealtimeClockSource) return cameraTimestampNs
        val offsetNs = elapsedRealtimeNsNow - uptimeNsNow
        return cameraTimestampNs + offsetNs
    }
}

