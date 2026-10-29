package com.simplevideodownloader.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.simplevideodownloader.R
import com.simplevideodownloader.data.DownloadRecord
import com.simplevideodownloader.data.DownloadStore
import com.simplevideodownloader.databinding.FragmentListBinding
import com.simplevideodownloader.databinding.ItemDownloadBinding
import com.simplevideodownloader.download.DownloadWorker
import com.simplevideodownloader.download.Downloads
import kotlinx.coroutines.launch

/** Pestaña "Progreso": descargas en curso y las que fallaron, con su avance. */
class ProgressFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private val adapter = Adapter()
    private lateinit var store: DownloadStore
    private var records: List<DownloadRecord> = emptyList()
    private var infos: Map<String, WorkInfo> = emptyMap()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = DownloadStore.get(requireContext())
        binding.empty.setText(R.string.progress_empty)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        WorkManager.getInstance(requireContext()).getWorkInfosByTagLiveData(Downloads.TAG).observe(viewLifecycleOwner) { list ->
            // Si una descarga se reintentó tiene varios trabajos: nos quedamos con el que sigue vivo.
            infos = list.groupBy { info -> recordIdOf(info) }
                .filterKeys { it != null }
                .mapKeys { it.key!! }
                .mapValues { (_, works) -> works.firstOrNull { !it.state.isFinished } ?: works.last() }
            render()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.records.collect { all ->
                    records = all.filter { it.status != DownloadRecord.Status.DONE }
                    render()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun recordIdOf(info: WorkInfo) =
        info.tags.firstOrNull { it.startsWith(Downloads.TAG_RECORD_PREFIX) }?.removePrefix(Downloads.TAG_RECORD_PREFIX)

    private fun render() {
        val b = _binding ?: return
        adapter.submit(records)
        b.empty.isVisible = records.isEmpty()
    }

    private fun cancel(record: DownloadRecord) {
        // Una descarga en cola nunca llega a ejecutarse, así que la marcamos aquí y no en el worker.
        infos[record.id]?.takeIf { !it.state.isFinished }?.let { WorkManager.getInstance(requireContext()).cancelWorkById(it.id) }
        store.update(record.id) { it.copy(status = DownloadRecord.Status.FAILED, error = getString(R.string.status_cancelled)) }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        private var items: List<DownloadRecord> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newItems: List<DownloadRecord>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class Holder(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val record = items[position]
            val info = infos[record.id]
            val b = holder.binding
            val ctx = b.root.context
            b.title.text = listOfNotNull(record.request.fileName, record.quality?.let { "($it)" }).joinToString(" ")

            if (record.status == DownloadRecord.Status.FAILED) {
                b.progress.isVisible = false
                b.status.text = ctx.getString(R.string.status_failed, record.error ?: "-")
                b.action.isVisible = true
                b.action.setText(R.string.retry)
                b.action.setOnClickListener { Downloads.restart(ctx, record) }
                b.secondary.isVisible = true
                b.secondary.setText(R.string.remove)
                b.secondary.setOnClickListener { store.remove(record.id) }
                return
            }

            val percent = info?.progress?.getInt(DownloadWorker.KEY_PROGRESS, -1) ?: -1
            b.progress.isVisible = true
            b.progress.isIndeterminate = percent < 0
            if (percent >= 0) b.progress.setProgressCompat(percent, true)
            b.status.text = when (info?.state) {
                WorkInfo.State.RUNNING -> ctx.getString(R.string.status_running, progressText(info))
                else -> ctx.getString(R.string.status_queued)
            }
            b.action.isVisible = true
            b.action.setText(R.string.cancel)
            b.action.setOnClickListener { cancel(record) }
            b.secondary.isVisible = false
        }

        private fun progressText(info: WorkInfo): String {
            val p = info.progress
            val done = p.getLong(DownloadWorker.KEY_DONE, 0)
            val total = p.getLong(DownloadWorker.KEY_TOTAL, 0)
            val percent = p.getInt(DownloadWorker.KEY_PROGRESS, -1)
            val ctx = requireContext()
            return when {
                p.getString(DownloadWorker.KEY_UNIT) == "segmentos" -> "$done / $total partes ($percent%)"
                total > 0 -> "${formatMegabytes(done)} / ${formatMegabytes(total)} ($percent%)"
                done > 0 -> Formatter.formatShortFileSize(ctx, done)
                else -> ""
            }
        }
    }
}
