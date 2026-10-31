package com.simplevideodownloader.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simplevideodownloader.R
import com.simplevideodownloader.adblock.DomainUtils
import com.simplevideodownloader.data.BrowserStore
import com.simplevideodownloader.data.HistoryEntry
import com.simplevideodownloader.databinding.ActivityHistoryBinding
import com.simplevideodownloader.databinding.ItemHistoryBinding
import com.simplevideodownloader.databinding.ItemHistoryHeaderBinding
import java.util.Calendar
import java.util.Date

/**
 * Historial de navegación a pantalla completa, agrupado por día (Última hora, Hoy, Ayer, Anteayer y fechas),
 * con buscador y opción de borrar por rango de tiempo. Tocar una página la abre en la pestaña actual.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var store: BrowserStore
    private val adapter = Adapter()
    private var query = ""

    private sealed class Row {
        data class Header(val text: String) : Row()
        data class Entry(val entry: HistoryEntry) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = BrowserStore.get(this)

        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.history)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> openSearch()
                R.id.action_clear -> askClearRange()
            }
            true
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable) {
                query = text.toString().trim()
                render()
            }
        })
        binding.closeSearch.setOnClickListener {
            binding.search.text.clear()
            binding.searchRow.isVisible = false
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(binding.search.windowToken, 0)
        }
        render()
    }

    private fun openSearch() {
        binding.searchRow.isVisible = true
        binding.search.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(binding.search, 0)
    }

    private fun render() {
        val q = query.lowercase()
        val entries = store.history().filter {
            q.isEmpty() || it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
        }
        val rows = ArrayList<Row>()
        var lastHeader: String? = null
        for (entry in entries) {
            val header = sectionOf(entry.time)
            if (header != lastHeader) {
                rows += Row.Header(header)
                lastHeader = header
            }
            rows += Row.Entry(entry)
        }
        adapter.submit(rows)
        binding.empty.isVisible = rows.isEmpty()
        binding.empty.setText(if (query.isEmpty()) R.string.history_empty else R.string.history_no_results)
    }

    private fun startOfDay(daysAgo: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun sectionOf(time: Long): String {
        val now = System.currentTimeMillis()
        return when {
            time <= 0 -> getString(R.string.history_older)
            time >= now - DateUtils.HOUR_IN_MILLIS -> getString(R.string.history_last_hour)
            time >= startOfDay(0) -> getString(R.string.history_today)
            time >= startOfDay(1) -> getString(R.string.history_yesterday)
            time >= startOfDay(2) -> getString(R.string.history_day_before)
            else -> DateUtils.formatDateTime(this, time, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE)
                .replaceFirstChar { it.uppercase() }
        }
    }

    /** Menú de 3 puntos > Borrar historial: se elige desde cuándo. */
    private fun askClearRange() {
        val labels = arrayOf(
            getString(R.string.history_last_hour),
            getString(R.string.history_today),
            getString(R.string.history_range_today_yesterday),
            getString(R.string.history_range_week),
            getString(R.string.history_range_month),
            getString(R.string.history_range_all),
        )
        var choice = 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_clear)
            .setSingleChoiceItems(labels, 0) { _, which -> choice = which }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val since = when (choice) {
                    0 -> System.currentTimeMillis() - DateUtils.HOUR_IN_MILLIS
                    1 -> startOfDay(0)
                    2 -> startOfDay(1)
                    3 -> startOfDay(6)
                    4 -> startOfDay(29)
                    else -> 0L
                }
                store.clearHistory(since)
                Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                render()
            }
            .show()
    }

    private fun open(entry: HistoryEntry) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_URL, entry.url))
        finish()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                object : RecyclerView.ViewHolder(ItemHistoryHeaderBinding.inflate(inflater, parent, false).root) {}
            } else {
                object : RecyclerView.ViewHolder(ItemHistoryBinding.inflate(inflater, parent, false).root) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> ItemHistoryHeaderBinding.bind(holder.itemView).header.text = row.text
                is Row.Entry -> {
                    val b = ItemHistoryBinding.bind(holder.itemView)
                    val entry = row.entry
                    bindSiteIcon(b.letter, b.icon, entry.site, lifecycleScope)
                    b.title.text = entry.title.ifBlank { entry.url }
                    b.host.text = DomainUtils.hostOf(entry.url) ?: entry.url
                    b.time.isVisible = entry.time > 0
                    b.time.text = DateFormat.getTimeFormat(this@HistoryActivity).format(Date(entry.time))
                    b.root.setOnClickListener { open(entry) }
                    b.root.setOnLongClickListener { view ->
                        PopupMenu(this@HistoryActivity, view).apply {
                            menu.add(R.string.remove)
                            setOnMenuItemClickListener {
                                store.removeHistory(entry)
                                render()
                                true
                            }
                            show()
                        }
                        true
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
