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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalEmulatorDimensionsTest {
    @Test
    fun createRejectsNonPositiveDimensions() {
        assertEquals(
            "rows must be > 0: 0",
            assertThrows(IllegalArgumentException::class.java) {
                TerminalEmulatorFactory.create(initialRows = 0)
            }.message,
        )
        assertEquals(
            "rows must be > 0: -1",
            assertThrows(IllegalArgumentException::class.java) {
                TerminalEmulatorFactory.create(initialRows = -1)
            }.message,
        )
        assertEquals(
            "columns must be > 0: 0",
            assertThrows(IllegalArgumentException::class.java) {
                TerminalEmulatorFactory.create(initialCols = 0)
            }.message,
        )
        assertEquals(
            "columns must be > 0: -1",
            assertThrows(IllegalArgumentException::class.java) {
                TerminalEmulatorFactory.create(initialCols = -1)
            }.message,
        )
    }

    @Test
    fun resizeRejectsNonPositiveDimensionsWithoutChangingState() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 10)

        try {
            assertEquals(
                "rows must be > 0: 0",
                assertThrows(IllegalArgumentException::class.java) {
                    emulator.resize(0, 10)
                }.message,
            )
            assertEquals(
                "rows must be > 0: -1",
                assertThrows(IllegalArgumentException::class.java) {
                    emulator.resize(-1, 10)
                }.message,
            )
            assertEquals(
                "columns must be > 0: 0",
                assertThrows(IllegalArgumentException::class.java) {
                    emulator.resize(5, 0)
                }.message,
            )
            assertEquals(
                "columns must be > 0: -1",
                assertThrows(IllegalArgumentException::class.java) {
                    emulator.resize(5, -1)
                }.message,
            )

            assertEquals(TerminalDimensions(rows = 5, columns = 10), emulator.dimensions)
        } finally {
            emulator.close()
        }
    }
}
