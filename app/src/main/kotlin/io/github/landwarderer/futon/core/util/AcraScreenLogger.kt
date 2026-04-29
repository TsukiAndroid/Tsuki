package io.github.landwarderer.futon.core.util

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks
import io.github.landwarderer.futon.core.ui.DefaultActivityLifecycleCallbacks
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Legacy placeholder for ACRA screen logging. Converted to a no-op implementation
 * so the app no longer depends on ACRA while preserving lifecycle registration behavior.
 */
@Singleton
class AcraScreenLogger @Inject constructor() : FragmentLifecycleCallbacks(), DefaultActivityLifecycleCallbacks {

	private val keys = WeakHashMap<Any, String>()

	override fun onFragmentAttached(fm: FragmentManager, f: Fragment, context: Context) {
		super.onFragmentAttached(fm, f, context)
		// No-op: removed ACRA dependency
	}

	override fun onFragmentDetached(fm: FragmentManager, f: Fragment) {
		super.onFragmentDetached(fm, f)
		keys.remove(f)
	}

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		super.onActivityCreated(activity, savedInstanceState)
		// Keep fragment lifecycle registration so behavior remains similar
		(activity as? FragmentActivity)?.supportFragmentManager?.registerFragmentLifecycleCallbacks(this, true)
	}

	override fun onActivityDestroyed(activity: Activity) {
		super.onActivityDestroyed(activity)
		keys.remove(activity)
	}

	private fun Any.key() = keys.getOrPut(this) {
		val time = LocalTime.now().truncatedTo(ChronoUnit.SECONDS)
		"$time: ${javaClass.simpleName}"
	}

	@Suppress("DEPRECATION")
	private fun Bundle?.contentToString() = this?.keySet()?.joinToString { k ->
		val v = get(k)
		"$k=$v"
	} ?: toString()
}
