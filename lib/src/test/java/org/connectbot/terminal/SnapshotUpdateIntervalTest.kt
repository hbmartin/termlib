/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.connectbot.terminal

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * Tests for [TerminalEmulatorFactory.create]'s minUpdateIntervalMs parameter,
 * which throttles snapshot emissions to batch fast output into fewer redraws
 * (e.g. for e-ink displays).
 *
 * These tests exercise the Handler-scheduled update path rather than calling
 * [TerminalEmulatorImpl.processPendingUpdates] directly, using Robolectric's
 * paused main looper to control time.
 */
@RunWith(AndroidJUnit4::class)
class SnapshotUpdateIntervalTest {

    private fun screenText(impl: TerminalEmulatorImpl): String = impl.snapshot.value.lines.first().text.trim()

    @Test
    fun firstUpdateIsNotDeferred() {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 5,
            initialCols = 20,
            minUpdateIntervalMs = 250L,
        )
        val impl = emulator as TerminalEmulatorImpl
        val looper = shadowOf(Looper.getMainLooper())

        emulator.writeInput("a".toByteArray())
        looper.idle()

        assertEquals("a", screenText(impl))
    }

    @Test
    fun updatesWithinIntervalAreDeferredAndCoalesced() {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 5,
            initialCols = 20,
            minUpdateIntervalMs = 250L,
        )
        val impl = emulator as TerminalEmulatorImpl
        val looper = shadowOf(Looper.getMainLooper())

        // First write emits immediately.
        emulator.writeInput("a".toByteArray())
        looper.idle()
        assertEquals("a", screenText(impl))
        val seqAfterFirst = impl.snapshot.value.sequenceNumber

        // Writes inside the interval do not emit yet…
        emulator.writeInput("b".toByteArray())
        emulator.writeInput("c".toByteArray())
        looper.idle()
        assertEquals("still first snapshot", seqAfterFirst, impl.snapshot.value.sequenceNumber)
        assertEquals("a", screenText(impl))

        // …and once the interval elapses, both coalesce into a single emission.
        looper.idleFor(Duration.ofMillis(250))
        assertEquals("one coalesced snapshot", seqAfterFirst + 1, impl.snapshot.value.sequenceNumber)
        assertEquals("abc", screenText(impl))
    }

    @Test
    fun updateAfterIntervalElapsedIsImmediate() {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 5,
            initialCols = 20,
            minUpdateIntervalMs = 250L,
        )
        val impl = emulator as TerminalEmulatorImpl
        val looper = shadowOf(Looper.getMainLooper())

        emulator.writeInput("a".toByteArray())
        looper.idle()
        assertEquals("a", screenText(impl))

        // Let more than the interval pass with no pending damage.
        looper.idleFor(Duration.ofMillis(300))

        // A new write is then processed without waiting.
        emulator.writeInput("b".toByteArray())
        looper.idle()
        assertEquals("ab", screenText(impl))
    }

    @Test
    fun closeCancelsDelayedUpdate() {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 5,
            initialCols = 20,
            minUpdateIntervalMs = 250L,
        )
        val impl = emulator as TerminalEmulatorImpl
        val looper = shadowOf(Looper.getMainLooper())

        emulator.writeInput("a".toByteArray())
        looper.idle()
        assertEquals("a", screenText(impl))
        val seqAfterFirst = impl.snapshot.value.sequenceNumber

        emulator.writeInput("b".toByteArray())
        looper.idle()
        assertEquals(seqAfterFirst, impl.snapshot.value.sequenceNumber)

        emulator.close()
        looper.idleFor(Duration.ofMillis(250))

        assertEquals(seqAfterFirst, impl.snapshot.value.sequenceNumber)
        assertEquals("a", screenText(impl))
    }

    @Test
    fun closeCancelsPendingFrameCallback() {
        ShadowChoreographer.setPostFrameCallbackDelay(100)
        try {
            val emulator = TerminalEmulatorFactory.create(
                initialRows = 5,
                initialCols = 20,
            )
            val impl = emulator as TerminalEmulatorImpl
            val looper = shadowOf(Looper.getMainLooper())

            emulator.writeInput("a".toByteArray())
            looper.runOneTask()

            emulator.close()
            looper.idleFor(Duration.ofMillis(100))

            assertEquals(0L, impl.snapshot.value.sequenceNumber)
        } finally {
            ShadowChoreographer.reset()
        }
    }

    @Test
    fun writeAfterCloseIsSilentlyDropped() {
        // PTY reader threads commonly race teardown, so late writes are
        // discarded rather than treated as a programming error.
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 5,
            initialCols = 20,
        )
        val impl = emulator as TerminalEmulatorImpl
        val looper = shadowOf(Looper.getMainLooper())

        emulator.writeInput("a".toByteArray())
        looper.idle()
        assertEquals("a", screenText(impl))

        emulator.close()

        emulator.writeInput("b".toByteArray())
        looper.idle()
        assertEquals("a", screenText(impl))
    }

    @Test
    fun resizeAfterCloseThrows() {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 5,
            initialCols = 20,
        )

        emulator.close()

        assertThrows(IllegalStateException::class.java) {
            emulator.resize(10, 40)
        }
    }

    @Test
    fun dispatchKeyAfterCloseThrows() {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 5,
            initialCols = 20,
        )

        emulator.close()

        assertThrows(IllegalStateException::class.java) {
            emulator.dispatchKey(0, VTermKey.ENTER)
        }
    }

    @Test
    fun closeIsIdempotent() {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 5,
            initialCols = 20,
        )
        val looper = shadowOf(Looper.getMainLooper())

        // Initialize the native terminal so the second close exercises the
        // already-closed path rather than the never-initialized path.
        emulator.writeInput("a".toByteArray())
        looper.idle()

        emulator.close()
        emulator.close()
    }

    @Test
    fun snapshotRemainsReadableAfterClose() {
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 5,
            initialCols = 20,
        )
        val impl = emulator as TerminalEmulatorImpl
        val looper = shadowOf(Looper.getMainLooper())

        emulator.writeInput("a".toByteArray())
        looper.idle()

        emulator.close()

        assertEquals("a", screenText(impl))
        assertEquals(emptyList<TerminalUrl>(), emulator.getUrls())
    }
}
