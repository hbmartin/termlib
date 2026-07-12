# Public API changes since `b6b2d21` (2026-06-15)

This document describes every public API addition and behavioral change in the
`org.connectbot.terminal` library since commit
`b6b2d215f0a068fd885f5b64c83db041f81b7d23`. It is written for applications that
embed the library, with particular attention to features useful for tmux-heavy
workflows and to underlying behavioral changes you should be aware of when
upgrading.

## Summary

| Change | Type | Surface |
|---|---|---|
| `TerminalEmulator` implements `AutoCloseable`; new `close()` | New API | `TerminalEmulator` |
| `onCommandFinished` callback (OSC 133 command duration) | New API | `TerminalEmulatorFactory.create` |
| `minUpdateIntervalMs` snapshot throttling | New API | `TerminalEmulatorFactory.create` |
| `cursorBlinkMode` parameter + new `CursorBlinkMode` type | New API | `Terminal` composable |
| `textAntiAlias` parameter | New API | `Terminal` composable |
| `isSelectionExtending` property | New API | `SelectionController` |
| `getSelectedText()` | New API | `SelectionController` |
| `getSnapshotLineTexts()` and `isAltScreenActive()` | New API | `TerminalEmulator` |
| `maxScrollbackLines` factory parameter | New API | `TerminalEmulatorFactory.create` |
| `onPasteShortcut` composable callback | New API | `Terminal` |
| Write/mutate/read contract after `close()` | Behavior | `TerminalEmulator` |
| `resize`/`create` reject non-positive dimensions | Behavior | `TerminalEmulator`, factory |
| Atomic dimensions; per-call `onResize` values | Behavior | `TerminalEmulator` |
| Enter no longer permanently swallowed by a finished selection | Behavior | Keyboard/IME input |
| `\r` and `\r\n` in typed/committed text dispatch as Enter | Behavior | Keyboard/IME input |
| Copied text never contains NUL placeholder characters | Behavior | Selection/clipboard |

---

## New API

### `TerminalEmulator` is now `AutoCloseable`

`TerminalEmulator` gained a `close()` method and extends `AutoCloseable`.
Previously there was no way to release the native terminal; the emulator (and
its native libvterm instance) lived until process death. Applications should
now close the emulator when the session ends — in a ViewModel's `onCleared()`,
a Service's `onDestroy()`, or a `DisposableEffect`'s `onDispose`:

```kotlin
class SessionViewModel : ViewModel() {
    val emulator = TerminalEmulatorFactory.create(/* ... */)

    override fun onCleared() {
        emulator.close()
    }
}
```

`close()` is idempotent — calling it more than once is safe. It releases the
native terminal, cancels any pending snapshot work (both the throttled handler
callback and the Choreographer frame callback), and clears queued damage so no
snapshot is emitted after close.

**The post-close contract has three tiers** (see the behavioral section below
for rationale):

1. **Writes are silently dropped.** Both `writeInput` overloads become no-ops
   after `close()`. PTY reader threads routinely race view teardown, and late
   output from a dying session is not a programming error.
2. **Other mutations throw.** `resize`, `dispatchKey`, `dispatchCharacter`,
   `applyColorScheme`, and `setDefaultColors` throw `IllegalStateException`
   after close — calling these on a closed emulator is a genuine bug in the
   caller.
3. **Reads keep working.** `snapshot`, `dimensions`, `getUrls()`, and
   `getLastCommandOutput()` continue to return the last state emitted before
   close, so UI that is still tearing down can render safely.

### `onCommandFinished` — shell-integration command completion with duration

`TerminalEmulatorFactory.create` gained an optional callback:

```kotlin
onCommandFinished: ((durationMs: Long) -> Unit)? = null
```

It fires when the shell reports command completion via the OSC 133;D
shell-integration sequence. The argument is how long the command ran, in
milliseconds:

- Measured from **OSC 133;C** (command execution start) to **OSC 133;D**
  (command finished) when the shell emits C — this covers execution time only.
- Falls back to measuring from **OSC 133;B** (command input start) if C was
  never seen, in which case the duration includes the time the user spent
  typing the command.
- **`-1`** if no start marker was seen at all (e.g. the emulator attached
  mid-session and only saw the D). The value is otherwise coerced to be
  non-negative, so `-1` is unambiguous as a sentinel.

