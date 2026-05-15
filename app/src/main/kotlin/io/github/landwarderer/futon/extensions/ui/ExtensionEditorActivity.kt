package io.github.landwarderer.futon.extensions.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.ui.BaseActivity
import io.github.landwarderer.futon.databinding.ActivityExtensionEditorBinding
import io.github.landwarderer.futon.extensions.domain.ExtensionType
import kotlinx.coroutines.launch

/**
 * Full-screen in-app code editor for JS and Dart extensions.
 *
 * The toolbar has three actions:
 *  - Save  — persists the current code via [ExtensionEditorViewModel.saveCode].
 *  - Test  — toggles the Test Console panel at the bottom of the screen.
 *  - Guide — shows a dialog explaining the JS extension contract.
 *
 * The Test Console lets the user pick one of the three required functions
 * (getMangaList / getMangaDetails / getChapterPages), supply an optional
 * argument, then run the current editor code against QuickJS and see the
 * JSON output or error message immediately — no install or device required.
 *
 * Only reachable for [ExtensionType.JS] and [ExtensionType.DART] extensions.
 */
@AndroidEntryPoint
class ExtensionEditorActivity : BaseActivity<ActivityExtensionEditorBinding>() {

    private val viewModel by viewModels<ExtensionEditorViewModel>()
    private var extensionId: String = ""
    private var testConsoleVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityExtensionEditorBinding.inflate(layoutInflater))

        extensionId = intent.getStringExtra(EXTRA_EXTENSION_ID) ?: run {
            finish()
            return
        }

        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)

        viewModel.load(extensionId)

        addMenuProvider(EditorMenuProvider())
        setupTestConsole()
        observeViewModel()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.extension.collect { ext ->
                        if (ext == null) return@collect
                        setTitle(getString(R.string.ext_editor_title, ext.name))
                        if (viewBinding.editCode.text.isNullOrEmpty()) {
                            viewBinding.editCode.setText(ext.sourceCode)
                            viewBinding.editCode.setSelection(0)
                        }
                    }
                }
                launch {
                    viewModel.saved.collect { saved ->
                        if (saved) {
                            Toast.makeText(
                                this@ExtensionEditorActivity,
                                getString(R.string.ext_editor_saved),
                                Toast.LENGTH_SHORT,
                            ).show()
                            viewModel.clearSaved()
                        }
                    }
                }
                launch {
                    viewModel.testState.collect { state ->
                        when (state) {
                            is TestState.Idle -> {
                                viewBinding.textTestOutput.text =
                                    getString(R.string.ext_test_output_hint)
                                viewBinding.btnRunTest.isEnabled = true
                            }
                            is TestState.Running -> {
                                viewBinding.textTestOutput.text =
                                    getString(R.string.ext_test_running)
                                viewBinding.btnRunTest.isEnabled = false
                            }
                            is TestState.Success -> {
                                viewBinding.textTestOutput.text = state.output
                                viewBinding.btnRunTest.isEnabled = true
                            }
                            is TestState.Failure -> {
                                viewBinding.textTestOutput.text =
                                    getString(R.string.ext_test_error_prefix, state.message)
                                viewBinding.btnRunTest.isEnabled = true
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupTestConsole() {
        // Update the argument hint when the function chip selection changes
        viewBinding.chipGroupFunction.setOnCheckedStateChangeListener { _, checkedIds ->
            val hint = when {
                checkedIds.contains(R.id.chip_list) ->
                    getString(R.string.ext_test_arg_hint_list)
                else ->
                    getString(R.string.ext_test_arg_hint_url)
            }
            viewBinding.layoutTestArg.hint = hint
        }

        viewBinding.btnRunTest.setOnClickListener {
            val code = viewBinding.editCode.text?.toString() ?: return@setOnClickListener
            val fnName = when (viewBinding.chipGroupFunction.checkedChipId) {
                R.id.chip_details -> "getMangaDetails"
                R.id.chip_pages   -> "getChapterPages"
                else              -> "getMangaList"
            }
            val arg = viewBinding.editTestArg.text?.toString().orEmpty()
            viewModel.runTest(code, fnName, arg)
        }
    }

    private fun toggleTestConsole() {
        testConsoleVisible = !testConsoleVisible
        val vis = if (testConsoleVisible) View.VISIBLE else View.GONE
        viewBinding.testPanel.visibility = vis
        viewBinding.testDivider.visibility = vis
        if (!testConsoleVisible) viewModel.clearTestState()
    }

    private fun showGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ext_guide_title))
            .setMessage(getString(R.string.ext_guide_body))
            .setPositiveButton(getString(R.string.ext_guide_ok), null)
            .show()
    }

    override fun onApplyWindowInsets(v: android.view.View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updatePadding(bottom = bars.bottom)
        return insets
    }

    private inner class EditorMenuProvider : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.opt_extension_editor, menu)
        }

        override fun onMenuItemSelected(item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.action_save_code -> {
                    val code = viewBinding.editCode.text?.toString() ?: ""
                    viewModel.saveCode(extensionId, code)
                    true
                }
                R.id.action_toggle_test_console -> {
                    toggleTestConsole()
                    true
                }
                R.id.action_show_guide -> {
                    showGuideDialog()
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        const val EXTRA_EXTENSION_ID = "extension_id"
    }
}
