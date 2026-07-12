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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class NativeInputSafetyTest {
    private fun snapshot(emulator: TerminalEmulator): TerminalSnapshot {
        val impl = emulator as TerminalEmulatorImpl
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        impl.processPendingUpdates()
        return impl.snapshot.value
    }

    @Test
    fun invalidArrayAndDirectBufferRangesAreIgnored() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 3, initialCols = 20)
        val bytes = "unsafe".toByteArray()

        emulator.writeInput(bytes, offset = -1, length = 1)
        emulator.writeInput(bytes, offset = bytes.size, length = 1)
        emulator.writeInput(bytes, offset = 0, length = bytes.size + 1)
        emulator.writeInput(ByteBuffer.allocateDirect(4), length = 5)
        emulator.writeInput(ByteBuffer.allocateDirect(4), length = -1)
        emulator.writeInput("ok".toByteArray())

        assertEquals("ok", snapshot(emulator).lines.first().text.trimEnd())
    }

    @Test
    fun combiningHeavyCellRunsStayWithinNativeBuffer() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 3, initialCols = 80)
        val packedCell = "a\u0300\u0301\u0302\u0303\u0304"

        emulator.writeInput(packedCell.repeat(80).toByteArray())

        val line = snapshot(emulator).lines.first()
        assertTrue(line.text.startsWith("a"))
        assertTrue(line.cells.isNotEmpty())
    }
}
