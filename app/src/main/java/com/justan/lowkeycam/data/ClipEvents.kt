package com.justan.lowkeycam.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * "The clip store changed" signal. Post-processing runs outside the camera screen, so screens that
 * are already visible need to be told to re-read the store.
 */
object ClipEvents {
    private val bus = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val clipsChanged = bus.asSharedFlow()

    fun emit() {
        bus.tryEmit(Unit)
    }
}
