/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Interface for controlling text selection in the terminal.
 * This allows external components (UI chrome, keyboard handlers, accessibility) to control selection.
 */
interface SelectionController {
    /**
     * Check if selection mode is currently active.
     */
    val isSelectionActive: Boolean

    /**
     * True while the selection is still being extended (a keyboard-driven
     * selection before [finishSelection], or a touch drag in progress). A
     * finished selection stays [isSelectionActive] for copying but no longer
     * extends. Defaults to false for implementations without this distinction.
     */
    val isSelectionExtending: Boolean
        get() = false

    /**
     * Start selection mode at the current cursor position or center of screen.
     * @param mode The selection mode to use (CHARACTER, WORD, or LINE)
     */
    fun startSelection(mode: SelectionMode = SelectionMode.CHARACTER)

    /**
     * Toggle selection mode on/off. If off, turns it on. If on, turns it off.
     */
    fun toggleSelection()

    /**
     * Move the selection cursor up by one row.
     */
    fun moveSelectionUp()

    /**
     * Move the selection cursor down by one row.
     */
    fun moveSelectionDown()

    /**
     * Move the selection cursor left by one column.
     */
    fun moveSelectionLeft()

    /**
     * Move the selection cursor right by one column.
     */
    fun moveSelectionRight()

    /**
     * Toggle between CHARACTER, WORD, and LINE selection modes.
     */
    fun toggleSelectionMode()

    /**
     * Set the selection mode directly.
     */
    fun setSelectionMode(mode: SelectionMode)

    /**
     * Select all text in the terminal.
     */
    fun selectAll()

    /**
     * Finish the selection (stop extending it, but keep it active for copying).
     */
    fun finishSelection()

    /**
     * Copy the selected text to clipboard and clear the selection.
     * @return The selected text, or empty string if no selection
     */
    fun copySelection(): String

    /**
     * Returns the currently selected text without the clipboard-write and
     * selection-clear side effects of [copySelection]. Soft-wrapped lines are
     * rejoined using libvterm's authoritative `softWrapped` flag. Returns "" if
     * nothing is selected.
     *
     * Default implementation returns "" so existing test doubles compile; the
     * real implementation forwards to the live snapshot. Callers that need the
     * selection's logical text (smart copy, OSC 52 emitters) should prefer this
     * over re-extracting from the snapshot themselves.
     */
    fun getSelectedText(): String = ""

    /**
     * Clear the selection without copying.
     */
    fun clearSelection()
}

sealed class SelectionMode {
    data object NONE : SelectionMode()
    data object CHARACTER : SelectionMode()
    data object WORD : SelectionMode()
    data object LINE : SelectionMode()
}

internal data class SelectionRange(
    val startRow: Int,
    val startCol: Int,
    val endRow: Int,
    val endCol: Int,
) {
    fun contains(row: Int, col: Int): Boolean {
        val minRow = minOf(startRow, endRow)
        val maxRow = maxOf(startRow, endRow)

        if (row !in minRow..maxRow) return false

        if (startRow == endRow) {
            val minCol = minOf(startCol, endCol)
            val maxCol = maxOf(startCol, endCol)
            return col in minCol..maxCol
        }

        return when (row) {
            minRow -> col >= if (startRow < endRow) startCol else endCol
            maxRow -> col <= if (startRow < endRow) endCol else startCol
            else -> true
        }
    }

    fun getStartPosition(): Pair<Int, Int> {
        if (startRow == endRow) return Pair(startRow, minOf(startCol, endCol))
        if (startRow < endRow) return Pair(startRow, startCol)
        return Pair(endRow, endCol)
    }

    fun getEndPosition(): Pair<Int, Int> {
        if (startRow == endRow) return Pair(startRow, maxOf(startCol, endCol))
        if (startRow < endRow) return Pair(endRow, endCol)
        return Pair(startRow, startCol)
    }
}

internal class SelectionManager {
    var mode by mutableStateOf<SelectionMode>(SelectionMode.NONE)
        private set

    var selectionRange by mutableStateOf<SelectionRange?>(null)
        private set

    var isSelecting by mutableStateOf(false)
        private set

