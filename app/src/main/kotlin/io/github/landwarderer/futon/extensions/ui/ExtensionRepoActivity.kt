package io.github.landwarderer.futon.extensions.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.ui.BaseActivity
import io.github.landwarderer.futon.databinding.ActivityExtensionRepoBinding
import io.github.landwarderer.futon.extensions.ui.adapter.ExtensionRepoAdapter
import kotlinx.coroutines.launch

/**
 * Lets users add / remove extension repository index URLs.
 *
 * A repo is a URL pointing to an `index.json` file listing available extensions.
 */
@AndroidEntryPoint
class ExtensionRepoActivity : BaseActivity<ActivityExtensionRepoBinding>() {

    private val viewModel by viewModels<ExtensionRepoViewModel>()
    private var adapter: ExtensionRepoAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityExtensionRepoBinding.inflate(layoutInflater))

        setTitle(R.string.ext_repos)
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)

        adapter = ExtensionRepoAdapter(
            onRemove = { repo -> viewModel.removeRepo(repo.indexUrl) },
        )

        with(viewBinding.recyclerView) {
            layoutManager = LinearLayoutManager(this@ExtensionRepoActivity)
            adapter = this@ExtensionRepoActivity.adapter
            setHasFixedSize(false)
        }

        viewBinding.fabAddRepo.setOnClickListener {
            showAddRepoDialog()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.repos.collect { repos ->
                        adapter?.submitList(repos)
                    }
                }
                launch {
                    viewModel.errorMessage.collect { msg ->
                        if (!msg.isNullOrEmpty()) {
                            Toast.makeText(this@ExtensionRepoActivity, msg, Toast.LENGTH_LONG).show()
                            viewModel.clearError()
                        }
                    }
                }
            }
        }
    }

    override fun onApplyWindowInsets(v: android.view.View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updatePadding(bottom = bars.bottom)
        return insets
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter = null
    }

    private fun showAddRepoDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_extension_repo, null)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.edit_repo_name)
        val editUrl = dialogView.findViewById<TextInputEditText>(R.id.edit_repo_url)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ext_add_repo)
            .setView(dialogView)
            .setPositiveButton(R.string.add) { _, _ ->
                val name = editName?.text?.toString()?.trim() ?: ""
                val url = editUrl?.text?.toString()?.trim() ?: ""
                viewModel.addRepo(name, url)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