**Ordering guarantee:** the callback is invoked *after* the snapshot containing
the finished command's output has been emitted. That means it is safe — and
intended — to call `getLastCommandOutput()` from inside the callback to read
what the command printed:

```kotlin
val emulator = TerminalEmulatorFactory.create(
    onKeyboardInput = { bytes -> pty.write(bytes) },
    onCommandFinished = { durationMs ->
        val output = emulator.getLastCommandOutput()
        if (durationMs >= LONG_RUNNING_THRESHOLD_MS) {
            notifyCommandDone(durationMs, output)
        }
    },
)
```

Typical uses: "command finished" notifications for backgrounded sessions,
per-command timing display in the app chrome, and capturing command output for
history or sharing.

The callback runs on the `Looper` passed to `create` (the main looper by
default), and is not invoked after `close()`.

### `minUpdateIntervalMs` — snapshot emission throttling

`TerminalEmulatorFactory.create` gained:

```kotlin
minUpdateIntervalMs: Long = 0L
```

This sets a minimum interval, in milliseconds, between snapshot emissions:

- **`0` (default):** updates coalesce to display-frame cadence via the
  Choreographer, exactly as before. No behavior change for existing callers.
- **`> 0`:** snapshot work is deferred until at least that long after the
  previous emission, batching bursts of terminal output into fewer, larger
  redraws.

Damage is never dropped, only deferred — the final state is always emitted, so
the screen never sticks in a stale state. The first update after creation is
never deferred.

This was added primarily for e-ink displays, where frequent partial refreshes
cause ghosting, but it is equally useful to cap redraw work for any
fast-scrolling output — a build spewing logs, `yes`, or a busy tmux pane
repainting its scroll region:

```kotlin
val emulator = TerminalEmulatorFactory.create(
    // ~4 redraws per second regardless of how fast output arrives
    minUpdateIntervalMs = 250L,
    /* ... */
)
```

### `cursorBlinkMode` on the `Terminal` composable + `CursorBlinkMode`

A new public sealed class and a matching `Terminal` composable parameter:

```kotlin
sealed class CursorBlinkMode {
    data object Terminal : CursorBlinkMode()  // default
    data object Never : CursorBlinkMode()
}
```

```kotlin
Terminal(
    terminalEmulator = emulator,
    cursorBlinkMode = CursorBlinkMode.Never,
)
```

- `CursorBlinkMode.Terminal` (default): cursor blink follows the terminal
  program's escape sequences (DECSCUSR / DEC private mode 12) — identical to
  the previous behavior.
- `CursorBlinkMode.Never`: the cursor is drawn solid whenever visible,
  regardless of what the program requests. Intended for e-ink displays, where
  a blinking cursor causes continuous partial refreshes.

### `textAntiAlias` on the `Terminal` composable

```kotlin
Terminal(
    terminalEmulator = emulator,
    textAntiAlias = false,
)
```

Defaults to `true` (unchanged behavior). Set to `false` for crisp
black-and-white glyph edges on e-ink or other 1-bit displays.

Together, `minUpdateIntervalMs`, `CursorBlinkMode.Never`, and
`textAntiAlias = false` form the library's e-ink rendering package.

### `SelectionController.isSelectionExtending`

The public `SelectionController` interface gained a property:

```kotlin
val isSelectionExtending: Boolean
    get() = false
```

It is `true` while a selection is still being extended — a keyboard-driven
selection before `finishSelection()`, or a touch drag in progress. A finished
selection remains `isSelectionActive` (still highlighted, still copyable) but
is no longer extending.

The property has a default implementation returning `false`, so existing
custom `SelectionController` implementations compile unchanged. However, if
you provide your own controller you should override it: the new Enter-key
handling (below) uses this distinction, and without an override Enter will
always clear your selection and pass through to the terminal rather than
finishing an in-progress keyboard selection.

### Selection text without clipboard side effects

`SelectionController` now exposes:

```kotlin
fun getSelectedText(): String
```

Unlike `copySelection()`, this reads the logical selected text without writing
to the clipboard or clearing the selection. The default implementation returns
an empty string so existing custom controllers remain source-compatible.

### Snapshot line text and alternate-screen state

`TerminalEmulator` gained two read-only accessors:

```kotlin
fun getSnapshotLineTexts(): List<String>
fun isAltScreenActive(): Boolean
```

