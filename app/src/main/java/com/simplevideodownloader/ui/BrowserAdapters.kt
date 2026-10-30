package com.simplevideodownloader.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.simplevideodownloader.R
import com.simplevideodownloader.adblock.DomainUtils
import com.simplevideodownloader.browser.Tab
import com.simplevideodownloader.data.Favicons
import com.simplevideodownloader.data.Site
import com.simplevideodownloader.databinding.ItemHomeEmptyBinding
import com.simplevideodownloader.databinding.ItemHomeHeaderBinding
import com.simplevideodownloader.databinding.ItemHomeTileBinding
import com.simplevideodownloader.databinding.ItemQualityBinding
import com.simplevideodownloader.databinding.ItemSiteBinding
import com.simplevideodownloader.databinding.ItemSuggestionBinding
import com.simplevideodownloader.databinding.ItemTabBinding
import com.simplevideodownloader.detect.DownloadOption
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Tamaño en MB, como lo pidió el usuario ("12.3 MB"). */
fun formatMegabytes(bytes: Long, decimals: Int = 1): String =
    String.format(Locale.getDefault(), "%.${decimals}f MB", bytes / 1_048_576.0)

private val tileColors = intArrayOf(
    0xFFE5383B.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFF8E24AA.toInt(),
    0xFFF4511E.toInt(), 0xFF00897B.toInt(), 0xFF3949AB.toInt(), 0xFF6D4C41.toInt(),
)

/** Icono del sitio si lo tenemos (o se puede pedir); mientras tanto, círculo de color con su inicial. */
fun bindSiteIcon(letter: TextView, icon: ImageView, site: Site, scope: CoroutineScope) {
    bindSiteLetter(letter, site)
    icon.tag = site.url
    val cached = Favicons.cached(site.url)
    icon.setImageBitmap(cached)
    icon.isVisible = cached != null
    if (cached != null) return
    scope.launch {
        val bitmap = Favicons.load(icon.context.applicationContext, site.url) ?: return@launch
        if (icon.tag == site.url) {
            icon.setImageBitmap(bitmap)
            icon.isVisible = true
        }
    }
}

/** Círculo de color con la inicial del sitio. */
fun bindSiteLetter(view: TextView, site: Site) {
    val host = DomainUtils.hostOf(site.url)?.removePrefix("www.") ?: site.title
    view.text = host.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    view.backgroundTintList = ColorStateList.valueOf(tileColors[abs(host.hashCode()) % tileColors.size])
    view.setTextColor(Color.WHITE)
}

/** Elementos de la página de inicio: títulos de sección, accesos directos y un texto si no hay nada. */
sealed class HomeItem {
    data class Header(val text: String) : HomeItem()
    data class Tile(val site: Site, val favorite: Boolean) : HomeItem()
    data class Empty(val text: String) : HomeItem()
}

class HomeAdapter(
    private val scope: CoroutineScope,
    private val onOpen: (Site) -> Unit,
    private val onLongPress: (View, HomeItem.Tile) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items: List<HomeItem> = emptyList()
    /** Sitios en filas (icono y nombre) en lugar de cuadrícula; se elige en Ajustes. */
    var listMode = false
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newItems: List<HomeItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun isFullWidth(position: Int) = listMode || items.getOrNull(position) !is HomeItem.Tile

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is HomeItem.Header -> 0
        is HomeItem.Tile -> if (listMode) 3 else 1
        is HomeItem.Empty -> 2
    }

    private class HeaderHolder(val binding: ItemHomeHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    private class TileHolder(val binding: ItemHomeTileBinding) : RecyclerView.ViewHolder(binding.root)
    private class EmptyHolder(val binding: ItemHomeEmptyBinding) : RecyclerView.ViewHolder(binding.root)
    private class RowHolder(val binding: ItemSiteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderHolder(ItemHomeHeaderBinding.inflate(inflater, parent, false))
            1 -> TileHolder(ItemHomeTileBinding.inflate(inflater, parent, false))
            3 -> RowHolder(ItemSiteBinding.inflate(inflater, parent, false))
            else -> EmptyHolder(ItemHomeEmptyBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HomeItem.Header -> (holder as HeaderHolder).binding.header.text = item.text
            is HomeItem.Empty -> (holder as EmptyHolder).binding.text.text = item.text
            is HomeItem.Tile -> {
                val root = if (holder is RowHolder) {
                    val b = holder.binding
                    bindSiteIcon(b.letter, b.icon, item.site, scope)
                    b.title.text = item.site.title.ifBlank { item.site.url }
                    b.root
                } else {
                    val b = (holder as TileHolder).binding
                    bindSiteIcon(b.letter, b.icon, item.site, scope)
                    b.title.text = item.site.title.ifBlank { item.site.url }
                    b.root
                }
                root.setOnClickListener { onOpen(item.site) }
                root.setOnLongClickListener {
                    onLongPress(it, item)
                    true
                }
            }
        }
    }
}

