package com.example.xr.hand

import com.example.xr.tracking.HeadPoseSnapshot
import com.example.xr.tracking.Ray
import com.example.xr.tracking.Vec3
import com.example.xr.windows.RaycastTarget
import com.example.xr.windows.SpatialWindowManager
import com.example.xr.windows.SpatialWindowState
import kotlin.math.PI
import kotlin.math.tan

/**
 * Projects camera-space hand landmarks into world space using the head pose AT THE CAMERA FRAME TIMESTAMP
 * plus camera FOV and calibrated palm width, produces interaction rays (Look+Pinch or Hand-Ray),
 * and checks direct near-field fingertip pokes.
 */
class HandRayInteractor {

    fun estimatePalmDepthMeters(
        hand: HandFrame,
        calibratedPalmWidthMeters: Float
    ): Float {
        val palmImg = hand.palmWidthImage().coerceAtLeast(0.025f)
        val halfFovRad = (hand.cameraHfovDeg * 0.5f) * (PI.toFloat() / 180f)
        val tanHalf = tan(halfFovRad).coerceIn(0.35f, 1.8f)
        // Pinhole camera depth: Z = realWidth / (2 * normalizedWidth * tan(HFOV/2))
        val depth = calibratedPalmWidthMeters / (2f * palmImg * tanHalf)
        return depth.coerceIn(0.18f, 1.15f)
    }

    /**
     * Transforms a normalized [0,1] image landmark into 3D world coordinates using [frameHeadPose]
     * (the head pose recorded at the camera frame's capture timestamp) and camera HFOV/VFOV.
     */
    fun imageLandmarkToWorld(
        landmark: Vec3,
        frameHeadPose: HeadPoseSnapshot,
        palmDepthMeters: Float,
        cameraHfovDeg: Float,
        cameraVfovDeg: Float
    ): Vec3 {
        val tanH = tan((cameraHfovDeg * 0.5f) * (PI.toFloat() / 180f)).coerceIn(0.35f, 1.8f)
        val tanV = tan((cameraVfovDeg * 0.5f) * (PI.toFloat() / 180f)).coerceIn(0.28f, 1.5f)
        val zLocal = -(palmDepthMeters + landmark.z * 0.16f).coerceIn(0.14f, 1.30f)
        val depth = -zLocal
        val xLocal = (landmark.x - 0.5f) * 2f * tanH * depth
        val yLocal = -(landmark.y - 0.5f) * 2f * tanV * depth
        return frameHeadPose.position + frameHeadPose.orientation.rotate(Vec3(xLocal, yLocal, zLocal))
    }

    fun computeInteractionRay(
        headPose: HeadPoseSnapshot,
        handRayModeEnabled: Boolean,
        dominantHand: HandFrame?,
        dominantHandFramePose: HeadPoseSnapshot? = null,
        calibratedPalmWidthMeters: Float = 0.085f
    ): Ray {
        if (!handRayModeEnabled || dominantHand == null || dominantHand.imageLandmarks.size < 21) {
            val gazeDir = headPose.orientation.rotate(Vec3.FORWARD).normalized()
            return Ray(origin = headPose.position, direction = gazeDir)
        }

        val handPose = dominantHandFramePose ?: headPose
        val depth = estimatePalmDepthMeters(dominantHand, calibratedPalmWidthMeters)
        val wristWorld = imageLandmarkToWorld(
            dominantHand.imageLandmarks[HandLandmarkIndex.WRIST],
            handPose,
            depth,
            dominantHand.cameraHfovDeg,
            dominantHand.cameraVfovDeg
        )
        val indexTipWorld = imageLandmarkToWorld(
            dominantHand.imageLandmarks[HandLandmarkIndex.INDEX_TIP],
            handPose,
            depth,
            dominantHand.cameraHfovDeg,
            dominantHand.cameraVfovDeg
        )
        val shoulderOffset = Vec3(
            if (dominantHand.side == HandSide.RIGHT) 0.18f else -0.18f,
            -0.25f,
            -0.08f
        )
        val shoulderWorld = handPose.position + handPose.orientation.rotate(shoulderOffset)
        val shoulderToTip = (indexTipWorld - shoulderWorld).normalized()
        val wristToTip = (indexTipWorld - wristWorld).normalized()
        val blendedDir = (shoulderToTip * 0.72f + wristToTip * 0.28f).normalized()
        return Ray(origin = shoulderWorld, direction = blendedDir)
    }

    fun estimateIndexTipWorld(
        frameHeadPose: HeadPoseSnapshot,
        hand: HandFrame,
        calibratedPalmWidthMeters: Float
    ): Vec3? {
        if (hand.imageLandmarks.size < 21) return null
        val depth = estimatePalmDepthMeters(hand, calibratedPalmWidthMeters)
        return imageLandmarkToWorld(
            landmark = hand.imageLandmarks[HandLandmarkIndex.INDEX_TIP],
            frameHeadPose = frameHeadPose,
            palmDepthMeters = depth,
            cameraHfovDeg = hand.cameraHfovDeg,
            cameraVfovDeg = hand.cameraVfovDeg
        )
    }

    /**
     * Checks whether the index fingertip is physically close (~4.5 cm) to a window quad surface
     * for direct near-field poking. Returns both the WindowHit and the fingertip world position.
     */
    fun findDirectPokeHit(
        headPose: HeadPoseSnapshot,
        hand: HandFrame?,
        calibratedPalmWidthMeters: Float,
        windows: List<SpatialWindowState>,
        maxSurfaceDistanceMeters: Float = 0.045f
    ): RaycastTarget.WindowHit? {
        if (hand == null) return null
        val tipWorld = estimateIndexTipWorld(headPose, hand, calibratedPalmWidthMeters) ?: return null
        var bestHit: RaycastTarget.WindowHit? = null
        var bestPlaneDist = maxSurfaceDistanceMeters

        for (win in windows) {
            if (win.minimized) continue
            val basis = win.computeWorldBasis()
            val rel = tipWorld - basis.center
            val signedDist = kotlin.math.abs(rel.dot(basis.normal))
            if (signedDist > bestPlaneDist) continue

            val halfW = win.widthMeters * 0.5f
            val halfH = win.heightMeters * 0.5f
            val localX = rel.dot(basis.right)
            val localY = rel.dot(basis.up)
            if (localX in -halfW..halfW && localY in -halfH..halfH) {
                val u = (localX + halfW) / win.widthMeters
                val v = 1f - ((localY + halfH) / win.heightMeters)
                bestPlaneDist = signedDist
                bestHit = RaycastTarget.WindowHit(
                    windowId = win.id,
                    region = SpatialWindowManager.classifyRegionStatic(u, v),
                    u = u,
                    v = v,
                    distance = tipWorld.distanceTo(headPose.position)
                )
            }
        }
        return bestHit
    }
}
