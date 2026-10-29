package com.simplevideodownloader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.KeyEvent
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simplevideodownloader.App
import com.simplevideodownloader.R
import com.simplevideodownloader.adblock.AdBlocker
import com.simplevideodownloader.adblock.DomainUtils
import com.simplevideodownloader.browser.Tab
import com.simplevideodownloader.data.BrowserStore
import com.simplevideodownloader.data.DownloadRecord
import com.simplevideodownloader.data.DownloadStore
import com.simplevideodownloader.data.Site
import com.simplevideodownloader.databinding.ActivityMainBinding
import com.simplevideodownloader.databinding.SheetDetectedBinding
import com.simplevideodownloader.detect.DetectedMedia
import com.simplevideodownloader.detect.DownloadOption
import com.simplevideodownloader.detect.MediaDetector
import com.simplevideodownloader.detect.MediaKind
import com.simplevideodownloader.detect.QualityResolver
import com.simplevideodownloader.download.DownloadRequest
import com.simplevideodownloader.download.Downloads
import com.simplevideodownloader.download.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class MainActivity : AppCompatActivity(), Tab.Host {

    private lateinit var binding: ActivityMainBinding
    private lateinit var browserStore: BrowserStore
    private val tabs = ArrayList<Tab>()
    private lateinit var current: Tab
    private var detectorJob: Job? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingDownload: PendingDownload? = null
    private var sheet: BottomSheetDialog? = null
    private var section = SECTION_TAB

    private lateinit var homeAdapter: HomeAdapter
    private lateinit var tabsAdapter: TabsAdapter
    private lateinit var favoritesAdapter: SitesAdapter

    private class PendingDownload(val request: DownloadRequest, val pageUrl: String?, val quality: String?)

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingDownload
        pendingDownload = null
        if (granted && pending != null) startDownload(pending.request, pending.pageUrl, pending.quality)
        else if (!granted) Toast.makeText(this, R.string.storage_permission_needed, Toast.LENGTH_LONG).show()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        browserStore = BrowserStore.get(this)

        setupToolbar()
        setupHome()
        setupDrawer()
        setupBottomNav()
        setupBackNavigation()
        binding.fabDownload.setOnClickListener { onDownloadButton() }
        updateDownloadButton(emptyList())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(browserStore.favorites, browserStore.topSites) { favorites, top -> favorites to top }
                    .collect { (favorites, top) -> renderSites(favorites, top) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadStore.get(this@MainActivity).records.collect { records ->
                    val active = records.count { it.status == DownloadRecord.Status.ACTIVE }
                    binding.bottomNav.getOrCreateBadge(R.id.nav_progress).apply {
                        isVisible = active > 0
                        number = active
                    }
                }
            }
        }

        restoreTabs()
        handleIntent(intent)
        savedInstanceState?.getInt(KEY_SECTION)?.let { openSection(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SECTION, section)
    }

    override fun onPause() {
        super.onPause()
        saveTabs()
        current.webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        current.webView.onResume()
    }

    override fun onDestroy() {
        tabs.forEach { it.destroy() }
        tabs.clear()
        super.onDestroy()
    }

    /** Abre enlaces compartidos desde otras apps, "abrir con" o una notificación de descarga. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.hasExtra(EXTRA_SECTION)) {
            openSection(intent.getIntExtra(EXTRA_SECTION, SECTION_TAB))
            intent.removeExtra(EXTRA_SECTION)
            return
        }
        val url = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                Regex("https?://\\S+").find(text)?.value
            }
            else -> null
        } ?: return
        intent.action = null
        openUrl(url, newTab = current.hasPage)
    }

    // --- Pestañas ---

    private fun restoreTabs() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val saved = runCatching { JSONArray(prefs.getString(PREF_TABS, "[]")) }.getOrNull()
        if (saved != null) {
            for (i in 0 until saved.length()) {
                val tab = Tab(this, this)
                saved.optString(i).takeIf { it.startsWith("http") }?.let { tab.restore(it) }
                tabs += tab
            }
        }
        if (tabs.isEmpty()) tabs += Tab(this, this)
        selectTab(tabs[prefs.getInt(PREF_CURRENT_TAB, 0).coerceIn(0, tabs.lastIndex)])
    }

    private fun saveTabs() {
        val urls = JSONArray()
        tabs.forEach { urls.put(it.url ?: "") }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(PREF_TABS, urls.toString())
            .putInt(PREF_CURRENT_TAB, tabs.indexOf(current).coerceAtLeast(0))
            .apply()
    }

    private fun newTab(url: String? = null): Tab {
        val tab = Tab(this, this)
        val index = if (::current.isInitialized) tabs.indexOf(current) + 1 else tabs.size
        tabs.add(index.coerceIn(0, tabs.size), tab)
        selectTab(tab)
        if (url != null) tab.load(url)
        return tab
    }

    private fun selectTab(tab: Tab) {
        if (::current.isInitialized && current !== tab) {
            current.webView.onPause()
            binding.webContainer.removeView(current.webView)
        }
        current = tab
        if (tab.webView.parent == null) {
            binding.webContainer.addView(tab.webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        tab.webView.onResume()
        tab.ensureLoaded()

        detectorJob?.cancel()
        detectorJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // En cuanto aparece un vídeo se consultan sus resoluciones; el botón se pone rojo cuando hay alguna descargable.
                tab.detector.items.collectLatest { items ->
                    if (items.isEmpty()) {
                        updateDownloadButton(emptyList())
                        return@collectLatest
                    }
                    if (tab.optionsFor == items) {
                        updateDownloadButton(tab.options)
                        return@collectLatest
                    }
                    delay(400) // la página suele descubrir varios vídeos seguidos
                    val options = QualityResolver.resolve(items, tab.title, tab.optionCache)
                    tab.options = options
                    tab.optionsFor = items
                    updateDownloadButton(options)
                }
            }
        }
        renderTab()
    }

    private fun closeTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        if (tabs.size == 1) tabs += Tab(this, this) // Siempre queda al menos una pestaña (en Inicio).
        tabs.removeAt(index)
        if (tab === current) selectTab(tabs[index.coerceAtMost(tabs.lastIndex)])
        tab.destroy()
        renderTab()
    }

    /** Abre una dirección en la pestaña actual o en una nueva, y vuelve a la sección del navegador. */
    fun openUrl(url: String, newTab: Boolean) {
        openSection(SECTION_TAB)
        if (newTab) newTab(url) else current.load(url)
    }

    /** Pinta la barra superior, la página de inicio y el panel lateral según la pestaña actual. */
    private fun renderTab() {
        val tab = current
        if (!binding.urlInput.hasFocus()) binding.urlInput.setText(tab.url ?: "")
        binding.homeView.isVisible = tab.isHome
        binding.progress.isVisible = section == SECTION_TAB && !tab.isHome && tab.progress < 100
        if (binding.progress.isVisible) binding.progress.setProgressCompat(tab.progress, true)
        binding.reloadButton.isVisible = !tab.isHome && tab.hasPage
        binding.tabsButton.text = tabs.size.toString()

        val pageUrl = tab.url
        val favorite = browserStore.isFavorite(pageUrl)
        applyBarColor(if (tab.isHome || section != SECTION_TAB) null else tab.barColor)
        binding.addFavoriteButton.isEnabled = pageUrl != null
        binding.addFavoriteButton.setText(if (favorite) R.string.remove_favorite_short else R.string.add_favorite_short)
        tabsAdapter.submit(tabs, tab)
    }

    override fun onTabUpdated(tab: Tab) {
        if (!::current.isInitialized) return
        if (tab === current) renderTab() else tabsAdapter.submit(tabs, current)
    }

    override fun onTabPageFinished(tab: Tab, url: String) {
        browserStore.recordVisit(url, tab.title)
        // La cabecera puede pintarse un poco después de "terminar" la carga: miramos ahora y otra vez en un momento.
        tab.webView.postDelayed({ if (tab.isHome.not()) sampleBarColor(tab) }, 300)
        tab.webView.postDelayed({ if (tab.isHome.not()) sampleBarColor(tab) }, 1500)
    }

    override fun openInNewTab(url: String) {
        newTab(url)
    }

    // --- Barra superior ---

    private fun setupToolbar() {
        binding.menuButton.setOnClickListener { anchor ->
            hideKeyboard()
            PopupMenu(this, anchor).apply {
                inflate(R.menu.main)
                prepareMenu(menu)
                setOnMenuItemClickListener { item -> onMenuItem(item.itemId) }
                show()
            }
        }
        binding.homeButton.setOnClickListener {
            hideKeyboard()
            current.showHome()
        }
        binding.tabsButton.setOnClickListener {
            hideKeyboard()
            binding.drawer.openDrawer(GravityCompat.START)
        }
        binding.urlInput.setOnEditorActionListener { v, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                val url = toUrl(v.text.toString())
                hideKeyboard()
                if (url == null) current.showHome() else current.load(url)
                current.webView.requestFocus()
                true
            } else {
                false
            }
        }
        binding.reloadButton.setOnClickListener {
            hideKeyboard()
            if (current.hasPage) {
                current.showPage()
                current.webView.reload()
            }
        }
    }

    private fun prepareMenu(menu: Menu) {
        val tab = current
        val pageUrl = tab.url
        menu.findItem(R.id.action_forward)?.isEnabled = tab.webView.canGoForward()
        menu.findItem(R.id.action_favorite)?.apply {
            isVisible = pageUrl != null
            isChecked = browserStore.isFavorite(pageUrl)
        }
        menu.findItem(R.id.action_block_here)?.apply {
            val host = pageUrl?.let { DomainUtils.hostOf(it) }
            isVisible = host != null && AdBlocker.isEnabled
            isChecked = host != null && !AdBlocker.isAllowlisted(host)
            // El contador de anuncios bloqueados vive aquí, en el menú, en lugar de en la barra.
            title = getString(R.string.block_here) + if (tab.blockedCount > 0) " (${tab.blockedCount})" else ""
        }
    }

    // --- Color de la barra según la página ---

    /** Lee el color de la cabecera de la página (lo que se ve arriba) y tiñe la barra con él, como Samsung Internet. */
    private fun sampleBarColor(tab: Tab) {
        val sampled = sampleTopColor(tab.webView)
        if (sampled != null) {
            setTabColor(tab, sampled)
            return
        }
        // Si no se puede leer la imagen, usamos el color que declara la web (<meta name="theme-color">).
        tab.webView.evaluateJavascript(THEME_COLOR_SCRIPT) { result ->
            val text = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
            parseCssColor(text)?.let { setTabColor(tab, it) }
        }
    }

    private fun setTabColor(tab: Tab, color: Int) {
        tab.barColor = color
        if (tab === current) renderTab()
    }

    /** Color más repetido en una franja fina de la parte superior de la web visible. */
    private fun sampleTopColor(web: WebView): Int? {
        if (web.width == 0 || web.height == 0 || web.scrollY > 0) return null
        return runCatching {
            val scale = 0.125f
            val width = (web.width * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, 3, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            canvas.translate(-web.scrollX.toFloat(), -(4f * resources.displayMetrics.density))
            web.draw(canvas)
            val counts = HashMap<Int, Int>()
            for (x in 0 until width) for (y in 0 until 3) {
                val c = bitmap.getPixel(x, y)
                if (Color.alpha(c) < 200) continue
                // Agrupamos tonos casi iguales (degradados, antialiasing del texto).
                val key = Color.rgb(Color.red(c) and 0xF8, Color.green(c) and 0xF8, Color.blue(c) and 0xF8)
                counts[key] = (counts[key] ?: 0) + 1
            }
            bitmap.recycle()
            val best = counts.maxByOrNull { it.value } ?: return null
            if (best.value < width) null else best.key
        }.getOrNull()
    }

    private fun parseCssColor(text: String?): Int? {
        val t = text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        Regex("rgba?\\(\\s*(\\d+)[ ,]+(\\d+)[ ,]+(\\d+)").find(t)?.let { m ->
            val (r, g, b) = m.destructured
            return Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
        }
        val hex = if (Regex("#[0-9a-f]{3}").matches(t)) "#" + t.drop(1).map { "$it$it" }.joinToString("") else t
        return runCatching { Color.parseColor(hex) }.getOrNull()
    }

    private var barColorApplied = false
    private var appliedBarColor: Int? = null

    /** Pinta la barra superior y la de estado; iconos y texto en blanco o negro según lo oscuro del fondo. */
    private fun applyBarColor(color: Int?) {
        if (barColorApplied && color == appliedBarColor) return
        barColorApplied = true
        appliedBarColor = color
        val background = color ?: ContextCompat.getColor(this, R.color.bar_background)
        val dark = ColorUtils.calculateLuminance(background) < 0.5
        val foreground = when {
            color == null -> ContextCompat.getColor(this, R.color.bar_icon)
            dark -> Color.WHITE
            else -> 0xFF202124.toInt()
        }
        val textColor = if (color == null) ContextCompat.getColor(this, R.color.bar_text) else foreground
        val field = when {
            color == null -> ContextCompat.getColor(this, R.color.url_field)
            dark -> 0x33FFFFFF
            else -> 0x1A000000
        }
        val density = resources.displayMetrics.density
        binding.toolbar.setBackgroundColor(background)
        binding.drawer.setStatusBarBackgroundColor(background)
        @Suppress("DEPRECATION")
        window.statusBarColor = background
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
            if (color == null) resources.getBoolean(R.bool.light_bars) else !dark
        binding.addressBar.background = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(field)
        }
        val tint = ColorStateList.valueOf(foreground)
        binding.homeButton.imageTintList = tint
        binding.reloadButton.imageTintList = tint
        binding.menuButton.imageTintList = tint
        binding.tabsButton.setTextColor(foreground)
        binding.tabsButton.background = GradientDrawable().apply {
            cornerRadius = 6 * density
            setStroke((2 * density).toInt(), foreground)
        }
        binding.urlInput.setTextColor(textColor)
        binding.urlInput.setHintTextColor(ColorUtils.setAlphaComponent(foreground, 0xB0))
    }

    private fun onMenuItem(id: Int): Boolean {
        val web = current.webView
        when (id) {
            R.id.action_forward -> if (web.canGoForward()) {
                current.showPage()
                web.goForward()
            }
            R.id.action_new_tab -> {
                newTab()
                binding.urlInput.requestFocus()
            }
            R.id.action_favorite -> toggleFavorite()
            R.id.action_block_here -> toggleBlockingHere()
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return false
        }
        return true
    }

    private fun toggleFavorite() {
        val url = current.url ?: return
        val added = browserStore.toggleFavorite(url, current.title)
        Toast.makeText(this, if (added) R.string.favorite_added else R.string.favorite_removed, Toast.LENGTH_SHORT).show()
        renderTab()
    }

    private fun toggleBlockingHere() {
        val host = current.url?.let { DomainUtils.hostOf(it) } ?: return
        AdBlocker.setAllowlisted(host, !AdBlocker.isAllowlisted(host))
        renderTab()
        current.webView.reload()
    }

    private fun toUrl(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (URLUtil.isNetworkUrl(text)) return text
        val looksLikeDomain = !text.contains(' ') && text.contains('.') && !text.endsWith('.')
        return if (looksLikeDomain) "https://$text" else "https://duckduckgo.com/?q=" + Uri.encode(text)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(binding.urlInput.windowToken, 0)
        binding.urlInput.clearFocus()
    }

    // --- Página de inicio y panel lateral ---

    private fun setupHome() {
        homeAdapter = HomeAdapter(
            onOpen = { site -> current.load(site.url) },
            onLongPress = { view, tile ->
                showRemoveMenu(view) {
                    if (tile.favorite) browserStore.removeFavorite(tile.site.url) else browserStore.removeTopSite(tile.site.url)
                }
            },
        )
        val grid = GridLayoutManager(this, 4)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (homeAdapter.isFullWidth(position)) 4 else 1
        }
        binding.homeView.layoutManager = grid
        binding.homeView.adapter = homeAdapter
    }

    private fun setupDrawer() {
        tabsAdapter = TabsAdapter(
            onSelect = { tab ->
                selectTab(tab)
                openSection(SECTION_TAB)
                binding.drawer.closeDrawer(GravityCompat.START)
            },
            onClose = { tab -> closeTab(tab) },
        )
        binding.tabsList.layoutManager = LinearLayoutManager(this)
        binding.tabsList.adapter = tabsAdapter

        favoritesAdapter = SitesAdapter(
            onOpen = { site ->
                binding.drawer.closeDrawer(GravityCompat.START)
                openUrl(site.url, newTab = false)
            },
            onLongPress = { view, site -> showRemoveMenu(view) { browserStore.removeFavorite(site.url) } },
        )
        binding.favoritesList.layoutManager = LinearLayoutManager(this)
        binding.favoritesList.adapter = favoritesAdapter

        binding.newTabButton.setOnClickListener {
            binding.drawer.closeDrawer(GravityCompat.START)
            openSection(SECTION_TAB)
            newTab()
            binding.urlInput.requestFocus()
        }
        binding.addFavoriteButton.setOnClickListener { toggleFavorite() }
    }

    private fun renderSites(favorites: List<Site>, top: List<Site>) {
        val items = ArrayList<HomeItem>()
        if (favorites.isNotEmpty()) {
            items += HomeItem.Header(getString(R.string.home_favorites))
            favorites.mapTo(items) { HomeItem.Tile(it, favorite = true) }
        }
        if (top.isNotEmpty()) {
            items += HomeItem.Header(getString(R.string.home_top_sites))
            top.mapTo(items) { HomeItem.Tile(it, favorite = false) }
        }
        if (items.isEmpty()) items += HomeItem.Empty(getString(R.string.home_empty))
        homeAdapter.submit(items)
        favoritesAdapter.submit(favorites)
        binding.favoritesEmpty.isVisible = favorites.isEmpty()
        if (::current.isInitialized) renderTab()
    }

    private fun showRemoveMenu(anchor: View, onRemove: () -> Unit) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.remove)
            setOnMenuItemClickListener {
                onRemove()
                true
            }
            show()
        }
    }

    // --- Barra inferior: Tab, Progreso, Terminado ---

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            showSection(
                when (item.itemId) {
                    R.id.nav_progress -> SECTION_PROGRESS
                    R.id.nav_done -> SECTION_DONE
                    else -> SECTION_TAB
                }
            )
            true
        }
    }

    /** Cambia de sección también desde fuera (notificaciones, pantalla Terminado...). */
    fun openSection(target: Int) {
        val itemId = when (target) {
            SECTION_PROGRESS -> R.id.nav_progress
            SECTION_DONE -> R.id.nav_done
            else -> R.id.nav_tab
        }
        if (binding.bottomNav.selectedItemId != itemId) binding.bottomNav.selectedItemId = itemId else showSection(target)
    }

    private fun showSection(target: Int) {
        section = target
        hideKeyboard()
        val isBrowser = target == SECTION_TAB
        binding.toolbar.isVisible = isBrowser
        binding.browserSection.isVisible = isBrowser
        binding.sectionContainer.isVisible = !isBrowser
        if (isBrowser) {
            if (::current.isInitialized) renderTab()
        } else {
            binding.progress.isVisible = false
            applyBarColor(null)
        }

        val fm = supportFragmentManager
        val tag = when (target) {
            SECTION_PROGRESS -> TAG_PROGRESS
            SECTION_DONE -> TAG_DONE
            else -> null
        }
        val transaction = fm.beginTransaction()
        listOf(TAG_PROGRESS, TAG_DONE).forEach { t -> fm.findFragmentByTag(t)?.let { if (t != tag) transaction.hide(it) } }
        if (tag != null) {
            val existing = fm.findFragmentByTag(tag)
            if (existing != null) transaction.show(existing)
            else transaction.add(R.id.section_container, createSection(tag), tag)
        }
        transaction.commitAllowingStateLoss()
    }

    private fun createSection(tag: String): Fragment = if (tag == TAG_PROGRESS) ProgressFragment() else CompletedFragment()

    // --- Botón de descarga ---

    /** Círculo gris si aún no hay vídeos en la página; rojo cuando hay alguno descargable. */
    private fun updateDownloadButton(options: List<DownloadOption>) {
        val fab = binding.fabDownload
        val ready = options.isNotEmpty()
        fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (ready) R.color.accent else R.color.download_idle))
        fab.contentDescription = options.firstNotNullOfOrNull { it.qualityLabel }?.let { getString(R.string.fab_videos, it) }
            ?: getString(R.string.download)
    }

    private fun onDownloadButton() {
        val items = current.detector.items.value
        if (items.isEmpty() || current.isHome) {
            Toast.makeText(this, R.string.no_video_yet, Toast.LENGTH_LONG).show()
            return
        }
        showQualitySheet(items, current.options.takeIf { current.optionsFor == items })
    }

    /** Hoja inferior: miniatura, título y duración del vídeo, y una tarjeta por resolución con su tamaño. */
    private fun showQualitySheet(items: List<DetectedMedia>, ready: List<DownloadOption>?) {
        val tab = current
        val pageUrl = tab.client.pageUrl
        val b = SheetDetectedBinding.inflate(layoutInflater)
        var customName: String? = null
        var pageThumbnail: Bitmap? = null
        // Por defecto el vídeo se llama como la página, más la calidad elegida.
        fun defaultName(option: DownloadOption) =
            listOfNotNull(tab.title?.takeIf { it.isNotBlank() }, option.qualityLabel).joinToString(" ").ifBlank { null }
        val adapter = QualityAdapter { }
        b.list.layoutManager = GridLayoutManager(this, 3)
        b.list.adapter = adapter
        b.videoTitle.text = tab.title ?: tab.url
        b.renameButton.isEnabled = false
        b.downloadButton.isEnabled = false
        b.closeButton.setOnClickListener { sheet?.dismiss() }
        b.renameButton.setOnClickListener {
            val option = adapter.selected ?: return@setOnClickListener
            showRenameDialog(customName ?: defaultName(option) ?: option.request.fileName.substringBeforeLast('.')) { name ->
                customName = name
                b.videoTitle.text = name
            }
        }
        b.downloadButton.setOnClickListener {
            val option = adapter.selected ?: return@setOnClickListener
            val request = (customName ?: defaultName(option))?.let { name ->
                option.request.copy(fileName = Downloads.suggestFileName(name, option.request.url, option.request.isHls, option.request.mimeType))
            } ?: option.request
            val record = startDownload(request, pageUrl, option.qualityLabel)
            // La miniatura de la página sirve mientras se descarga; al terminar se cambia por un fotograma del vídeo.
            val image = pageThumbnail
            if (record != null && image != null) {
                val file = DownloadStore.get(this).thumbnailFile(record.id)
                lifecycleScope.launch(Dispatchers.IO) { if (!file.exists()) Thumbnails.save(image, file) }
            }
        }
        loadSheetHeader(tab, b) { pageThumbnail = it }

        val job = lifecycleScope.launch {
            val options = ready ?: QualityResolver.resolve(items, tab.title, tab.optionCache)
            b.loading.isVisible = false
            adapter.submit(options)
            b.renameButton.isEnabled = options.isNotEmpty()
            b.downloadButton.isEnabled = options.isNotEmpty()
            options.maxOfOrNull { it.durationSec }?.takeIf { it > 0 && !b.duration.isVisible }?.let { showDuration(b, it) }
            b.sheetSubtitle.setText(
                when {
                    options.isNotEmpty() -> R.string.qualities_found
                    items.all { it.kind == MediaKind.DASH } -> R.string.download_unsupported_dash
                    else -> R.string.qualities_none
                }
            )
        }
        sheet = BottomSheetDialog(this).apply {
            setContentView(b.root)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            setOnDismissListener {
                job.cancel()
                sheet = null
            }
            show()
        }
    }

    /** Miniatura (og:image o el póster del vídeo) y duración, leídas de la propia página. */
    private fun loadSheetHeader(tab: Tab, b: SheetDetectedBinding, onThumbnail: (Bitmap) -> Unit) {
        tab.webView.evaluateJavascript(SHEET_HEADER_SCRIPT) { result ->
            val info = runCatching { JSONObject(JSONTokener(result).nextValue() as String) }.getOrNull() ?: return@evaluateJavascript
            info.optDouble("dur", 0.0).takeIf { it > 0 }?.let { showDuration(b, it) }
            val image = info.optString("img").takeIf { it.startsWith("http") } ?: return@evaluateJavascript
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) { downloadThumbnail(image, tab.client.pageUrl) }
                if (bitmap != null) {
                    b.thumbnail.setImageBitmap(bitmap)
                    onThumbnail(bitmap)
                }
            }
        }
    }

    private fun showDuration(b: SheetDetectedBinding, seconds: Double) {
        b.duration.text = DateUtils.formatElapsedTime(seconds.toLong())
        b.duration.isVisible = true
    }

    private fun downloadThumbnail(url: String, referer: String?): Bitmap? = runCatching {
        val request = Request.Builder().url(url).apply { referer?.let { header("Referer", it) } }.build()
        App.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body!!.bytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 320) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    private fun showRenameDialog(initial: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSingleLine()
            setSelectAllOnFocus(true)
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onSave)
            }
            .show()
        input.requestFocus()
    }

    override fun onDownloadRequested(tab: Tab, request: DownloadRequest) {
        startDownload(request, tab.client.pageUrl, null)
    }

    private fun startDownload(request: DownloadRequest, pageUrl: String?, quality: String?): DownloadRecord? {
        if (MediaDetector.isUnsupportedSite(request.url) || MediaDetector.isUnsupportedSite(pageUrl)) {
            Toast.makeText(this, R.string.download_unsupported_site, Toast.LENGTH_LONG).show()
            return null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = PendingDownload(request, pageUrl, quality)
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val record = Downloads.enqueue(this, request, pageUrl, quality)
        sheet?.dismiss()
        Toast.makeText(this, getString(R.string.download_started, displayName(request.fileName)), Toast.LENGTH_SHORT).show()
        return record
    }

    // --- Pantalla completa y enlaces externos ---

    override fun openExternal(uri: Uri): Boolean {
        try {
            val intent = if (uri.scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            startActivity(intent)
        } catch (_: Exception) {
        }
        return true
    }

    override fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        binding.fullscreenContainer.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        binding.fullscreenContainer.isVisible = true
        binding.mainContainer.isVisible = false
        setFullscreenMode(true)
    }

    override fun hideCustomView() {
        val view = customView ?: return
        binding.fullscreenContainer.removeView(view)
        binding.fullscreenContainer.isVisible = false
        binding.mainContainer.isVisible = true
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        setFullscreenMode(false)
    }

    private fun setFullscreenMode(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val tab = current
                when {
                    binding.drawer.isDrawerOpen(GravityCompat.START) -> binding.drawer.closeDrawer(GravityCompat.START)
                    customView != null -> hideCustomView()
                    section != SECTION_TAB -> openSection(SECTION_TAB)
                    tab.isHome && tab.hasPage -> tab.showPage()
                    !tab.isHome && tab.webView.canGoBack() -> tab.webView.goBack()
                    !tab.isHome -> tab.showHome()
                    tabs.size > 1 -> closeTab(tab)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    companion object {
        const val EXTRA_SECTION = "section"
        const val SECTION_TAB = 0
        const val SECTION_PROGRESS = 1
        const val SECTION_DONE = 2

        private const val KEY_SECTION = "section"
        private const val TAG_PROGRESS = "progress"
        private const val TAG_DONE = "done"
        private const val PREFS = "browser"
        private const val PREF_TABS = "tabs"
        private const val PREF_CURRENT_TAB = "current_tab"

        private const val THEME_COLOR_SCRIPT =
            "(function(){var m=document.querySelector('meta[name=\"theme-color\"]');return m?m.content:'';})()"

        private const val SHEET_HEADER_SCRIPT = "(function(){try{" +
            "var m=document.querySelector('meta[property=\"og:image\"],meta[name=\"twitter:image\"]');" +
            "var img=m?m.content:'',d=0,v=document.querySelectorAll('video');" +
            "for(var i=0;i<v.length;i++){if(!img&&v[i].poster)img=v[i].poster;if(isFinite(v[i].duration)&&v[i].duration>d)d=v[i].duration;}" +
            "return JSON.stringify({img:img?new URL(img,location.href).href:'',dur:d});}catch(e){return '{}';}})()"
    }
}
