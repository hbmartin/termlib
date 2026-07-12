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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalEmulatorResizeConcurrencyTest {
    @Test(timeout = 10_000L)
    fun snapshotsKeepDimensionsConsistentWithLinesDuringResize() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 10)
        val impl = emulator as TerminalEmulatorImpl
        val buildSnapshot = TerminalEmulatorImpl::class.java.getDeclaredMethod("buildSnapshot").apply {
            isAccessible = true
        }
        val resizing = AtomicBoolean(true)
        val failure = AtomicReference<String?>()
        val resizeFailure = AtomicReference<Throwable?>()

        val resizer = thread(name = "terminal-resizer") {
            try {
                repeat(2_000) { iteration ->
                    if (iteration % 2 == 0) {
                        emulator.resize(20, 40)
                    } else {
                        emulator.resize(5, 10)
                    }
                }
            } catch (throwable: Throwable) {
                resizeFailure.set(throwable)
            } finally {
                resizing.set(false)
            }
        }

        try {
            while (resizing.get() && failure.get() == null) {
                val snapshot = buildSnapshot.invoke(impl) as TerminalSnapshot
                val lineWidths = snapshot.lines.map { it.cells.size }.distinct()
                if (snapshot.lines.size != snapshot.rows || lineWidths.any { it != snapshot.cols }) {
                    failure.set(
                        "rows=${snapshot.rows}, cols=${snapshot.cols}, " +
                            "lineCount=${snapshot.lines.size}, widths=$lineWidths",
                    )
                }
            }
        } finally {
            resizer.join()
            emulator.close()
        }

        assertNull("resize thread failed", resizeFailure.get())
        assertNull(failure.get(), failure.get())
    }
}
