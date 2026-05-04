package com.sonar.android.service

// Pure port of TrayApp.cs state machine — no Android/coroutine dependencies.
// Thread-safe: all public methods synchronize on `this`.
internal class RecognitionStateMachine {

    enum class State { Idle, Recording, Recognizing }

    sealed class Cmd {
        object StartRecording : Cmd()
        class Recognize(val pcm: ByteArray) : Cmd()
        object GoIdle : Cmd()
    }

    private var state             = State.Idle
    private var capturingForQueue = false
    private var pendingPcm: ByteArray? = null

    val currentState        get() = synchronized(this) { state }
    val isCapturingForQueue get() = synchronized(this) { capturingForQueue }

    // Called when long-press fires (500 ms threshold reached)
    fun onLongPressDown(): Cmd? = synchronized(this) {
        when {
            state == State.Idle -> {
                state = State.Recording
                Cmd.StartRecording
            }
            state == State.Recognizing && !capturingForQueue && pendingPcm == null -> {
                capturingForQueue = true
                Cmd.StartRecording
            }
            else -> null  // ignore: already recording, queue full, etc.
        }
    }

    // Called when the volume button is released
    fun onKeyUp(pcm: ByteArray): Cmd? = synchronized(this) {
        when {
            state == State.Recording -> {
                state = State.Recognizing
                Cmd.Recognize(pcm)
            }
            capturingForQueue -> {
                capturingForQueue = false
                if (state == State.Recognizing) {
                    pendingPcm = pcm   // store for after current recognition finishes
                    null
                } else {
                    state = State.Recognizing
                    Cmd.Recognize(pcm)
                }
            }
            else -> null
        }
    }

    // Called when recognition finishes (success or error)
    fun onRecognitionDone(): Cmd? = synchronized(this) {
        val pending = pendingPcm
        val capNow  = capturingForQueue
        pendingPcm  = null
        when {
            capNow   -> { state = State.Recording; null }      // still recording next phrase
            pending != null -> Cmd.Recognize(pending)          // process queued phrase
            else     -> { state = State.Idle; Cmd.GoIdle }
        }
    }

    fun reset() = synchronized(this) {
        state             = State.Idle
        capturingForQueue = false
        pendingPcm        = null
    }
}
