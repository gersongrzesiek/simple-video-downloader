package com.simplevideodownloader.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import com.simplevideodownloader.databinding.FragmentCompletedBinding
import com.simplevideodownloader.databinding.ItemCompletedBinding
import com.simplevideodownloader.download.Downloads
import com.simplevideodownloader.download.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pestaña "Terminado": vídeos descargados con miniatura y peso. Tocar uno lo reproduce. Arriba hay un buscador
 * por título y un botón para elegir varios vídeos y eliminarlos de una vez.
 */
class CompletedFragment : Fragment() {

    private var _binding: FragmentCompletedBinding? = null
    private val binding get() = _binding!!
    private val adapter = Adapter()
    private lateinit var store: DownloadStore
    /** Descargas cuyo borrado está esperando la confirmación del sistema (Android 11+). */
    private var pendingDeleteIds: List<String> = emptyList()
    private var allDone: List<DownloadRecord> = emptyList()
    private var query = ""
    /** Modo selección para eliminar varios: ids marcados (null = no se está seleccionando). */
    private var selection: MutableSet<String>? = null

    private val deleteRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val ids = pendingDeleteIds
        pendingDeleteIds = emptyList()
        if (result.resultCode == Activity.RESULT_OK && ids.isNotEmpty()) {
            ids.forEach(store::remove)
            Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
        }
    }

    private val exitSelectionOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setSelecting(false)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCompletedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = DownloadStore.get(requireContext())
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, exitSelectionOnBack)

        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable) {
                query = text.toString().trim()
                binding.clearSearch.isVisible = text.isNotEmpty()
                render()
            }
        })
        binding.clearSearch.setOnClickListener { binding.search.text.clear() }
        binding.deleteMode.setOnClickListener { setSelecting(true) }
        binding.cancelSelection.setOnClickListener { setSelecting(false) }
        binding.selectAll.setOnClickListener {
            val chosen = selection ?: return@setOnClickListener
            val visible = visibleRecords().map { it.id }
            if (chosen.containsAll(visible)) chosen.removeAll(visible.toSet()) else chosen.addAll(visible)
            render()
        }
        binding.deleteSelected.setOnClickListener {
            val ids = selection ?: return@setOnClickListener
            val records = allDone.filter { it.id in ids }
            if (records.isNotEmpty()) confirmDelete(records)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.records.collect { all ->
                    allDone = all.filter { it.status == DownloadRecord.Status.DONE }
                    selection?.retainAll(allDone.map { it.id }.toSet())
                    render()
                }
            }
        }
    }

    /** Los vídeos que coinciden con la búsqueda (sin distinguir mayúsculas ni acentos). */
    private fun visibleRecords(): List<DownloadRecord> {
        if (query.isEmpty()) return allDone
        val q = normalize(query)
        return allDone.filter { normalize(displayName(it.request.fileName)).contains(q) }
    }

    private fun normalize(text: String) =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** El título con lo buscado subrayado (y en negrita), sin importar mayúsculas ni acentos. */
    private fun highlighted(title: String): CharSequence {
        if (query.isEmpty()) return title
        val q = normalize(query)
        // Normalizamos letra por letra para poder volver a las posiciones del título original.
        val norm = StringBuilder()
        val origin = ArrayList<Int>()
        title.forEachIndexed { i, c ->
            normalize(c.toString()).forEach { n ->
                norm.append(n)
                origin += i
            }
        }
        val text = SpannableString(title)
        var from = norm.indexOf(q)
        while (from >= 0 && q.isNotEmpty()) {
            val start = origin[from]
            val end = origin[from + q.length - 1] + 1
            text.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            from = norm.indexOf(q, from + q.length)
        }
        return text
    }

    private fun render() {
        val b = _binding ?: return
        val visible = visibleRecords()
        adapter.submit(visible)
        b.empty.isVisible = visible.isEmpty()
        b.empty.text = if (allDone.isNotEmpty() && query.isNotEmpty()) getString(R.string.no_search_results, query) else getString(R.string.done_empty)
        val chosen = selection
        b.searchBar.isVisible = chosen == null
        b.selectionBar.isVisible = chosen != null
        if (chosen != null) {
            b.selectionCount.text = getString(R.string.selected_count, chosen.size)
            b.deleteSelected.isEnabled = chosen.isNotEmpty()
        }
        b.deleteMode.isEnabled = allDone.isNotEmpty()
    }

    private fun setSelecting(on: Boolean) {
        selection = if (on) HashSet() else null
        exitSelectionOnBack.isEnabled = on
        render()
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
                    R.id.action_delete_file -> confirmDelete(listOf(record))
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

    private fun confirmDelete(records: List<DownloadRecord>) {
        val builder = MaterialAlertDialogBuilder(requireContext())
        if (records.size == 1) {
            builder.setTitle(R.string.delete_confirm_title)
                .setMessage(getString(R.string.delete_confirm_message, displayName(records[0].request.fileName)))
        } else {
            builder.setTitle(getString(R.string.delete_many_title, records.size)).setMessage(R.string.delete_many_message)
        }
        builder.setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteFiles(records) }
            .show()
    }

    /** Borra los archivos; los que Android no deja borrar directamente se piden juntos en una sola confirmación. */
    private fun deleteFiles(records: List<DownloadRecord>) {
        val ctx = requireContext()
        var deleted = 0
        var failed = 0
        val needsConfirmation = ArrayList<DownloadRecord>()
        for (record in records) {
            val uri = record.uri?.let(Uri::parse)
            val ok: Boolean? = try {
                when {
                    uri == null -> true
                    uri.scheme == "file" -> uri.path?.let(::File)?.let { !it.exists() || it.delete() } ?: true
                    else -> ctx.contentResolver.delete(uri, null, null) > 0 || !fileExists(uri)
                }
            } catch (e: SecurityException) {
                // Android 11+: si el archivo ya no es "nuestro" (p. ej. tras reinstalar), el sistema pide confirmación.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) null else false
            }
            if (ok == null) {
                needsConfirmation += record
            } else if (ok) {
                store.remove(record.id)
                deleted++
            } else {
                failed++
            }
        }
        setSelecting(false)
        if (needsConfirmation.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingDeleteIds = needsConfirmation.map { it.id }
            val uris = needsConfirmation.mapNotNull { it.uri?.let(Uri::parse) }
            val sender = MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender
            deleteRequest.launch(IntentSenderRequest.Builder(sender).build())
        }
        when {
            failed > 0 -> Toast.makeText(ctx, R.string.delete_failed, Toast.LENGTH_SHORT).show()
            deleted > 1 -> Toast.makeText(ctx, getString(R.string.deleted_many, deleted), Toast.LENGTH_SHORT).show()
            deleted == 1 -> Toast.makeText(ctx, R.string.deleted, Toast.LENGTH_SHORT).show()
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
            b.title.text = highlighted(displayName(record.request.fileName))
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

            val chosen = selection
            b.check.isVisible = chosen != null
            b.check.isChecked = chosen?.contains(record.id) == true
            b.more.isVisible = chosen == null
            b.root.setOnClickListener {
                val current = selection
                if (current == null) {
                    play(record)
                } else {
                    if (!current.remove(record.id)) current.add(record.id)
                    render()
                }
            }
            // Mantener pulsado un vídeo también empieza a seleccionar.
            b.root.setOnLongClickListener {
                if (selection == null) setSelecting(true)
                selection?.add(record.id)
                render()
                true
            }
            b.more.setOnClickListener { showMenu(it, record) }
        }
    }
}
