package com.cryptostorm.xray

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class XrayStatus { STOPPED, LISTENING, ACTIVE }

object XraySignals {
    private val _events = MutableSharedFlow<XrayStatus>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    @JvmStatic fun notifyActive()    { _events.tryEmit(XrayStatus.ACTIVE) }
    @JvmStatic fun notifyListening() { _events.tryEmit(XrayStatus.LISTENING) }
    @JvmStatic fun notifyStopped()   { _events.tryEmit(XrayStatus.STOPPED) }
}