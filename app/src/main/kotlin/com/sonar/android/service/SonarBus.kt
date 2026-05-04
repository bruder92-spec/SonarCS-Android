package com.sonar.android.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object SonarBus {

    sealed class Event {
        object EngineReady      : Event()
        object EngineUnloaded   : Event()
        object RecordingStarted : Event()
        object Recognizing      : Event()
        object Idle             : Event()
        class  TextReady(val text: String) : Event()
        class  Error(val msg: String)      : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun post(event: Event) { _events.tryEmit(event) }
}