`getSnapshotLineTexts()` returns a fresh list containing the current visible
snapshot lines. `isAltScreenActive()` reports the snapshot-published alternate
screen state, so it is safe for UI-thread gesture or status decisions.

### Configurable scrollback length

`TerminalEmulatorFactory.create` now accepts:

```kotlin
maxScrollbackLines: Int = 1000
```

The previous 1000-line behavior remains the default. Negative values are
coerced to zero.

### Ctrl+Shift+V paste hook

The `Terminal` composable now accepts `onPasteShortcut`. When non-null,
Ctrl+Shift+V invokes this callback and consumes the key. When null, the key
combination is forwarded to the remote terminal unchanged. This is separate
from `onPasteRequest`, which remains the selection-toolbar paste action.

---

## Behavioral changes

### Lifecycle: teardown races are no longer fatal

Alongside the new `close()`, all callbacks from the native layer (`damage`,
`moverect`, cursor moves, bell, scrollback pushes, OSC sequences, keyboard
echo) are ignored once the emulator is closed, and pending callbacks queued on
the handler are cancelled. Late `onBell`, `onKeyboardInput`, `onClipboardCopy`,
`onProgressChange`, `onResize`, and `onCommandFinished` invocations will not
fire after `close()` returns.

The practical consequence for apps: **you no longer need to stop your PTY
reader thread before tearing down the terminal.** A reader thread that is
mid-`writeInput` when the session closes is safe; its data is dropped.

### Dimension validation

- `TerminalEmulatorFactory.create` throws `IllegalArgumentException` if
  `initialRows` or `initialCols` is not positive.
- `resize(newRows, newCols)` throws `IllegalArgumentException` if either value
  is not positive.

Previously such values were passed through to the native layer. If your layout
code can produce a transient `0×0` measurement (common during the first
Compose layout pass), guard the `resize` call.

### Atomic dimensions and exact resize callbacks

`TerminalEmulator.dimensions` is now published atomically as a single
`TerminalDimensions` value. A reader on another thread can no longer observe a
torn pair (new rows with old columns) mid-resize.

Two related guarantees:

- **`onResize` reports the dimensions of that specific resize call.** Each
  callback captures its own `TerminalDimensions`; a later resize can no longer
  change the value an earlier queued callback delivers. If you forward
  `onResize` to a PTY `TIOCSWINSZ`, every resize in a rapid burst reports
  faithfully.
- **`TerminalSnapshot.rows`/`cols` are consistent with the snapshot's line
  list.** The dimensions and the cached lines are read together under the same
  lock, so a snapshot can no longer claim one size while carrying a line list
  of another.

### Enter key vs. selection: no more swallowed Enter

Previously, pressing Enter while any selection was active "finished" the
selection and consumed the key — every time. A touch selection (which finishes
when the finger lifts, but stays highlighted for copying) would therefore
swallow Enter *forever* until the user tapped elsewhere. Users saw a shell
that ignored the Enter key.

New behavior, applied consistently across hardware key events, single
character input, and IME text/committed-text input:

- If a selection is **still extending** (keyboard-driven selection before
  `finishSelection()`): Enter finishes it — stops extending, keeps it
  highlighted for copying — and is consumed, like classic copy-mode.
- If a selection is **finished but still highlighted**: Enter clears the
  selection and passes through to the terminal as a normal Enter.
- Any other key still clears the selection and passes through, and Escape
  still cancels the selection.

Compose-mode (dead-key composition) input is intercepted *before* the
selection guard on all input paths, so composing a character no longer
interacts with selection state inconsistently between paths.

### `\r` and `\r\n` now dispatch as the Enter key

In text delivered through the IME paths (`onTextInput`, committed text) and
single-character input, line terminators are normalized to Enter key events:

- `\n`, `\r`, and `\r\n` each dispatch a single `VTermKey.ENTER`. Previously
  only `\n` did; a bare `\r` was dispatched as a literal character.
