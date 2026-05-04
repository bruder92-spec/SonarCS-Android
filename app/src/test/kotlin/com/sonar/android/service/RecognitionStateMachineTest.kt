package com.sonar.android.service

import com.sonar.android.service.RecognitionStateMachine.Cmd
import com.sonar.android.service.RecognitionStateMachine.State
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RecognitionStateMachineTest {

    private lateinit var sm: RecognitionStateMachine
    private val pcm1 = byteArrayOf(1, 2)
    private val pcm2 = byteArrayOf(3, 4)

    @Before fun setUp() { sm = RecognitionStateMachine() }

    // ── happy path ──────────────────────────────────────────────────────────────

    @Test fun `idle + longPress → Recording, cmd=StartRecording`() {
        val cmd = sm.onLongPressDown()
        assertTrue(cmd is Cmd.StartRecording)
        assertEquals(State.Recording, sm.currentState)
    }

    @Test fun `recording + keyUp → Recognizing, cmd=Recognize`() {
        sm.onLongPressDown()
        val cmd = sm.onKeyUp(pcm1)
        assertTrue(cmd is Cmd.Recognize)
        assertArrayEquals(pcm1, (cmd as Cmd.Recognize).pcm)
        assertEquals(State.Recognizing, sm.currentState)
    }

    @Test fun `recognizing + done(no queue) → Idle, cmd=GoIdle`() {
        sm.onLongPressDown()
        sm.onKeyUp(pcm1)
        val cmd = sm.onRecognitionDone()
        assertTrue(cmd is Cmd.GoIdle)
        assertEquals(State.Idle, sm.currentState)
    }

    // ── phrase queue ────────────────────────────────────────────────────────────

    @Test fun `recognizing + longPress → starts queue recording, cmd=StartRecording`() {
        sm.onLongPressDown(); sm.onKeyUp(pcm1)     // → Recognizing
        val cmd = sm.onLongPressDown()
        assertTrue(cmd is Cmd.StartRecording)
        assertTrue(sm.isCapturingForQueue)
        assertEquals(State.Recognizing, sm.currentState)
    }

    @Test fun `recognizing+capturing + keyUp → stores pending, no immediate cmd`() {
        sm.onLongPressDown(); sm.onKeyUp(pcm1)     // → Recognizing
        sm.onLongPressDown()                        // → capturingForQueue
        val cmd = sm.onKeyUp(pcm2)
        assertNull(cmd)                             // just queued, no new recognition yet
        assertFalse(sm.isCapturingForQueue)
        assertEquals(State.Recognizing, sm.currentState)
    }

    @Test fun `recognizing(pending) + done → Recognizing with pending cmd`() {
        sm.onLongPressDown(); sm.onKeyUp(pcm1)     // → Recognizing
        sm.onLongPressDown(); sm.onKeyUp(pcm2)     // → pending set
        val cmd = sm.onRecognitionDone()
        assertTrue(cmd is Cmd.Recognize)
        assertArrayEquals(pcm2, (cmd as Cmd.Recognize).pcm)
        assertEquals(State.Recognizing, sm.currentState)
    }

    @Test fun `second recognition done → Idle`() {
        sm.onLongPressDown(); sm.onKeyUp(pcm1)
        sm.onLongPressDown(); sm.onKeyUp(pcm2)
        sm.onRecognitionDone()                     // processes pending
        val cmd = sm.onRecognitionDone()           // second recognition done
        assertTrue(cmd is Cmd.GoIdle)
        assertEquals(State.Idle, sm.currentState)
    }

    @Test fun `recognizing+capturing + done → Recording (still capturing)`() {
        sm.onLongPressDown(); sm.onKeyUp(pcm1)     // → Recognizing
        sm.onLongPressDown()                        // → capturingForQueue, Recording-like
        // Recognition finishes while still holding button
        val cmd = sm.onRecognitionDone()
        assertNull(cmd)                             // no new cmd, state → Recording
        assertEquals(State.Recording, sm.currentState)
    }

    // ── ignore / no-op cases ─────────────────────────────────────────────────────

    @Test fun `idle + keyUp → null`() {
        assertNull(sm.onKeyUp(pcm1))
        assertEquals(State.Idle, sm.currentState)
    }

    @Test fun `recording + longPress → null (already recording)`() {
        sm.onLongPressDown()
        assertNull(sm.onLongPressDown())
        assertEquals(State.Recording, sm.currentState)
    }

    @Test fun `recognizing with pending + longPress → null (queue full)`() {
        sm.onLongPressDown(); sm.onKeyUp(pcm1)     // → Recognizing
        sm.onLongPressDown(); sm.onKeyUp(pcm2)     // → pendingPcm set
        assertNull(sm.onLongPressDown())            // queue full, ignore
    }

    // ── reset ───────────────────────────────────────────────────────────────────

    @Test fun `reset from recording → Idle`() {
        sm.onLongPressDown()
        sm.reset()
        assertEquals(State.Idle, sm.currentState)
        assertFalse(sm.isCapturingForQueue)
    }
}
