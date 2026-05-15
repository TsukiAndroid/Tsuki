package io.github.landwarderer.futon.extensions.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
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
 * Opens with the extension's current source code pre-filled in a monospaced
 * editable field. The "Save" toolbar action persists changes back through
 * [ExtensionEditorViewModel].
 *
 * Only reachable for [ExtensionType.JS] and [ExtensionType.DART] extensions;
 * other types do not have editable source code.
 */
@AndroidEntryPoint
class ExtensionEditorActivity : BaseActivity<ActivityExtensionEditorBinding>() {

    private val viewModel by viewModels<ExtensionEditorViewModel>()
    private var extensionId: String = ""

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
            }
        }
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
                else -> false
            }
        }
    }

    companion object {
        const val EXTRA_EXTENSION_ID = "extension_id"
    }
}
