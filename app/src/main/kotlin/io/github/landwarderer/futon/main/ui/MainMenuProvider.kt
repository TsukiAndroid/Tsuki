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
                if (viewModel.appUpdate.value != null) {
                        menuInflater.inflate(R.menu.opt_main, menu)
                }
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
                R.id.action_app_update -> {
                        router.openAppUpdate()
                        true
                }

                else -> false
        }
}
