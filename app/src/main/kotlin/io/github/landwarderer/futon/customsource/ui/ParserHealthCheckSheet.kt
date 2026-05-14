package io.github.landwarderer.futon.customsource.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.customsource.domain.CustomSource
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Bottom sheet that pings every user-defined custom source and reports
 * whether it is reachable, slow, or broken.
 *
 * The check uses a lightweight HEAD request (falling back to GET) against
 * each source's [CustomSource.cleanBaseUrl].  Results are coloured:
 *   - Green  (OK)        — HTTP 2xx in < 3 s
 *   - Yellow (Slow)      — HTTP 2xx in ≥ 3 s
 *   - Orange (Redirect)  — HTTP 3xx
 *   - Red    (Error)     — HTTP 4xx/5xx or exception
 *   - Grey   (Pending)   — not checked yet
 */
@AndroidEntryPoint
class ParserHealthCheckSheet : BottomSheetDialogFragment() {

    private val viewModel: CustomSourceViewModel by viewModels()

    private var recyclerView: RecyclerView? = null
    private var checkAllBtn: MaterialButton? = null
    private var statusSummary: TextView? = null
    private var adapter: HealthAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_parser_health_check, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView  = view.findViewById(R.id.recycler_health)
        checkAllBtn   = view.findViewById(R.id.btn_check_all)
        statusSummary = view.findViewById(R.id.text_health_summary)

        recyclerView?.layoutManager = LinearLayoutManager(requireContext())

        checkAllBtn?.setOnClickListener {
            checkAllBtn?.isEnabled = false
            checkAllBtn?.text = getString(R.string.health_checking_label)
            viewModel.runHealthCheckAll()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sources.collectLatest { sources ->
                val results = viewModel.healthResults.value
                renderRows(sources, results)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.healthResults.collectLatest { results ->
                val sources = viewModel.sources.value
                renderRows(sources, results)
                updateSummary(sources, results)
                val allDone = sources.all { s ->
                    val status = results[s.id]?.status
                    status != null &&
                        status != CustomSourceViewModel.HealthStatus.Status.PENDING &&
                        status != CustomSourceViewModel.HealthStatus.Status.CHECKING
                }
                if (allDone && sources.isNotEmpty()) {
                    checkAllBtn?.isEnabled = true
                    checkAllBtn?.text = getString(R.string.health_recheck_label)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView  = null
        checkAllBtn   = null
        statusSummary = null
        adapter       = null
    }

    private fun renderRows(
        sources: List<CustomSource>,
        results: Map<Long, CustomSourceViewModel.HealthStatus>,
    ) {
        if (adapter == null) {
            adapter = HealthAdapter(sources, results)
            recyclerView?.adapter = adapter
        } else {
            adapter?.update(sources, results)
        }
    }

    private fun updateSummary(
        sources: List<CustomSource>,
        results: Map<Long, CustomSourceViewModel.HealthStatus>,
    ) {
        val ok   = results.values.count { it.status == CustomSourceViewModel.HealthStatus.Status.OK || it.status == CustomSourceViewModel.HealthStatus.Status.SLOW }
        val err  = results.values.count { it.status == CustomSourceViewModel.HealthStatus.Status.ERROR }
        val total = sources.size
        statusSummary?.isVisible = results.isNotEmpty()
        statusSummary?.text = getString(R.string.health_summary, ok, err, total)
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class HealthAdapter(
        private var sources: List<CustomSource>,
        private var results: Map<Long, CustomSourceViewModel.HealthStatus>,
    ) : RecyclerView.Adapter<HealthAdapter.VH>() {

        fun update(
            newSources: List<CustomSource>,
            newResults: Map<Long, CustomSourceViewModel.HealthStatus>,
        ) {
            sources = newSources
            results = newResults
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_parser_health_status, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) =
            holder.bind(sources[position], results[sources[position].id])

        override fun getItemCount() = sources.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val nameView:   TextView = view.findViewById(R.id.text_health_name)
            private val urlView:    TextView = view.findViewById(R.id.text_health_url)
            private val statusView: TextView = view.findViewById(R.id.text_health_status)
            private val latencyView: TextView = view.findViewById(R.id.text_health_latency)

            fun bind(source: CustomSource, health: CustomSourceViewModel.HealthStatus?) {
                nameView.text = source.displayName
                urlView.text  = source.cleanBaseUrl

                val ctx = itemView.context
                when (health?.status) {
                    CustomSourceViewModel.HealthStatus.Status.OK -> {
                        statusView.text = ctx.getString(R.string.health_status_ok)
                        statusView.setTextColor(ContextCompat.getColor(ctx, R.color.health_ok))
                    }
                    CustomSourceViewModel.HealthStatus.Status.SLOW -> {
                        statusView.text = ctx.getString(R.string.health_status_slow)
                        statusView.setTextColor(ContextCompat.getColor(ctx, R.color.health_slow))
                    }
                    CustomSourceViewModel.HealthStatus.Status.REDIRECT -> {
                        statusView.text = ctx.getString(R.string.health_status_redirect)
                        statusView.setTextColor(ContextCompat.getColor(ctx, R.color.health_slow))
                    }
                    CustomSourceViewModel.HealthStatus.Status.ERROR -> {
                        val code = health.httpCode?.let { " ($it)" }.orEmpty()
                        statusView.text = ctx.getString(R.string.health_status_error) + code
                        statusView.setTextColor(ContextCompat.getColor(ctx, R.color.health_error))
                    }
                    CustomSourceViewModel.HealthStatus.Status.CHECKING -> {
                        statusView.text = ctx.getString(R.string.health_status_checking)
                        statusView.setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
                    }
                    else -> {
                        statusView.text = ctx.getString(R.string.health_status_pending)
                        statusView.setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
                    }
                }

                latencyView.isVisible = health?.latencyMs != null && health.latencyMs > 0
                latencyView.text = health?.latencyMs?.let { "${it}ms" }.orEmpty()
            }
        }
    }

    companion object {
        const val TAG = "ParserHealthCheckSheet"
        fun newInstance() = ParserHealthCheckSheet()
    }
}