    fun startSelection(
        row: Int,
        col: Int,
        cols: Int,
        mode: SelectionMode = SelectionMode.CHARACTER,
        snapshot: TerminalSnapshot? = null,
        scrollbackPosition: Int = 0,
    ) {
        this.mode = mode
        isSelecting = true
        selectionRange = SelectionRange(row, col, row, col)
        adjustSelectionForMode(cols, snapshot, scrollbackPosition)
    }

    fun updateSelection(row: Int, col: Int) {
        if (!isSelecting) return

        val range = selectionRange ?: return
        selectionRange = range.copy(endRow = row, endCol = col)
    }

    fun updateSelectionStart(row: Int, col: Int) {
        val range = selectionRange ?: return
        selectionRange = range.copy(startRow = row, startCol = col)
    }

    fun updateSelectionEnd(row: Int, col: Int) {
        val range = selectionRange ?: return
        selectionRange = range.copy(endRow = row, endCol = col)
    }

    fun moveSelectionUp(maxRow: Int) {
        val range = selectionRange ?: return
        if (isSelecting) {
            // During selection, move the end point up
            val newRow = (range.endRow - 1).coerceAtLeast(0)
            selectionRange = range.copy(endRow = newRow)
        } else {
            // After selection is finished, move both start and end up
            val newStartRow = (range.startRow - 1).coerceAtLeast(0)
            val newEndRow = (range.endRow - 1).coerceAtLeast(0)
            selectionRange = range.copy(startRow = newStartRow, endRow = newEndRow)
        }
    }

    fun moveSelectionDown(maxRow: Int) {
        val range = selectionRange ?: return
        if (isSelecting) {
            // During selection, move the end point down
            val newRow = (range.endRow + 1).coerceAtMost(maxRow - 1)
            selectionRange = range.copy(endRow = newRow)
        } else {
            // After selection is finished, move both start and end down
            val newStartRow = (range.startRow + 1).coerceAtMost(maxRow - 1)
            val newEndRow = (range.endRow + 1).coerceAtMost(maxRow - 1)
            selectionRange = range.copy(startRow = newStartRow, endRow = newEndRow)
        }
    }

    fun moveSelectionLeft(maxCol: Int) {
        val range = selectionRange ?: return
        if (isSelecting) {
            // During selection, move the end point left
            val newCol = (range.endCol - 1).coerceAtLeast(0)
            selectionRange = range.copy(endCol = newCol)
        } else {
            // After selection is finished, move both start and end left
            val newStartCol = (range.startCol - 1).coerceAtLeast(0)
            val newEndCol = (range.endCol - 1).coerceAtLeast(0)
            selectionRange = range.copy(startCol = newStartCol, endCol = newEndCol)
        }
    }

    fun moveSelectionRight(maxCol: Int) {
        val range = selectionRange ?: return
        if (isSelecting) {
            // During selection, move the end point right
            val newCol = (range.endCol + 1).coerceAtMost(maxCol - 1)
            selectionRange = range.copy(endCol = newCol)
        } else {
            // After selection is finished, move both start and end right
            val newStartCol = (range.startCol + 1).coerceAtMost(maxCol - 1)
            val newEndCol = (range.endCol + 1).coerceAtMost(maxCol - 1)
            selectionRange = range.copy(startCol = newStartCol, endCol = newEndCol)
        }
    }

    fun endSelection() {
        isSelecting = false
        normalizeToReadingOrder()
    }

    /**
     * Ensure `start` is the earlier endpoint in reading order (row-major:
     * top-left-most character). During a drag the range is kept in gesture
     * order — start = where the finger went down — because the update/drag
     * logic addresses the moving end by name. Once the gesture commits,
     * though, the user-facing meaning of the handles is positional: a
     * left-to-right reader expects the "start" handle on the earlier
     * character, and the handle hit-test uses the raw start/end fields. Called
     * from [endSelection]; harmless when already ordered.
     */
    internal fun normalizeToReadingOrder() {
        val r = selectionRange ?: return
        if (r.startRow > r.endRow || (r.startRow == r.endRow && r.startCol > r.endCol)) {
            selectionRange = SelectionRange(r.endRow, r.endCol, r.startRow, r.startCol)
        }
    }

    fun clearSelection() {
        mode = SelectionMode.NONE
        selectionRange = null
        isSelecting = false
    }

