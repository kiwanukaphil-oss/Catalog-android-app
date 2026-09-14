package com.kline.pilot.ui

/** Route app-level navigation through the active editor's unsaved-draft choice without retaining its draft itself. */
class WorkspaceNavigationGuard {
    var intercept: (((() -> Unit)) -> Unit)? = null
    fun navigate(action: () -> Unit) { intercept?.invoke(action) ?: action() }
}
