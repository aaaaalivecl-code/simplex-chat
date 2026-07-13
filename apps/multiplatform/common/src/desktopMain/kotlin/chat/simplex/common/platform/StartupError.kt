package chat.simplex.common.platform

import com.sun.jna.platform.win32.*
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.platform.win32.WinUser.*

// Windows-only: shows a startup error the jpackage launcher otherwise hides behind "Failed to launch
// JVM" (#4146); on Linux/Mac the error rethrown by the caller reaches stderr. Uses a native window,
// not Swing, because broken AWT initialization can be the cause; the read-only edit control makes the
// stack trace selectable (Ctrl+A / Ctrl+C) and scrollable.
fun showStartupError(e: Throwable) {
  if (!desktopPlatform.isWindows()) return
  try {
    // Win32 edit controls need CRLF line endings to render multiline text
    val trace = e.stackTraceToString().replace("\n", "\r\n")
    showWindowsErrorWindow(
      "SimpleX failed to start",
      "SimpleX could not start. Select all (Ctrl+A) and copy (Ctrl+C) the error below, then " +
        "report it at https://github.com/simplex-chat/simplex-chat/issues\r\n\r\n$trace"
    )
  } catch (_: Throwable) {
    // nothing useful to do: the caller rethrows the original error, which reaches stderr
  }
}

private const val ES_MULTILINE = 0x0004
private const val ES_READONLY = 0x0800
private const val ES_AUTOVSCROLL = 0x0040
private const val CW_USEDEFAULT = 0x80000000.toInt()

// Shows a modal-style native window whose whole client area is a read-only, scrollable, selectable
// edit control. Blocks on its own message loop until the user closes the window.
private fun showWindowsErrorWindow(title: String, text: String) {
  val user32 = User32.INSTANCE
  val hInstance = Kernel32.INSTANCE.GetModuleHandle(null)
  val className = "SimpleXStartupError"

  // Only WM_DESTROY needs handling (end the loop on close); everything else is the default behavior.
  // Kept in a local so the callback is not garbage-collected while the window is alive.
  val wndProc = WindowProc { hwnd, uMsg, wParam, lParam ->
    if (uMsg == WM_DESTROY) {
      user32.PostQuitMessage(0)
      LRESULT(0)
    } else {
      user32.DefWindowProc(hwnd, uMsg, wParam, lParam)
    }
  }
  val windowClass = WNDCLASSEX()
  windowClass.cbSize = windowClass.size()
  windowClass.lpfnWndProc = wndProc
  windowClass.hInstance = hInstance
  windowClass.lpszClassName = className
  user32.RegisterClassEx(windowClass)

  val window = user32.CreateWindowEx(
    0, className, title, WS_CAPTION or WS_SYSMENU or WS_VISIBLE,
    CW_USEDEFAULT, CW_USEDEFAULT, 760, 480,
    null, null, hInstance, null
  )
  val clientArea = RECT()
  user32.GetClientRect(window, clientArea)
  // The edit control's initial text is its window title, so no separate WM_SETTEXT is needed.
  user32.CreateWindowEx(
    0, "EDIT", text,
    WS_CHILD or WS_VISIBLE or WS_VSCROLL or ES_MULTILINE or ES_READONLY or ES_AUTOVSCROLL,
    0, 0, clientArea.right, clientArea.bottom,
    window, null, hInstance, null
  )
  user32.ShowWindow(window, SW_SHOWNORMAL)
  user32.SetForegroundWindow(window)

  val msg = MSG()
  while (user32.GetMessage(msg, null, 0, 0) > 0) {
    user32.TranslateMessage(msg)
    user32.DispatchMessage(msg)
  }
}
