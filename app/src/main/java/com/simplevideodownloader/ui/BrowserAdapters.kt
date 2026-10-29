package com.simplevideodownloader.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.simplevideodownloader.R
import com.simplevideodownloader.adblock.DomainUtils
import com.simplevideodownloader.browser.Tab
import com.simplevideodownloader.data.Site
import com.simplevideodownloader.databinding.ItemHomeEmptyBinding
import com.simplevideodownloader.databinding.ItemHomeHeaderBinding
import com.simplevideodownloader.databinding.ItemHomeTileBinding
import com.simplevideodownloader.databinding.ItemMediaBinding
import com.simplevideodownloader.databinding.ItemSiteBinding
import com.simplevideodownloader.databinding.ItemTabBinding
import com.simplevideodownloader.detect.DownloadOption
import java.util.Locale
import kotlin.math.abs

/** Tamaño en MB, como lo pidió el usuario ("12.3 MB"). */
fun formatMegabytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)

private val tileColors = intArrayOf(
    0xFFE5383B.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFF8E24AA.toInt(),
    0xFFF4511E.toInt(), 0xFF00897B.toInt(), 0xFF3949AB.toInt(), 0xFF6D4C41.toInt(),
)

/** Círculo de color con la inicial del sitio (en lugar de descargar su icono). */
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
    private val onOpen: (Site) -> Unit,
    private val onLongPress: (View, HomeItem.Tile) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items: List<HomeItem> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newItems: List<HomeItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun isFullWidth(position: Int) = items.getOrNull(position) !is HomeItem.Tile

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is HomeItem.Header -> 0
        is HomeItem.Tile -> 1
        is HomeItem.Empty -> 2
    }

    private class HeaderHolder(val binding: ItemHomeHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    private class TileHolder(val binding: ItemHomeTileBinding) : RecyclerView.ViewHolder(binding.root)
    private class EmptyHolder(val binding: ItemHomeEmptyBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderHolder(ItemHomeHeaderBinding.inflate(inflater, parent, false))
            1 -> TileHolder(ItemHomeTileBinding.inflate(inflater, parent, false))
            else -> EmptyHolder(ItemHomeEmptyBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HomeItem.Header -> (holder as HeaderHolder).binding.header.text = item.text
            is HomeItem.Empty -> (holder as EmptyHolder).binding.text.text = item.text
            is HomeItem.Tile -> {
                val b = (holder as TileHolder).binding
                bindSiteLetter(b.letter, item.site)
                b.title.text = item.site.title.ifBlank { item.site.url }
                b.root.setOnClickListener { onOpen(item.site) }
                b.root.setOnLongClickListener {
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
        bindSiteLetter(b.letter, site)
        b.title.text = site.title.ifBlank { site.url }
        b.root.setOnClickListener { onOpen(site) }
        b.root.setOnLongClickListener {
            onLongPress(it, site)
            true
        }
    }
}

/** Resoluciones disponibles para descargar, con su tamaño. */
class OptionsAdapter(private val onClick: (DownloadOption) -> Unit) : RecyclerView.Adapter<OptionsAdapter.Holder>() {
    private var options: List<DownloadOption> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newOptions: List<DownloadOption>) {
        options = newOptions
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = options.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val option = options[position]
        val b = holder.binding
        val ctx = b.root.context
        b.kind.text = option.format
        b.title.text = option.qualityLabel ?: ctx.getString(R.string.quality_unknown)
        val size = when {
            option.sizeBytes <= 0 -> ctx.getString(R.string.size_unknown)
            option.sizeEstimated -> "~" + formatMegabytes(option.sizeBytes)
            else -> formatMegabytes(option.sizeBytes)
        }
        b.subtitle.text = listOfNotNull(size, option.sourceHost).joinToString(" · ")
        b.action.setOnClickListener { onClick(option) }
        b.root.setOnClickListener { onClick(option) }
    }
}
