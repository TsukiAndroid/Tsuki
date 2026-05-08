package io.github.landwarderer.futon.core.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentManager
import androidx.viewbinding.ViewBinding
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import io.github.landwarderer.futon.BuildConfig
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.exceptions.resolve.ExceptionResolver
import io.github.landwarderer.futon.core.nav.AppRouter
import io.github.landwarderer.futon.core.prefs.AppSettings
import io.github.landwarderer.futon.core.ui.util.ActionModeDelegate
import io.github.landwarderer.futon.core.ui.util.SystemUiController
import io.github.landwarderer.futon.core.util.ext.isWebViewUnavailable
import io.github.landwarderer.futon.main.ui.protect.ScreenshotPolicyHelper
import androidx.appcompat.R as appcompatR

abstract class BaseActivity<B : ViewBinding> :
        AppCompatActivity(),
        OnApplyWindowInsetsListener,
        ScreenshotPolicyHelper.ContentContainer {

        private var isAmoledTheme = false

        lateinit var viewBinding: B
                private set

        protected lateinit var exceptionResolver: ExceptionResolver
                private set

        @JvmField
        val actionModeDelegate = ActionModeDelegate()

        private lateinit var entryPoint: BaseActivityEntryPoint

        /**
         * Access the app-wide [AppSettings] singleton from within [BaseActivity] or any subclass
         * without having to re-inject it (the entry-point is resolved once in [attachBaseContext]).
         */
        protected val baseSettings: AppSettings
                get() = entryPoint.settings

        /**
         * Controls visibility of the system bars (status bar + navigation bar).
         *
         * Initialised in [onCreate] after [super.onCreate] so the decor view and
         * [WindowInsetsController] are fully ready.  Subclasses may call
         * [SystemUiController.setSystemUiVisible] at any time after [onCreate] returns.
         *
         * [BaseFullscreenActivity] overrides [applyImmersiveMode] so that the reader
         * can manage bar visibility independently of the global app-wide setting.
         */
        protected lateinit var systemUiController: SystemUiController

        override fun attachBaseContext(newBase: Context) {
                entryPoint = EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(newBase.applicationContext)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        AppCompatDelegate.setApplicationLocales(entryPoint.settings.appLocales)
                }
                super.attachBaseContext(newBase)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
                val settings = entryPoint.settings
                isAmoledTheme = settings.isAmoledTheme
                setTheme(settings.colorScheme.styleResId)
                if (isAmoledTheme) {
                        setTheme(R.style.ThemeOverlay_Tsuki_Amoled)
                }
                putDataToExtras(intent)
                exceptionResolver = entryPoint.exceptionResolverFactory.create(this)
                enableEdgeToEdge()
                super.onCreate(savedInstanceState)
                // Initialise after super so the decor view / WindowInsetsController is ready.
                systemUiController = SystemUiController(window)
        }

        override fun onPostCreate(savedInstanceState: Bundle?) {
                super.onPostCreate(savedInstanceState)
                onBackPressedDispatcher.addCallback(actionModeDelegate)
        }

        override fun onNewIntent(intent: Intent) {
                putDataToExtras(intent)
                super.onNewIntent(intent)
        }

        /**
         * Re-apply the global immersive mode whenever the window regains focus
         * (e.g., after dismissing a dialog or pulling down the notification shade).
         * This keeps bars hidden even when the system temporarily shows them.
         *
         * [BaseFullscreenActivity] overrides this to a no-op so the reader can
         * toggle its own toolbar independently.
         */
        override fun onWindowFocusChanged(hasFocus: Boolean) {
                super.onWindowFocusChanged(hasFocus)
                if (hasFocus) {
                        applyImmersiveMode()
                }
        }

        /**
         * Apply (or remove) the global app-wide immersive mode.
         *
         * When [AppSettings.isAppImmersiveModeEnabled] is true the status bar and
         * navigation bar are hidden using sticky-immersive flags on API 23–29 and
         * [WindowInsetsController] on API 30+.  A one-finger swipe from any edge
         * temporarily reveals the bars; they auto-hide again once the user stops
         * interacting.
         *
         * Override in subclasses that manage their own bar visibility (e.g. the reader).
         */
        protected open fun applyImmersiveMode() {
                systemUiController.setSystemUiVisible(!baseSettings.isAppImmersiveModeEnabled)
        }

        @Deprecated("Use ViewBinding", level = DeprecationLevel.ERROR)
        override fun setContentView(layoutResID: Int) = throw UnsupportedOperationException()

        @Deprecated("Use ViewBinding", level = DeprecationLevel.ERROR)
        override fun setContentView(view: View?) = throw UnsupportedOperationException()

        protected fun setContentView(binding: B) {
                this.viewBinding = binding
                super.setContentView(binding.root)
                ViewCompat.setOnApplyWindowInsetsListener(binding.root, this)
                val toolbar = (binding.root.findViewById<View>(R.id.toolbar) as? Toolbar)
                toolbar?.let(this::setSupportActionBar)
        }

        protected fun setDisplayHomeAsUp(isEnabled: Boolean, showUpAsClose: Boolean) {
                supportActionBar?.run {
                        setDisplayHomeAsUpEnabled(isEnabled)
                        if (showUpAsClose) {
                                setHomeAsUpIndicator(appcompatR.drawable.abc_ic_clear_material)
                        }
                }
        }

        override fun onSupportNavigateUp(): Boolean {
                val fm = supportFragmentManager
                if (fm.isStateSaved) {
                        return false
                }
                if (fm.backStackEntryCount > 0) {
                        fm.popBackStack()
                } else {
                        dispatchNavigateUp()
                }
                return true
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
                if (BuildConfig.DEBUG) {
                        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                                ActivityCompat.recreate(this)
                                return true
                        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                                throw RuntimeException("Test crash")
                        }
                }
                return super.onKeyDown(keyCode, event)
        }

        protected fun isDarkAmoledTheme(): Boolean {
                val uiMode = resources.configuration.uiMode
                val isNight = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                return isNight && isAmoledTheme
        }

        @CallSuper
        override fun onSupportActionModeStarted(mode: ActionMode) {
                super.onSupportActionModeStarted(mode)
                actionModeDelegate.onSupportActionModeStarted(mode, window)
        }

        @CallSuper
        override fun onSupportActionModeFinished(mode: ActionMode) {
                super.onSupportActionModeFinished(mode)
                actionModeDelegate.onSupportActionModeFinished(mode, window)
        }

        protected open fun dispatchNavigateUp() {
                val upIntent = parentActivityIntent
                if (upIntent != null) {
                        if (!navigateUpTo(upIntent)) {
                                startActivity(upIntent)
                        }
                } else {
                        finishAfterTransition()
                }
        }

        override fun isNsfwContent(): Flow<Boolean> = flowOf(false)

        private fun putDataToExtras(intent: Intent?) {
                intent?.putExtra(AppRouter.KEY_DATA, intent.data)
        }

        protected fun setContentViewWebViewSafe(viewBindingProducer: () -> B): Boolean {
                return try {
                        setContentView(viewBindingProducer())
                        true
                } catch (e: Exception) {
                        if (e.isWebViewUnavailable()) {
                                Toast.makeText(this, R.string.web_view_unavailable, Toast.LENGTH_LONG).show()
                                finishAfterTransition()
                                false
                        } else {
                                throw e
                        }
                }
        }

        protected fun hasViewBinding() = ::viewBinding.isInitialized
}
