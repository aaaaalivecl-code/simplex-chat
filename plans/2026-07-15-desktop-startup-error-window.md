# Show desktop startup errors in a copyable window (#4146)

## Problem

When any exception escapes `main()` before the app window appears - a missing
DLL, a failed migration, broken AWT initialization - Windows users see only the
jpackage launcher's "Failed to launch JVM" box. The launcher runs without a
console, so stderr is lost, and no log file exists yet at that point. Every
report in #4146 stalled on exactly this ("no console output or log-files
whatsoever"); the same class of failure shipped once before as #5237 (wrong
OpenSSL DLL name, fixed by #5238) and was only diagnosable by rebuilding.

The launcher reports any nonzero exit as "Failed to launch JVM" (jpackage
`JvmLauncher.cpp`, `JP_THROW` on nonzero `JLI_Launch` status), so the dialog is
generic for the whole class: bad `java-options` in `SimpleX.cfg`, poisoned
`_JAVA_OPTIONS`, DLL load failures, or any uncaught startup exception.

## Change

Catch `Throwable` around the startup portion of `main()` and show the stack
trace in a native Win32 window before rethrowing. The window's client area is a
single read-only multiline edit control (`ES_READONLY | ES_MULTILINE | WS_VSCROLL`),
built with jna-platform's typed `User32`, which the app already ships. The text
is selectable (Ctrl+A / Ctrl+C) and scrolls, so long stack traces are fully
readable and copyable into a bug report. Nothing is written to disk.

A plain `MessageBoxW` was tried first but rejected: its text cannot be selected
(only a whole-box Ctrl+C copy, which users do not discover) and it has no
scrollbar, forcing the trace to be truncated to fit the screen.

Key placement detail: `showApp()` is inside the try block. Its first statements
(the `SystemTray.isSupported()` probe in `DesktopTray.kt`, then Compose setup)
are the process's first AWT initialization, which is itself a known startup
failure cause (#4146, fixed separately by bundling `jdk.accessibility`).
Verified empirically: a first build with `showApp()` outside the try showed no
dialog on a machine reproducing the AWT failure; moving it inside is required.

## Why this design

- Native window, not Swing: broken AWT initialization is one of the failure
  causes, so a Swing dialog would crash the same way the app did. Win32 windowing
  goes straight to `user32.dll` via JNA and is unaffected by the JVM's AWT state.
- The window uses only WM_DESTROY (to end its message loop on close); the edit
  control's own window proc provides selection, copy and scrolling, so no custom
  input handling is needed. The `WindowProc` callback is held in a local for the
  loop's lifetime so it is not garbage-collected while the window is alive.
- Windows-only dialog: on Linux/macOS the rethrown exception reaches stderr in
  the terminal; the launcher-hides-everything problem is Windows-specific.
- try/catch in `main()`, not `Thread.setDefaultUncaughtExceptionHandler`: a
  default handler would change crash handling for every thread for the app's
  whole lifetime (and would suppress the JVM's own stderr trace); the try/catch
  is scoped to startup only.
- Crashes after the window appears are out of scope: the existing
  `WindowExceptionHandler` in `showApp()` (DesktopApp.kt) already surfaces
  those in-app with a shareable stack trace and does not propagate to `main()`.
- The inner try/catch around the native window code is load-bearing: it is
  invoked inside `main()`'s catch, so an exception escaping it would suppress
  the `throw e` and replace the real startup error on stderr.
- Rethrowing keeps the nonzero exit code, so the launcher's own box still
  appears after ours; suppressing it would require lying about the exit status.

## Impact

- No behavior change when startup succeeds.
- On startup failure, Windows users get one additional window with the
  selectable stack trace before the launcher's generic box.
- The message text is hardcoded English: translated resources may not be
  loadable in the failed state this code reports on.

## Verification

- Compiles on Linux; the Linux/macOS path (rethrow to stderr) is default JVM
  behavior.
- Negative result that shaped the change: CI test MSI v1 (catch not covering
  `showApp()`) showed no dialog on a Windows 10 machine reproducing the
  assistive-technology startup failure - the crash fires at the SystemTray
  probe inside `showApp()`.
- CI test MSI v2 (this change) built; confirmation on the same repro machine
  that the dialog appears with the `AWTError` stack trace is pending.
