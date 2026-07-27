package io.github.landwarderer.futon.plugins.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.ui.BaseActivity
import io.github.landwarderer.futon.core.util.ext.consumeAllSystemBarsInsets
import io.github.landwarderer.futon.core.util.ext.systemBarsInsets
import io.github.landwarderer.futon.databinding.ActivityManagePluginsBinding

/**
 * Container activity for [ManagePluginsFragment].
 *
 * Entry point: Explore → ⋮ menu → Manage Plugins
 * Also accessible via Settings → Sources → Plugins → Manage Plugins
 */
@AndroidEntryPoint
class ManagePluginsActivity : BaseActivity<ActivityManagePluginsBinding>() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityManagePluginsBinding.inflate(layoutInflater))
        setTitle(R.string.manage_plugins)
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, ManagePluginsFragment())
                .commit()
        }
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val bars = insets.systemBarsInsets
        viewBinding.root.updatePadding(top = bars.top)
        return insets.consumeAllSystemBarsInsets()
    }
}
