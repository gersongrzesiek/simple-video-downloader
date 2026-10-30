package com.simplevideodownloader.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simplevideodownloader.R
import com.simplevideodownloader.data.DownloadRecord
import com.simplevideodownloader.data.DownloadStore
import com.simplevideodownloader.databinding.FragmentListBinding
import com.simplevideodownloader.databinding.ItemCompletedBinding
import com.simplevideodownloader.download.Downloads
import com.simplevideodownloader.download.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Pestaña "Terminado": vídeos descargados con miniatura y peso. Tocar uno lo reproduce. */
class CompletedFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private val adapter = Adapter()
    private lateinit var store: DownloadStore
    /** Descarga cuyo borrado está esperando la confirmación del sistema (Android 11+). */
    private var pendingDeleteId: String? = null

    private val deleteRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val id = pendingDeleteId ?: return@registerForActivityResult
        pendingDeleteId = null
        if (result.resultCode == Activity.RESULT_OK) {
            store.remove(id)
            Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = DownloadStore.get(requireContext())
        binding.empty.setText(R.string.done_empty)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.records.collect { all ->
                    val done = all.filter { it.status == DownloadRecord.Status.DONE }
                    adapter.submit(done)
                    _binding?.empty?.isVisible = done.isEmpty()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Descargas anteriores a esta versión no guardaban la duración: la leemos del archivo una vez. */
    private val durationChecked = HashSet<String>()

    private fun fillDuration(record: DownloadRecord) {
        val uri = record.uri?.let(Uri::parse) ?: return
        if (!durationChecked.add(record.id)) return
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val duration = withContext(Dispatchers.IO) { Thumbnails.durationMs(ctx, uri) }
            if (duration > 0) store.update(record.id) { it.copy(durationMs = duration) }
        }
    }

    private fun fileExists(uri: Uri): Boolean = mediaExists(requireContext(), uri)

    /** Reproduce el vídeo; si ya no está en el teléfono, lo vuelve a descargar. */
    private fun play(record: DownloadRecord) {
        val uri = record.uri?.let(Uri::parse)
        viewLifecycleOwner.lifecycleScope.launch {
            val exists = uri != null && withContext(Dispatchers.IO) { fileExists(uri) }
            val ctx = context ?: return@launch
            if (exists) {
                startActivity(
                    Intent(ctx, PlayerActivity::class.java).setData(uri)
                        .putExtra(PlayerActivity.EXTRA_TITLE, record.request.fileName)
                )
            } else {
                Toast.makeText(ctx, R.string.file_missing_redownload, Toast.LENGTH_LONG).show()
                Downloads.restart(ctx, record)
                (activity as? MainActivity)?.openSection(MainActivity.SECTION_PROGRESS)
            }
        }
    }

    private fun showMenu(anchor: View, record: DownloadRecord) {
        PopupMenu(requireContext(), anchor).apply {
            inflate(R.menu.completed_item)
            // Compartir un file:// (Android 9 o anterior) haría fallar la app; ahí solo se ofrece en MediaStore.
            menu.findItem(R.id.action_share).isVisible = record.uri?.startsWith("content:") == true
            menu.findItem(R.id.action_open_site).isEnabled = record.pageUrl != null
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_open_site -> openSite(record)
                    R.id.action_share -> share(record)
                    R.id.action_delete_file -> confirmDelete(record)
                    R.id.action_remove_entry -> store.remove(record.id)
                }
                true
            }
            show()
        }
    }

    private fun openSite(record: DownloadRecord) {
        val url = record.pageUrl?.takeIf { it.startsWith("http") }
        if (url == null) {
            Toast.makeText(requireContext(), R.string.no_source_site, Toast.LENGTH_SHORT).show()
            return
        }
        (activity as? MainActivity)?.openUrl(url, newTab = true)
    }

    private fun share(record: DownloadRecord) {
        val uri = record.uri?.let(Uri::parse) ?: return
        val intent = Intent(Intent.ACTION_SEND)
            .setType(requireContext().contentResolver.getType(uri) ?: "video/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(record: DownloadRecord) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, record.request.fileName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteFile(record) }
            .show()
    }

    private fun deleteFile(record: DownloadRecord) {
        val ctx = requireContext()
        val uri = record.uri?.let(Uri::parse)
        if (uri == null) {
            store.remove(record.id)
            return
        }
        val deleted = try {
            if (uri.scheme == "file") {
                val file = uri.path?.let(::File)
                file == null || !file.exists() || file.delete()
            } else {
                ctx.contentResolver.delete(uri, null, null) > 0 || !fileExists(uri)
            }
        } catch (e: SecurityException) {
            // Android 11+: si el archivo ya no es "nuestro" (p. ej. tras reinstalar), el sistema pide confirmación.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingDeleteId = record.id
                val sender = MediaStore.createDeleteRequest(ctx.contentResolver, listOf(uri)).intentSender
                deleteRequest.launch(IntentSenderRequest.Builder(sender).build())
                return
            }
            false
        }
        if (deleted) {
            store.remove(record.id)
            Toast.makeText(ctx, R.string.deleted, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, R.string.delete_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        private var items: List<DownloadRecord> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newItems: List<DownloadRecord>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class Holder(val binding: ItemCompletedBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemCompletedBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val record = items[position]
            val b = holder.binding
            b.title.text = displayName(record.request.fileName)
            b.details.text = listOfNotNull(
                record.sizeBytes.takeIf { it > 0 }?.let { formatMegabytes(it) },
                record.quality,
                record.completedAt.takeIf { it > 0 }?.let {
                    DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                },
            ).joinToString(" · ")

            b.thumbnail.showThumbnail(store.thumbnail(record), viewLifecycleOwner.lifecycleScope)
            b.duration.isVisible = record.durationMs > 0
            b.duration.text = DateUtils.formatElapsedTime(record.durationMs / 1000)
            if (record.durationMs <= 0) fillDuration(record)

            b.root.setOnClickListener { play(record) }
            b.more.setOnClickListener { showMenu(it, record) }
        }
    }
}
