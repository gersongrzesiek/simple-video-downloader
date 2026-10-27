package com.simplevideodownloader.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.simplevideodownloader.R
import com.simplevideodownloader.databinding.ActivityDownloadsBinding
import com.simplevideodownloader.databinding.ItemDownloadBinding
import com.simplevideodownloader.download.DownloadWorker
import com.simplevideodownloader.download.Downloads

/** Lista de descargas (en curso, terminadas y con error), leída de WorkManager. */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.downloads)
        binding.toolbar.setOnMenuItemClickListener {
            WorkManager.getInstance(this).pruneWork()
            true
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        WorkManager.getInstance(this).getWorkInfosByTagLiveData(Downloads.TAG).observe(this) { infos ->
            // Las activas primero; dentro de cada grupo, las más recientes arriba (WorkManager no da fecha, usamos el orden inverso).
            val sorted = infos.reversed().sortedBy { if (it.state.isFinished) 1 else 0 }
            adapter.submit(sorted)
            binding.empty.isVisible = sorted.isEmpty()
        }
    }

    private fun open(info: WorkInfo) {
        val uri = info.outputData.getString(DownloadWorker.KEY_URI)?.let(Uri::parse) ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, contentResolver.getType(uri) ?: "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        private var items: List<WorkInfo> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newItems: List<WorkInfo>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class Holder(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val info = items[position]
            val b = holder.binding
            val ctx = b.root.context
            b.title.text = info.tags.firstOrNull { it.startsWith(Downloads.TAG_NAME_PREFIX) }
                ?.removePrefix(Downloads.TAG_NAME_PREFIX) ?: "video"
            val percent = info.progress.getInt(DownloadWorker.KEY_PROGRESS, -1)
            b.progress.isVisible = !info.state.isFinished
            b.progress.isIndeterminate = percent < 0
            if (percent >= 0) b.progress.setProgressCompat(percent, true)

            b.status.text = when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ctx.getString(R.string.status_queued)
                WorkInfo.State.RUNNING -> ctx.getString(R.string.status_running, progressText(info))
                WorkInfo.State.SUCCEEDED -> ctx.getString(R.string.status_done)
                WorkInfo.State.FAILED -> ctx.getString(
                    R.string.status_failed,
                    info.outputData.getString(DownloadWorker.KEY_ERROR) ?: "-",
                )
                WorkInfo.State.CANCELLED -> ctx.getString(R.string.status_cancelled)
            }

            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    b.action.isVisible = true
                    b.action.setText(R.string.open)
                    b.action.setOnClickListener { open(info) }
                    b.root.setOnClickListener { open(info) }
                }
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                    b.action.isVisible = false
                    b.root.setOnClickListener(null)
                }
                else -> {
                    b.action.isVisible = true
                    b.action.setText(R.string.cancel)
                    b.action.setOnClickListener { WorkManager.getInstance(ctx).cancelWorkById(info.id) }
                    b.root.setOnClickListener(null)
                }
            }
        }

        private fun progressText(info: WorkInfo): String {
            val p = info.progress
            val done = p.getLong(DownloadWorker.KEY_DONE, 0)
            val total = p.getLong(DownloadWorker.KEY_TOTAL, 0)
            val percent = p.getInt(DownloadWorker.KEY_PROGRESS, -1)
            return when {
                p.getString(DownloadWorker.KEY_UNIT) == "segmentos" -> "$done / $total partes"
                total > 0 -> "${Formatter.formatShortFileSize(this@DownloadsActivity, done)} / " +
                    "${Formatter.formatShortFileSize(this@DownloadsActivity, total)} ($percent%)"
                done > 0 -> Formatter.formatShortFileSize(this@DownloadsActivity, done)
                else -> ""
            }
        }
    }
}
