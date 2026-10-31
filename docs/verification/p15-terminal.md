# P15 remote terminal

The thread menu opens a real host/thread-scoped PTY screen. Native Markdown
`command` actions route to the same screen with a command confirmation dialog;
opening the dialog never sends the command. The destination host, thread and
native-resolved cwd remain visible. Cancel consumes no input.

## Native contract

Pinned release: lib-t3-rust v0.3.7, ABI 3, source
`7cbad5d8198d37e9ded767d33ac03f2cf15875f4`, AAR SHA-256
`15d60510f170c269631eedb182ad2cfed991e4d33ce19aa3c7ea364296594843`.
The release AAR and host JNI are ignored build artifacts. The regenerated catalog
contains 261 imported strings in 20 locales; 14 hand-kept terminal strings also
exist in every locale.

- `terminal.context` resolves the selected host's worktree/project/provider from
  that host's shell snapshot. Empty fallback fails closed: no Android cwd is used.
- `terminal.attach` is used only with `terminal:operate`. Its domain helper builds
  validated args. The initial explicit open may start the shell. Reconnection,
  activity/lifecycle reentry and restored process state use `resume=true` and
  `restartIfNotRunning=false`, without an `afterSequence` cursor.
- `terminal.observe` is a distinct read-only subscription with `terminal:read`.
  Its cwd/dimensions come from the terminal snapshot; it does not require shell
  or project-read permission. It never calls attach, resize, write or open. Unsupported/denied observation
  reports a terminal error; it never falls back to an operating session.
- Every ordered event passes through `terminal.output`. Snapshots replace both
  scrollback and parser state; duplicate sequence events produce no renderer write.
  Transport closure reconnects with backoff; a PTY exit is separately displayed
  and disables input. Exit codes/signals come from the native reduction.
- Write and resize revalidate permissions through the transport. User input is
  queued in order with an attachment generation; stale queued input is discarded.
  Unknown write delivery is visible and is never retried. Resizes are serialized
  and coalesced independently of input, including the layout/attach race.
- Closing the screen cancels the subscription and IME ownership. It detaches from
  the PTY; the protocol does not expose a terminal-kill RPC. The remote shell can
  continue for Desktop or a later resume.

## Android surface

The persistent VT parser supports Unicode code points and combining marks,
wide cells, CR/LF/backspace/tab, cursor movement/save/restore, erasure, insertion,
scroll regions, primary/alternate buffers, SGR 16/256/true colors, underline,
bold/inverse, OSC discard, cursor visibility, bracketed paste and application
cursor keys. OSC cannot change the clipboard or launch links. Device/status
queries receive bounded replies only on an operating live stream, never during
snapshot replay. Scrollback is capped at 2,000 lines and 128,000 cells.

The Canvas renders backgrounds before glyphs so wide glyphs are not erased by
continuation cells. Drag vertically for history and horizontally for wider
observed terminals. Copy copies the bounded output. Paste requires confirmation
and honors bracketed-paste mode. The IME buffers composition locally, sends only
committed text, maps delete to PTY backspace, and handles hardware control keys.
The soft-key row exposes Esc, Ctrl, Tab, arrows and Return. View dimensions use
measured monospaced cells; layout, rotation and IME insets drive resize.

This is a text terminal: inline terminal graphics and mouse reporting are not
implemented. Column resize preserves the visible grid rather than reflowing old
scrollback paragraphs. Live-host shell/TUI compatibility and physical-keyboard,
OEM IME, TalkBack and RTL visual acceptance are not established by the fixtures.

## Verification

Fixture coverage lives in `TerminalEmulatorTest`, `TerminalSessionTest`,
`TerminalBindingsTest`, `TerminalNativeContractTest`, `NativeTerminalTest`,
`TerminalScreenTest` and `TerminalScreenDeviceTest`. The native WebSocket fixture
fragments a Unicode code point across real continuation frames and splits ANSI
across events. It verifies the actual observe wire tag, native output reduction,
sequence dedupe, worktree/provider context and resume args. Session fixtures cover
reconnect replacement, exited PTY, lifecycle/process resume, resize, read-only
mutations, unsupported observe and uncertain/stale input. UI fixtures cover IME
composition/deletion, layout sizing, read-only controls, confirmation and cancel.

An initial full JVM run hit the existing `MessageImagesTest` Image 199 viewport
assertion once. The unchanged fixture passed its focused rerun and subsequent
full runs; its intermittent cause remains unresolved.

Final suite results are recorded with the change. No phone or alternate checkout
is used for acceptance; only the explicitly selected emulator is instrumented.

### Final local results — 2026-10-31

Java 21 (`mise where java@temurin-21`), `ANDROID_HOME=$HOME/.android-sdk`,
`--max-workers=2`, one Gradle invocation at a time:

- `:core:test`: debug/release each 499 PASS, 5 SKIP, 0 failures.
- `:app:testDebugUnitTest`: 519 PASS, 1 SKIP, 0 failures.
- `:app:verifyRoborazziDebug`: 144 unchanged goldens, no replacements.
- `lint`: no errors; core 2 warnings, app 103 warnings and 1 hint.
- `tools/import-ot3-strings --check`: 261 keys × 20 locales; hand-kept 14 × 20 parity checked.
- `:app:assembleDebug -Pot3a.requireBridgeSdk=true`: PASS.
- Full unfiltered `:app:connectedDebugAndroidTest`, `ANDROID_SERIAL=emulator-5584`,
  API 34: 110 PASS, 9 SKIP, 0 failures. Final combined Gradle run: 6m 53s.
- 39 dedicated terminal fixtures pass (31 net additions over the diagnostic branch).

The dedicated emulator was stopped after verification. No phone or alternate
checkout was changed. Logs, counts and the APK are under the local artifact cache
`~/.cache/o3-android-artifacts/terminal-037/`. Live-host/physical-device acceptance
and the intermittent image fixture cause remain unverified as described above.