    fun toggleMode(cols: Int, snapshot: TerminalSnapshot? = null, scrollbackPosition: Int = 0) {
        mode = when (mode) {
            SelectionMode.CHARACTER -> SelectionMode.WORD
            SelectionMode.WORD -> SelectionMode.LINE
            SelectionMode.LINE -> SelectionMode.CHARACTER
            SelectionMode.NONE -> SelectionMode.CHARACTER
        }

        adjustSelectionForMode(cols, snapshot, scrollbackPosition)
    }

    fun setMode(newMode: SelectionMode, cols: Int, snapshot: TerminalSnapshot? = null, scrollbackPosition: Int = 0) {
        mode = newMode
        adjustSelectionForMode(cols, snapshot, scrollbackPosition)
    }

    fun selectAll(rows: Int, cols: Int) {
        mode = SelectionMode.CHARACTER
        isSelecting = false
        selectionRange = SelectionRange(0, 0, rows - 1, cols - 1)
    }

    /**
     * Shift only the fixed selection anchor while an active drag scrolls the
     * viewport underneath it. Rows intentionally are not clamped: the anchor
     * may sit outside the viewport while still resolving to a scrollback line.
     */
    fun shiftSelectionStartByRows(delta: Int) {
        val range = selectionRange ?: return
        selectionRange = range.copy(startRow = range.startRow + delta)
    }

    /** Keep both endpoints attached to their logical lines during viewport scrolling. */
    fun shiftSelectionByRows(delta: Int) {
        val range = selectionRange ?: return
        selectionRange = range.copy(
            startRow = range.startRow + delta,
            endRow = range.endRow + delta,
        )
    }

    /**
     * Clamps the selection range to the given dimensions.
     * Useful when the terminal is resized.
     */
    fun clampToDimensions(rows: Int, cols: Int) {
        val range = selectionRange ?: return
        val newStartRow = range.startRow.coerceAtMost(rows - 1)
        val newEndRow = range.endRow.coerceAtMost(rows - 1)
        val newStartCol = range.startCol.coerceAtMost(cols - 1)
        val newEndCol = range.endCol.coerceAtMost(cols - 1)

        if (newStartRow != range.startRow || newEndRow != range.endRow ||
            newStartCol != range.startCol || newEndCol != range.endCol
        ) {
            selectionRange = SelectionRange(newStartRow, newStartCol, newEndRow, newEndCol)
        }
    }

    internal fun adjustSelectionForMode(cols: Int, snapshot: TerminalSnapshot?, scrollbackPosition: Int = 0) {
        val range = selectionRange ?: return

        when (mode) {
            SelectionMode.LINE -> {
                selectionRange = range.copy(
                    startCol = 0,
                    endCol = cols - 1,
                )
            }

            SelectionMode.WORD -> {
                if (snapshot != null) {
                    val startLine = getSnapshotLine(snapshot, range.startRow, scrollbackPosition)
                    val endLine = getSnapshotLine(snapshot, range.endRow, scrollbackPosition)

                    if (startLine != null && endLine != null) {
                        val (newStartCol, _) = findWordBoundaries(startLine, range.startCol)
                        val (_, newEndCol) = findWordBoundaries(endLine, range.endCol)

                        selectionRange = range.copy(
                            startCol = newStartCol,
                            endCol = newEndCol,
                        )
                    }
                }
            }

            SelectionMode.CHARACTER, SelectionMode.NONE -> {
                // No adjustment needed
            }
        }
    }

    /**
     * Resolves a viewport-relative [row] to the underlying [TerminalLine].
     *
     * Visible rows in `[0, scrollbackPosition)` show scrollback; rows in
     * `[scrollbackPosition, rows)` show the top of the current screen. The old
     * implementation short-circuited the live-tail case (scrollbackPosition ==
     * 0) to `snapshot.lines.getOrNull(row)`, which returns null for rows that
     * map to the current screen while partially scrolled back — silently
     * dropping those rows from copied text and breaking word-boundary
     * expansion. Use the unified scrollback-aware index for every
     * scrollbackPosition so it matches [TerminalScreenState.getVisibleLine].
     */
    private fun getSnapshotLine(snapshot: TerminalSnapshot, row: Int, scrollbackPosition: Int = 0): TerminalLine? {
        val sbPos = scrollbackPosition.coerceAtLeast(0)
        val actualIndex = snapshot.scrollback.size - sbPos + row
        return if (actualIndex < snapshot.scrollback.size) {
            snapshot.scrollback.getOrNull(actualIndex)
        } else {
            snapshot.lines.getOrNull(actualIndex - snapshot.scrollback.size)
        }
    }

