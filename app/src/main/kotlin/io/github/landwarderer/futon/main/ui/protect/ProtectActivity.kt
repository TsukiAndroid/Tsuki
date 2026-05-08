package io.github.landwarderer.futon.main.ui.protect

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.activity.viewModels
import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationRequest.Biometric
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import androidx.biometric.registerForAuthenticationResult
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.ui.BaseActivity
import io.github.landwarderer.futon.core.ui.util.DefaultTextWatcher
import io.github.landwarderer.futon.core.util.ext.consumeAll
import io.github.landwarderer.futon.core.util.ext.getDisplayMessage
import io.github.landwarderer.futon.core.util.ext.getParcelableExtraCompat
import io.github.landwarderer.futon.core.util.ext.observe
import io.github.landwarderer.futon.core.util.ext.observeEvent
import io.github.landwarderer.futon.core.util.ext.performHapticFeedbackCompat
import io.github.landwarderer.futon.core.util.ext.syncImeAnimationToPadding
import io.github.landwarderer.futon.databinding.ActivityProtectBinding
import com.google.android.material.R as materialR

@AndroidEntryPoint
class ProtectActivity :
	BaseActivity<ActivityProtectBinding>(),
	TextView.OnEditorActionListener,
	DefaultTextWatcher,
	View.OnClickListener,
	AuthenticationResultCallback {

	private val viewModel by viewModels<ProtectViewModel>()
	private var canUseBiometric = false

	private val biometricPrompt = registerForAuthenticationResult(resultCallback = this)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		setContentView(ActivityProtectBinding.inflate(layoutInflater))

		// Smooth animated keyboard: padding tracks the IME in real-time (API 30+)
		// instead of jumping when the keyboard finishes opening. Falls back to the
		// same instant behaviour on API 23–29 — no regression on older devices.
		viewBinding.root.syncImeAnimationToPadding(
			resources.getDimensionPixelOffset(R.dimen.screen_padding),
		)

		viewBinding.editPassword.setOnEditorActionListener(this)
		viewBinding.editPassword.addTextChangedListener(this)
		viewBinding.buttonNext.setOnClickListener(this)
		viewBinding.buttonCancel.setOnClickListener(this)

		viewBinding.editPassword.inputType = if (viewModel.isNumericPassword) {
			EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
		} else {
			EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
		}

		viewModel.onError.observeEvent(this, this::onError)
		viewModel.isLoading.observe(this, this::onLoadingStateChanged)
		viewModel.onUnlockSuccess.observeEvent(this) {
			val intent = intent.getParcelableExtraCompat<Intent>(EXTRA_INTENT)
			startActivity(intent)
			finishAfterTransition()
		}
		lifecycleScope.launch {
			withResumed {
				canUseBiometric = useFingerprint()
				updateEndIcon()
				if (!canUseBiometric) {
					viewBinding.editPassword.requestFocus()
				}
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		// Union systemBars + ime so that bottom padding grows to accommodate the
		// keyboard on API 30+ where adjustResize does not work with edge-to-edge.
		val type = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
		val barsInsets = insets.getInsets(type)
		val basePadding = resources.getDimensionPixelOffset(R.dimen.screen_padding)
		viewBinding.root.setPadding(
			barsInsets.left + basePadding,
			barsInsets.top + basePadding,
			barsInsets.right + basePadding,
			barsInsets.bottom + basePadding,
		)
		return insets.consumeAll(type)
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_next -> viewModel.tryUnlock(viewBinding.editPassword.text?.toString().orEmpty())
			R.id.button_cancel -> finish()
			materialR.id.text_input_end_icon -> useFingerprint()
		}
	}

	override fun onEditorAction(v: TextView?, actionId: Int, event: KeyEvent?): Boolean {
		return if (actionId == EditorInfo.IME_ACTION_DONE && viewBinding.buttonNext.isEnabled) {
			viewBinding.buttonNext.performClick()
			true
		} else {
			false
		}
	}

	override fun afterTextChanged(s: Editable?) {
		viewBinding.layoutPassword.error = null
		viewBinding.buttonNext.isEnabled = !s.isNullOrEmpty()
		updateEndIcon()
	}

	override fun onAuthResult(result: AuthenticationResult) {
		if (result.isSuccess()) {
			// Satisfying "unlock" haptic — CONFIRM on API 30+, VIRTUAL_KEY on API 23–29
			viewBinding.root.performHapticFeedbackCompat(isSuccess = true)
			viewModel.unlock()
		}
	}

	private fun onError(e: Throwable) {
		viewBinding.layoutPassword.error = e.getDisplayMessage(resources)
		// Wrong password haptic — REJECT on API 30+, LONG_PRESS buzz on API 23–29
		viewBinding.root.performHapticFeedbackCompat(isSuccess = false)
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.layoutPassword.isEnabled = !isLoading
	}

	private fun useFingerprint(): Boolean {
		if (!viewModel.isBiometricEnabled) {
			return false
		}
		if (BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK) != BIOMETRIC_SUCCESS) {
			return false
		}
		val request = AuthenticationRequest.biometricRequest(
			title = getString(R.string.app_name),
			authFallback = Biometric.Fallback.NegativeButton(getString(android.R.string.cancel)),
			init = {
				setMinStrength(Biometric.Strength.Class2)
				setIsConfirmationRequired(false)
			},
		)
		biometricPrompt.launch(request)
		return true
	}

	private fun updateEndIcon() = with(viewBinding.layoutPassword) {
		val isFingerprintIcon = canUseBiometric && viewBinding.editPassword.text.isNullOrEmpty()
		if (isFingerprintIcon == (endIconMode == TextInputLayout.END_ICON_CUSTOM)) {
			return@with
		}
		if (isFingerprintIcon) {
			endIconMode = TextInputLayout.END_ICON_CUSTOM
			setEndIconDrawable(androidx.biometric.R.drawable.fingerprint_dialog_fp_icon)
			endIconContentDescription = getString(androidx.biometric.R.string.use_biometric_label)
			setEndIconOnClickListener(this@ProtectActivity)
		} else {
			setEndIconOnClickListener(null)
			setEndIconDrawable(0)
			endIconContentDescription = null
			endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
		}
	}

	companion object {

		private const val EXTRA_INTENT = "src_intent"

		fun newIntent(context: Context, sourceIntent: Intent): Intent {
			return Intent(context, ProtectActivity::class.java)
				.putExtra(EXTRA_INTENT, sourceIntent)
		}
	}
}