/** Lista de pestañas abiertas en el panel lateral. */
class TabsAdapter(
    private val onSelect: (Tab) -> Unit,
    private val onClose: (Tab) -> Unit,
) : RecyclerView.Adapter<TabsAdapter.Holder>() {
    private var tabs: List<Tab> = emptyList()
    private var current: Tab? = null

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newTabs: List<Tab>, selected: Tab?) {
        tabs = newTabs.toList()
        current = selected
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemTabBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemTabBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = tabs.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tab = tabs[position]
        val b = holder.binding
        val home = b.root.context.getString(R.string.new_tab_title)
        b.title.text = tab.title ?: tab.url ?: home
        b.url.text = tab.url ?: home
        b.row.isSelected = tab === current
        b.row.setOnClickListener { onSelect(tab) }
        b.close.setOnClickListener { onClose(tab) }
    }
}

/** Lista de favoritos en el panel lateral. */
class SitesAdapter(
    private val scope: CoroutineScope,
    private val onOpen: (Site) -> Unit,
    private val onLongPress: (View, Site) -> Unit,
) : RecyclerView.Adapter<SitesAdapter.Holder>() {
    private var sites: List<Site> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newSites: List<Site>) {
        sites = newSites
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemSiteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemSiteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = sites.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val site = sites[position]
        val b = holder.binding
        bindSiteIcon(b.letter, b.icon, site, scope)
        b.title.text = site.title.ifBlank { site.url }
        b.root.setOnClickListener { onOpen(site) }
        b.root.setOnLongClickListener {
            onLongPress(it, site)
            true
        }
    }
}

/** Resoluciones disponibles para descargar, en tarjetas: se elige una y luego se pulsa Descargar. */
/** [onSelected] recibe la calidad elegida y si la tocó el usuario (false al llenar la lista). */
class QualityAdapter(private val onSelected: (DownloadOption, Boolean) -> Unit) : RecyclerView.Adapter<QualityAdapter.Holder>() {
    private var options: List<DownloadOption> = emptyList()
    private var selectedIndex = 0
    /** Calidades ya descargadas (o descargándose), con su texto: "Descargado" / "Descargando". */
    private var badges: Map<DownloadOption, String> = emptyMap()

    @SuppressLint("NotifyDataSetChanged")
    fun setBadges(newBadges: Map<DownloadOption, String>) {
        badges = newBadges
        notifyDataSetChanged()
    }

    val selected: DownloadOption? get() = options.getOrNull(selectedIndex)

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newOptions: List<DownloadOption>) {
        options = newOptions
        selectedIndex = 0
        notifyDataSetChanged()
        selected?.let { onSelected(it, false) }
    }

    class Holder(val binding: ItemQualityBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemQualityBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = options.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val option = options[position]
        val b = holder.binding
        val ctx = b.root.context
        b.quality.text = option.qualityLabel?.uppercase() ?: ctx.getString(R.string.quality_unknown_short)
        val badge = badges[option]
        b.size.setTextColor(
            if (badge != null) ContextCompat.getColor(ctx, R.color.accent)
            else MaterialColors.getColor(b.size, android.R.attr.textColorSecondary)
        )
        b.size.text = when {
            badge != null -> badge
            option.sizeBytes <= 0 -> ctx.getString(R.string.size_unknown)
            option.sizeEstimated -> "~" + formatMegabytes(option.sizeBytes, decimals = 2)
            else -> formatMegabytes(option.sizeBytes, decimals = 2)
        }
        b.card.isChecked = position == selectedIndex
        b.card.setOnClickListener {
            val previous = selectedIndex
            selectedIndex = holder.bindingAdapterPosition
            notifyItemChanged(previous)
            notifyItemChanged(selectedIndex)
            selected?.let { onSelected(it, true) }
        }
    }
}

/** Sugerencias del historial bajo la barra de direcciones. */
class SuggestionAdapter : BaseAdapter() {
    private var sites: List<Site> = emptyList()

    fun submit(newSites: List<Site>) {
        sites = newSites
        notifyDataSetChanged()
    }

    override fun getCount() = sites.size
    override fun getItem(position: Int) = sites[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val b = convertView?.let { ItemSuggestionBinding.bind(it) }
            ?: ItemSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val site = sites[position]
        b.title.text = site.title.ifBlank { site.url }
        b.url.text = site.url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
        return b.root
    }
}

