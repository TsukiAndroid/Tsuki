package io.github.landwarderer.futon.webviewsource.ui.list

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R

/**
 * Thin host Activity for [WebViewSourceListFragment].
 *
 * Navigation entry point:
 *  [io.github.landwarderer.futon.core.nav.AppRouter.openWebViewSourceList]
 */
@AndroidEntryPoint
class WebViewSourceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_container)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, WebViewSourceListFragment.newInstance())
                .commit()
        }
    }
}
