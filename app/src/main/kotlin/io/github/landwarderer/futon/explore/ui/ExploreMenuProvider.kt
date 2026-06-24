package io.github.landwarderer.futon.explore.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import androidx.fragment.app.FragmentManager
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.nav.AppRouter
import io.github.landwarderer.futon.customsource.ui.AddCustomSourceSheet
import io.github.landwarderer.futon.browsersource.ui.AddBrowserSourceSheet
import io.github.landwarderer.futon.customsource.ui.ImportParserSheet
import io.github.landwarderer.futon.customsource.ui.visualpicker.VisualRuleBuilderActivity
class ExploreMenuProvider(
        private val router: AppRouter,
        private val fragmentManager: FragmentManager,
        private val onFilterLanguages: () -> Unit,
) : MenuProvider {

        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.opt_explore, menu)
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                        R.id.action_filter_language -> {
                                onFilterLanguages()
                                true
                        }

                        R.id.action_manage -> {
                                router.openSourcesSettings()
                                true
                        }

                        R.id.action_add_custom_source -> {
                                AddCustomSourceSheet.newInstance()
                                        .show(fragmentManager, AddCustomSourceSheet.TAG)
                                true
                        }

                        R.id.action_import_parser -> {
                                ImportParserSheet.newInstance()
                                        .show(fragmentManager, ImportParserSheet.TAG)
                                true
                        }

                        R.id.action_manage_extensions -> {
                                router.openExtensions()
                                true
                        }

                        R.id.action_add_browser_source -> {
                                AddBrowserSourceSheet.newInstance()
                                        .show(fragmentManager, AddBrowserSourceSheet.TAG)
                                true
                        }

                        else -> false
                }
        }
}
