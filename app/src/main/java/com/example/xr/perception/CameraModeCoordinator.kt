package com.example.xr.perception

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class CameraCoordinatorState {
    NONE,
    RELEASING,
    ACQUIRING_CAMERAX,
    ACQUIRING_ARCORE,
    ACTIVE
}

enum class CameraConsumerMode {
    OFF,
    CAMERAX_SHARED,
    ARCORE_MODE
}

data class CameraStatusSnapshot(
    val state: CameraCoordinatorState = CameraCoordinatorState.NONE,
    val activeMode: CameraConsumerMode = CameraConsumerMode.OFF,
    val statusMessage: String = "Camera idle",
    val switchCount: Int = 0
)

/**
 * Serialized state machine ensuring only one camera owner (CameraX or ARCore)
 * holds hardware camera access at a time: NONE -> RELEASING -> ACQUIRING_* -> ACTIVE.
 */
class CameraModeCoordinator(
    private val releaseTimeoutMs: Long = 2000L
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transitionMutex = Mutex()

    private val _status = MutableStateFlow(CameraStatusSnapshot())
    val status: StateFlow<CameraStatusSnapshot> = _status.asStateFlow()

    private var currentOwner: CameraOwnerHandle? = null

    interface CameraOwnerHandle {
        val mode: CameraConsumerMode
        suspend fun acquire(): Boolean
        suspend fun release()
        fun forceClose()
    }

    fun requestModeSwitch(
        targetMode: CameraConsumerMode,
        ownerFactory: (CameraConsumerMode) -> CameraOwnerHandle?
    ) {
        scope.launch {
            switchModeSync(targetMode, ownerFactory)
        }
    }

    suspend fun switchModeSync(
        targetMode: CameraConsumerMode,
        ownerFactory: (CameraConsumerMode) -> CameraOwnerHandle?
    ): Boolean = transitionMutex.withLock {
        val current = currentOwner
        if (current != null && current.mode == targetMode && _status.value.state == CameraCoordinatorState.ACTIVE) {
            return true
        }

        if (current != null) {
            _status.value = _status.value.copy(
                state = CameraCoordinatorState.RELEASING,
                statusMessage = "Releasing ${current.mode.name}..."
            )
            val released = withTimeoutOrNull(releaseTimeoutMs) {
                runCatching { current.release() }.isSuccess
            } ?: false
            if (!released) {
                runCatching { current.forceClose() }
            }
            currentOwner = null
        }

        if (targetMode == CameraConsumerMode.OFF) {
            _status.value = _status.value.copy(
                state = CameraCoordinatorState.NONE,
                activeMode = CameraConsumerMode.OFF,
                statusMessage = "Camera off"
            )
            return true
        }

        val nextOwner = ownerFactory(targetMode) ?: run {
            _status.value = _status.value.copy(
                state = CameraCoordinatorState.NONE,
                activeMode = CameraConsumerMode.OFF,
                statusMessage = "Requested camera mode unavailable"
            )
            return false
        }

        val acquiringState = if (targetMode == CameraConsumerMode.ARCORE_MODE) {
            CameraCoordinatorState.ACQUIRING_ARCORE
        } else {
            CameraCoordinatorState.ACQUIRING_CAMERAX
        }
        _status.value = _status.value.copy(
            state = acquiringState,
            statusMessage = "Acquiring ${targetMode.name}..."
        )

        val acquired = runCatching { nextOwner.acquire() }.getOrDefault(false)
        if (acquired) {
            currentOwner = nextOwner
            _status.value = _status.value.copy(
                state = CameraCoordinatorState.ACTIVE,
                activeMode = targetMode,
                statusMessage = "${targetMode.name} active",
                switchCount = _status.value.switchCount + 1
            )
            return true
        } else {
            runCatching { nextOwner.forceClose() }
            currentOwner = null
            _status.value = _status.value.copy(
                state = CameraCoordinatorState.NONE,
                activeMode = CameraConsumerMode.OFF,
                statusMessage = "Failed to acquire ${targetMode.name}; reverted to VR"
            )
            return false
        }
    }

    suspend fun releaseAll() {
        switchModeSync(CameraConsumerMode.OFF) { null }
    }
}
