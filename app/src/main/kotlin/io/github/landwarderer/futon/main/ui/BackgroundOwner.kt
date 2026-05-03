package io.github.landwarderer.futon.main.ui

/**
 * Implemented by [MainActivity]. Fragments that load a background image call
 * [setActivityBackground] after their image loads so the image also shows behind
 * the transparent AppBar / SearchBar area. Call [clearActivityBackground] when
 * the background should no longer be shown (handled automatically by the tab
 * change listener in MainActivity).
 */
interface BackgroundOwner {
    fun setActivityBackground(url: String?)
    fun clearActivityBackground()
}