- A `\r\n` pair within one call collapses into one Enter. (A pair split across
  two separate calls counts as two Enters — real IMEs commit CRLF atomically,
  so this doesn't occur in practice.)

If your app programmatically injects command strings (a "send command" button,
snippet insertion, `tmux send-keys`-style automation) using `\r` as the
terminator, the terminal now receives a proper Enter key event. This matters
under modes where Enter encodes differently from a raw carriage return, such
as the kitty keyboard protocol — programs like recent tmux, neovim, and fish
that enable it will now see Enter correctly instead of a stray `\r` character.

IME text is also NFC-normalized before dispatch (unchanged), and the
selection transition described above is applied per line-terminator token
within the committed text.

### Copied text never contains NUL characters

Cells that have never been written hold a NUL (`\u0000`) placeholder
internally.
Selection copy now maps these to spaces so trailing-blank trimming works and
NUL bytes can no longer leak into the clipboard. If a user selects across
never-written regions, pasted text now contains plain spaces/nothing instead
of invisible NULs (which previously could truncate pastes or corrupt shell
input).

### OSC 133 output regions and command duration tracking

The OSC 133 parser now records command start time at `B` (command input
start), refines it at `C` (execution start), and computes the duration at `D`
— feeding the new `onCommandFinished` callback. Parsing of segments for
`getLastCommandOutput()` is unchanged.

---

## Using the new API with tmux

Several of these changes were driven by tmux-style workloads:

**Command tracking inside tmux.** `getLastCommandOutput()` and the new
`onCommandFinished` rely on OSC 133 sequences reaching the emulator. When the
shell runs directly on the PTY, configuring shell integration (e.g. the OSC
133 hooks emitted by fish, or the zsh/bash snippets used by iTerm2/WezTerm/
VS Code) is enough. When the user runs tmux *inside* the session, tmux
consumes escape sequences from inner panes; the inner shell's OSC 133 marks
only reach the emulator if they are explicitly passed through (tmux's
`allow-passthrough` option plus DCS-wrapped sequences from the inner shell's
integration hooks). Design your UI to degrade gracefully — treat a `-1`
duration and a `null` `getLastCommandOutput()` as "no shell integration
available".

**Status bars and scroll regions.** The emulator limits semantic-segment
shifting to the active scroll region, so command/output tracking survives
tmux-style layouts where a status bar sits outside the scrolled area. The new
snapshot-consistency guarantees (atomic dimensions, lines matching
`rows`/`cols`) remove a class of transient mis-rendering during pane resizes.

**Resize storms.** Dragging a split or rotating the device produces rapid
resize sequences. `onResize` now reports each resize's exact dimensions, so
forwarding them to the PTY (and from there, via SIGWINCH, to tmux) is reliable
even when calls are queued. Remember that `resize` now throws on non-positive
dimensions — clamp layout-derived values.

**Fast-repainting panes.** tmux redraws whole regions on pane switches,
`clear`, and busy output. If redraw cost matters on your target hardware
(e-ink readers especially), set `minUpdateIntervalMs` to batch these repaints;
no output is lost, and the final frame is always rendered.

**Copy-mode-like selection.** Enter now finishes an in-progress
keyboard-driven selection (mirroring tmux copy-mode's Enter-to-yank feel) but
passes through to the shell when the selection has already been finished by a
touch gesture — so a leftover highlight never blocks the Enter key.

---

## Migration checklist

1. **Close your emulators.** Add `emulator.close()` to your session teardown
   path (`onCleared`, `onDestroy`, or `DisposableEffect`). Not closing leaks
   the native terminal, as it always did — but now you have the API to fix it.
2. **Don't call mutators after close.** `resize`, `dispatchKey`,
   `dispatchCharacter`, `applyColorScheme`, and `setDefaultColors` now throw
   `IllegalStateException` on a closed emulator. `writeInput` is safe (dropped
   silently), so PTY reader threads need no changes.
3. **Guard against zero dimensions.** `create` and `resize` throw
   `IllegalArgumentException` for non-positive rows/cols.
4. **If you implement `SelectionController`**, override the new
   `isSelectionExtending` property so Enter can distinguish an in-progress
   keyboard selection from a finished one.
5. **If you inject text ending in `\r`**, be aware it now dispatches as an
   Enter key event rather than a literal carriage-return character. For plain
   shells the bytes on the PTY are the same; under kitty-keyboard-protocol
   consumers the encoding is now correct where it was previously wrong.
6. **Optionally adopt** `onCommandFinished` (+ `getLastCommandOutput()` inside
   it), `minUpdateIntervalMs`, `maxScrollbackLines`, `cursorBlinkMode`,
   `textAntiAlias`, `onPasteShortcut`, and the new read-only text/alt-screen
   accessors where they benefit your app.