    private fun isWordChar(char: Char): Boolean = char.isLetterOrDigit() || char == '_'

    private fun findWordBoundaries(line: TerminalLine, col: Int): Pair<Int, Int> {
        val cells = line.cells
        if (cells.isEmpty()) return Pair(0, 0)
        val safeCol = col.coerceIn(0, cells.lastIndex)

        // If the touch is in trailing whitespace with no word to the right, snap to the last word.
        if (!isWordChar(cells[safeCol].char)) {
            val lastWordEnd = cells.indices.lastOrNull { isWordChar(cells[it].char) }
            if (lastWordEnd != null && lastWordEnd < safeCol) {
                var start = lastWordEnd
                while (start > 0 && isWordChar(cells[start - 1].char)) start--
                return Pair(start, lastWordEnd)
            }
        }

        val startChar = cells[safeCol].char
        val targetingWord = isWordChar(startChar)

        var start = safeCol
        while (start > 0 && isWordChar(cells[start - 1].char) == targetingWord) {
            start--
        }

        var end = safeCol
        while (end < cells.size - 1 && isWordChar(cells[end + 1].char) == targetingWord) {
            end++
        }

        return Pair(start, end)
    }

    private fun isBlankCell(cell: TerminalLine.Cell): Boolean = (cell.char == ' ' || cell.char == '\u0000') && cell.combiningChars.isEmpty()

    // NUL placeholder cells (e.g. TerminalLine.empty) must not leak into the
    // clipboard; map them to spaces so trimEnd()/trim() can drop them.
    private fun visibleChar(c: Char): Char = if (c == '\u0000') ' ' else c

    private fun lastContentCol(line: TerminalLine): Int {
        var last = line.cells.lastIndex
        while (last > 0 && isBlankCell(line.cells[last])) last--
        return last
    }

    fun getSelectedText(snapshot: TerminalSnapshot, scrollbackPosition: Int = 0): String {
        val range = selectionRange ?: return ""

        val minRow = minOf(range.startRow, range.endRow)
        val maxRow = maxOf(range.startRow, range.endRow)

        return buildString {
            for (row in minRow..maxRow) {
                // Viewport-relative `row` — resolve through the same
                // scrollback-aware helper as adjustSelectionForMode so rows
                // that map to the live screen while partially scrolled back are
                // not dropped from the copied text.
                val line = getSnapshotLine(snapshot, row, scrollbackPosition) ?: continue

                when (mode) {
                    SelectionMode.LINE -> {
                        // Build line text and trim trailing whitespace.
                        val lineText = buildString {
                            line.cells.forEach { cell ->
                                append(visibleChar(cell.char))
                                cell.combiningChars.forEach { append(it) }
                            }
                        }.trimEnd()
                        append(lineText)
                        if (row < maxRow && !line.softWrapped) append('\n')
                    }

                    SelectionMode.CHARACTER, SelectionMode.WORD -> {
                        val startCol = when (row) {
                            minRow -> minOf(range.startCol, range.endCol)
                            else -> 0
                        }
                        val endCol = when (row) {
                            maxRow -> maxOf(range.startCol, range.endCol)
                            else -> line.cells.size - 1
                        }

                        // Build line text and trim trailing whitespace
                        val lineText = buildString {
                            for (col in startCol..minOf(endCol, line.cells.lastIndex)) {
                                val cell = line.cells[col]
                                append(visibleChar(cell.char))
                                cell.combiningChars.forEach { append(it) }
                            }
                        }.trimEnd()
                        append(lineText)
                        if (row < maxRow && !line.softWrapped) append('\n')
                    }

                    SelectionMode.NONE -> {}
                }
            }
        }.trim()
    }

    fun isCellSelected(row: Int, col: Int, line: TerminalLine? = null): Boolean {
        val range = selectionRange ?: return false
        return when (mode) {
            SelectionMode.LINE -> {
                val minRow = minOf(range.startRow, range.endRow)
                val maxRow = maxOf(range.startRow, range.endRow)
                row in minRow..maxRow
            }

            SelectionMode.CHARACTER, SelectionMode.WORD -> {
                if (line != null && col > lastContentCol(line)) return false
                range.contains(row, col)
            }

            SelectionMode.NONE -> false
        }
    }
}
