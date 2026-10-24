package io.github.landwarderer.futon.main.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.nav.AppRouter

class MainMenuProvider(
        private val router: AppRouter,
        private val viewModel: MainViewModel,
) : MenuProvider {

        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.opt_main, menu)
                menu.findItem(R.id.action_app_update)?.isVisible = viewModel.appUpdate.value != null
                val isNovel = viewModel.appMode.value.isNovel
                menu.findItem(R.id.action_app_mode)?.run {
                        setIcon(if (isNovel) R.drawable.ic_read else R.drawable.ic_novel)
                        setTitle(if (isNovel) R.string.content_type_manga else R.string.content_type_novel)
                }
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
                R.id.action_app_mode -> {
                        viewModel.toggleAppMode()
                        true
                }

                R.id.action_app_update -> {
                        router.openAppUpdate()
                        true
                }

                else -> false
        }
}
