package io.github.landwarderer.futon.extensions.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.ui.BaseActivity
import io.github.landwarderer.futon.databinding.ActivityExtensionsBinding
import io.github.landwarderer.futon.extensions.ui.adapter.ExtensionsAdapter
import kotlinx.coroutines.launch

/**
 * Displays installed extensions and extensions available from repos.
 *
 * Separate from the existing Mihon ExtensionDownloaderActivity — this is the
 * new unified extension manager for JS, Dart, Mihon-APK, and JSON-template extensions.
 */
@AndroidEntryPoint
class ExtensionsActivity : BaseActivity<ActivityExtensionsBinding>() {

    private val viewModel by viewModels<ExtensionsViewModel>()
    private var adapter: ExtensionsAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityExtensionsBinding.inflate(layoutInflater))

        setTitle(R.string.ext_extensions)
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)

        adapter = ExtensionsAdapter(
            onEnableToggle = { id, enabled -> viewModel.setEnabled(id, enabled) },
            onDelete = { id -> viewModel.delete(id) },
            onInstall = { available -> viewModel.installFromAvailable(available) },
        )

        with(viewBinding.recyclerView) {
            layoutManager = LinearLayoutManager(this@ExtensionsActivity)
            adapter = this@ExtensionsActivity.adapter
            setHasFixedSize(true)
        }

        viewBinding.btnImport.setOnClickListener {
            ImportExtensionSheet.newInstance()
                .show(supportFragmentManager, ImportExtensionSheet.TAG)
        }

        viewBinding.btnRefresh.setOnClickListener {
            viewModel.refreshAvailable()
        }

        addMenuProvider(ExtensionsMenuProvider())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.installedExtensions.collect { installed ->
                        adapter?.setInstalled(installed)
                        viewBinding.emptyState.isVisible = installed.isEmpty() &&
                            (adapter?.itemCount ?: 0) == 0
                    }
                }
                launch {
                    viewModel.availableExtensions.collect { available ->
                        adapter?.setAvailable(available)
                    }
                }
                launch {
                    viewModel.isLoading.collect { loading ->
                        viewBinding.progressBar.isVisible = loading
                    }
                }
                launch {
                    viewModel.errorMessage.collect { msg ->
                        if (!msg.isNullOrEmpty()) {
                            Toast.makeText(this@ExtensionsActivity, msg, Toast.LENGTH_LONG).show()
                            viewModel.clearError()
                        }
                    }
                }
            }
        }

        viewModel.refreshAvailable()
    }

    override fun onApplyWindowInsets(v: android.view.View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updatePadding(bottom = systemBars.bottom)
        return insets
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter = null
    }

    private inner class ExtensionsMenuProvider :
        MenuProvider,
        MenuItem.OnActionExpandListener,
        SearchView.OnQueryTextListener {

        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.opt_extensions, menu)
            val searchMenuItem = menu.findItem(R.id.action_search)
            searchMenuItem?.setOnActionExpandListener(this)
            val searchView = searchMenuItem?.actionView as? SearchView
            searchView?.setOnQueryTextListener(this)
            searchView?.setIconifiedByDefault(false)
            searchView?.queryHint = getString(R.string.search)
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            if (menuItem.itemId == R.id.action_manage_repos) {
                startActivity(android.content.Intent(this@ExtensionsActivity, ExtensionRepoActivity::class.java))
                return true
            }
            return false
        }

        override fun onMenuItemActionExpand(item: MenuItem) = true
        override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
            (item.actionView as? SearchView)?.setQuery("", false)
            return true
        }

        override fun onQueryTextSubmit(query: String?) = false
        override fun onQueryTextChange(newText: String?): Boolean {
            viewModel.performSearch(newText)
            return true
        }
    }
}
