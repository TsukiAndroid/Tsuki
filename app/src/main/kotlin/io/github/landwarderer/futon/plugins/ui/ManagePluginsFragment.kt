package io.github.landwarderer.futon.plugins.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.ui.BaseFragment
import io.github.landwarderer.futon.core.util.ext.observeEvent
import io.github.landwarderer.futon.databinding.FragmentManagePluginsBinding
import io.github.landwarderer.futon.plugins.domain.Plugin
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ManagePluginsFragment : BaseFragment<FragmentManagePluginsBinding>() {

    private val viewModel: ManagePluginsViewModel by viewModels()
    private var adapter: PluginsAdapter? = null

    // File picker for .jar import
    private val jarFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            AddPluginSheet.newInstance(initialUri = uri.toString())
                .show(parentFragmentManager, AddPluginSheet.TAG)
        }
    }

    override fun onCreateViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): FragmentManagePluginsBinding =
        FragmentManagePluginsBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Adapter
        adapter = PluginsAdapter(
            onToggle  = { plugin, enabled -> viewModel.setEnabled(plugin.id, enabled) },
            onUpdate  = { plugin -> showGithubSheet(plugin.githubRepo ?: "") },
            onDelete  = { plugin -> confirmDelete(plugin) },
        )
        viewBinding.recyclerPlugins.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter       = this@ManagePluginsFragment.adapter
        }

        // FAB → Add Plugin
        viewBinding.fabAddPlugin.setOnClickListener {
            showAddPluginOptions()
        }

        // Observe plugin list
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.plugins.collect { plugins ->
                    adapter?.submitList(plugins)
                    viewBinding.emptyView.isVisible = plugins.isEmpty()
                    viewBinding.recyclerPlugins.isVisible = plugins.isNotEmpty()
                }
            }
        }

        viewModel.onError.observeEvent(viewLifecycleOwner) { msg ->
            Snackbar.make(viewBinding.root, msg, Snackbar.LENGTH_LONG).show()
        }
        viewModel.onPluginRemoved.observeEvent(viewLifecycleOwner) { name ->
            Snackbar.make(
                viewBinding.root,
                getString(R.string.plugin_removed, name),
                Snackbar.LENGTH_SHORT,
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        adapter = null
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun showAddPluginOptions() {
        val options = arrayOf(
            getString(R.string.plugin_import_from_file),
            getString(R.string.plugin_import_from_github),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_plugin)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> jarFilePicker.launch(
                        arrayOf("application/java-archive", "application/zip", "*/*")
                    )
                    1 -> AddPluginSheet.newInstance()
                        .show(parentFragmentManager, AddPluginSheet.TAG)
                }
            }
            .show()
    }

    private fun showGithubSheet(repoUrl: String) {
        AddPluginSheet.newInstance(prefilledRepo = repoUrl)
            .show(parentFragmentManager, AddPluginSheet.TAG)
    }

    private fun confirmDelete(plugin: Plugin) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.plugin_delete_confirm, plugin.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.removePlugin(plugin.id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
