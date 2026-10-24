package com.gozenchu.ws

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.text.InputType
import android.text.TextUtils
import android.util.Patterns
import android.util.TypedValue
import android.view.ContextMenu
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.net.URISyntaxException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var siteStore: SiteStore
    private lateinit var sessionStore: SessionStore

    private val tabs = mutableListOf<BrowserTab>()
    private var activeIndex = -1
    private val activeTab: BrowserTab? get() = tabs.getOrNull(activeIndex)
    private val closedTabs = ArrayDeque<TabState>()

    /** Name of the session being worked on; null until the tabs are saved as a session. */
    private var sessionName: String? = null
    private var restoring = false
    private var desktopMode = true
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val defaultUserAgent by lazy { WebSettings.getDefaultUserAgent(this) }

    private lateinit var tabStrip: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var sidebar: View
    private lateinit var sidebarList: LinearLayout
    private lateinit var webArea: FrameLayout
    private lateinit var home: View
    private lateinit var homeList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        siteStore = SiteStore(this)
        sessionStore = SessionStore(this)
        desktopMode = prefs.getBoolean(KEY_DESKTOP, true)

        WebView.setWebContentsDebuggingEnabled((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        CookieManager.getInstance().setAcceptCookie(true)

        setContentView(buildLayout())
        restoreSession(sessionStore.loadCurrent())
        if (savedInstanceState == null) handleIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                goBackOrLeave()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        tabs.forEach { it.webView?.onResume() }
    }

    override fun onStop() {
        persist()
        CookieManager.getInstance().flush()
        tabs.forEach { it.webView?.onPause() }
        super.onStop()
    }

    override fun onDestroy() {
        tabs.forEach { destroyWebView(it) }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goBackOrLeave()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_FILE) return super.onActivityResult(requestCode, resultCode, data)
        val uris = if (resultCode != RESULT_OK) null else data?.clipData?.let { clip ->
            Array(clip.itemCount) { clip.getItemAt(it).uri }
        } ?: WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && handleShortcut(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // ---- Layout ---------------------------------------------------------------------------

    private fun buildLayout(): View {
        tabStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabStrip)
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.PANEL_DARK)
            addView(tabScroll, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(iconButton("+", "新しいタブ (Ctrl+T)") { newTab() })
        }

        address = EditText(this).apply {
            setSingleLine()
            textSize = 14f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.MUTED)
            hint = "URL または検索 (Ctrl+L)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Palette.INPUT)
            }
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    navigate(text.toString())
                    true
                } else {
                    false
                }
            }
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(iconButton("☰", "サイドバー (Ctrl+Shift+B)") { toggleSidebar() })
            addView(iconButton("←", "戻る (Alt+←)") { goBack() })
            addView(iconButton("→", "進む (Alt+→)") { goForward() })
            addView(iconButton("⟳", "再読み込み (Ctrl+R)") { reload() })
            addView(address, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(iconButton(">_", "Termux (Ctrl+Alt+T)") { openTermux() })
            addView(iconButton("⋮", "メニュー") { showMenu(it) })
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        sidebarList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(12))
        }
        sidebar = ScrollView(this).apply {
            setBackgroundColor(Palette.PANEL)
            addView(sidebarList)
            visibility = if (prefs.getBoolean(KEY_SIDEBAR, true)) View.VISIBLE else View.GONE
        }

        webArea = FrameLayout(this)
        homeList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        home = ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            addView(homeList)
        }
        val content = FrameLayout(this).apply {
            addView(webArea, MATCH_PARENT, MATCH_PARENT)
            addView(home, MATCH_PARENT, MATCH_PARENT)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(sidebar, LinearLayout.LayoutParams(dp(184), MATCH_PARENT))
            addView(content, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            addView(tabRow, MATCH_PARENT, WRAP_CONTENT)
            addView(toolbar, MATCH_PARENT, WRAP_CONTENT)
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(3)))
            addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Draw edge-to-edge on every version and pad for system bars and the keyboard ourselves.
            @Suppress("DEPRECATION") // Edge-to-edge is already the default from Android 15.
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        return root
    }

    private fun iconButton(glyph: String, hint: String, onClick: (View) -> Unit) = TextView(this).apply {
        text = glyph
        textSize = 16f
        typeface = Typeface.MONOSPACE
        setTextColor(Palette.TEXT)
        gravity = Gravity.CENTER
        minWidth = dp(40)
        minHeight = dp(40)
        setPadding(dp(8), 0, dp(8), 0)
        contentDescription = hint
        tooltipText = hint
        background = rippleBackground()
        setOnClickListener { onClick(it) }
    }

    private fun rippleBackground(): Drawable? {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return getDrawable(value.resourceId)
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.08f
        setTextColor(Palette.MUTED)
        setPadding(dp(10), dp(16), dp(10), dp(4))
    }

    private fun listRow(
        title: String,
        subtitle: String?,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
        highlighted: Boolean = false,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = if (highlighted) ColorDrawable(Palette.SELECTED) else rippleBackground()
        addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(Palette.TEXT)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        })
        if (subtitle != null) {
            addView(TextView(context).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Palette.MUTED)
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        setOnClickListener { onClick() }
        if (onLongClick != null) {
            // Long-press on touch, right-click with a mouse on DeX.
            setOnLongClickListener { onLongClick(); true }
            setOnContextClickListener { onLongClick(); true }
        }
    }

    private fun renderTabs() {
        tabStrip.removeAllViews()
        tabs.forEachIndexed { i, tab ->
            val selected = i == activeIndex
            val label = TextView(this).apply {
                text = tab.label()
                textSize = 13f
                maxWidth = dp(200)
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (selected) Palette.TEXT else Palette.MUTED)
            }
            val close = TextView(this).apply {
                text = "×"
                textSize = 15f
                setTextColor(Palette.MUTED)
                setPadding(dp(8), 0, dp(2), 0)
                tooltipText = "閉じる (Ctrl+W)"
                setOnClickListener { closeTab(tabs.indexOf(tab)) }
            }
            tabStrip.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(8), dp(8))
                setBackgroundColor(if (selected) Palette.BG else Palette.PANEL_DARK)
                tooltipText = tab.url.ifBlank { null }
                addView(label)
                addView(close)
                setOnClickListener { activate(tabs.indexOf(tab)) }
            })
        }
        tabScroll.post {
            tabStrip.getChildAt(activeIndex)?.let { tabScroll.smoothScrollTo(it.left - dp(48), 0) }
        }
    }

    private fun renderSidebar() {
        sidebarList.removeAllViews()
        sidebarList.addView(sectionLabel("サイト"))
        siteStore.load().forEach { site ->
            sidebarList.addView(listRow(site.name, siteSubtitle(site), { openSite(site) }, { siteActions(site) }))
        }
        sidebarList.addView(listRow("＋ サイトを追加", null, { editSiteDialog(null) }))

        sidebarList.addView(sectionLabel("セッション"))
        val named = sessionStore.listNamed()
        if (sessionName == null) {
            sidebarList.addView(listRow("未保存のセッション", "${tabs.size} タブ · 保存する", { saveSessionDialog() }, highlighted = true))
        }
        named.forEach { session ->
            sidebarList.addView(listRow(
                session.name.orEmpty(),
                "${session.tabs.size} タブ · ${formatTime(session.savedAt)}",
                { switchSession(session) },
                { sessionActions(session) },
                highlighted = session.name == sessionName,
            ))
        }
        sidebarList.addView(listRow("＋ 名前を付けて保存", null, { saveSessionDialog() }))
        sidebarList.addView(listRow("＋ 新しいセッション", null, { switchSession(null) }))

        sidebarList.addView(sectionLabel("ツール"))
        sidebarList.addView(listRow("Termux", "Ctrl+Alt+T", { openTermux() }))
        sidebarList.addView(listRow("ショートカット", null, { showShortcuts() }))
    }

    private fun renderHome() {
        homeList.removeAllViews()
        homeList.addView(TextView(this).apply {
            text = "ws"
            textSize = 34f
            typeface = Typeface.MONOSPACE
            setTextColor(Palette.TEXT)
        })
        homeList.addView(TextView(this).apply {
            text = sessionName?.let { "セッション: $it" } ?: "Galaxy DeX のための開発ワークスペース"
            setTextColor(Palette.MUTED)
        })
        homeList.addView(sectionLabel("サイト"))
        siteStore.load().forEach { site ->
            homeList.addView(listRow(site.name, siteSubtitle(site), { openSite(site) }, { siteActions(site) }))
        }
        val named = sessionStore.listNamed().filter { it.name != sessionName }
        if (named.isNotEmpty()) {
            homeList.addView(sectionLabel("セッションを再開"))
            named.take(8).forEach { session ->
                homeList.addView(listRow(
                    session.name.orEmpty(),
                    "${session.tabs.size} タブ · ${formatTime(session.savedAt)}",
                    { switchSession(session) },
                ))
            }
        }
        homeList.addView(sectionLabel("ヒント"))
        homeList.addView(TextView(this).apply {
            text = "localhost:3000 のように入力すると Termux で動かしている開発サーバーをタブで開けます。\n" +
                "サイトやセッションは長押し / 右クリックで編集できます。"
            setTextColor(Palette.MUTED)
            setPadding(dp(10), 0, dp(10), 0)
        })
    }

    private fun refreshLists() {
        renderSidebar()
        if (home.visibility == View.VISIBLE) renderHome()
    }

    private fun siteSubtitle(site: Site): String {
        val host = Uri.parse(site.url).host ?: site.url
        return if (site.external) "$host ↗" else host
    }

    // ---- Tabs ---------------------------------------------------------------------------

    private fun newTab(url: String = "", title: String = "", index: Int = tabs.size): BrowserTab {
        val tab = BrowserTab(url, title)
        tabs.add(index.coerceIn(0, tabs.size), tab)
        activate(tabs.indexOf(tab))
        if (url.isBlank()) focusAddress()
        return tab
    }

    /** Adds a tab after the current one without switching to it; it loads when first shown. */
    private fun backgroundTab(url: String) {
        tabs.add(activeIndex + 1, BrowserTab(url, url))
        renderTabs()
        persist()
    }

    private fun activate(index: Int) {
        if (tabs.isEmpty()) tabs.add(BrowserTab())
        activeIndex = index.coerceIn(0, tabs.lastIndex)
        val tab = tabs[activeIndex]
        if (tab.webView == null && tab.url.isNotBlank()) attachWebView(tab).loadUrl(tab.url)
        tabs.forEach { it.webView?.visibility = if (it === tab) View.VISIBLE else View.GONE }
        home.visibility = if (tab.isHome) View.VISIBLE else View.GONE
        if (tab.isHome) renderHome() else tab.webView?.requestFocus()
        progress.visibility = View.GONE
        updateAddress()
        renderTabs()
        persist()
    }

    private fun load(tab: BrowserTab, url: String) {
        tab.url = url
        (tab.webView ?: attachWebView(tab)).loadUrl(url)
        if (tab === activeTab) activate(activeIndex)
    }

    private fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        if (tab.url.isNotBlank()) {
            closedTabs.addLast(tab.state())
            while (closedTabs.size > MAX_CLOSED_TABS) closedTabs.removeFirst()
        }
        destroyWebView(tab)
        tabs.removeAt(index)
        val next = when {
            index < activeIndex -> activeIndex - 1
            index == activeIndex -> index.coerceAtMost(tabs.lastIndex)
            else -> activeIndex
        }
        activate(next.coerceAtLeast(0))
    }

    private fun reopenClosedTab() {
        val state = closedTabs.removeLastOrNull() ?: return
        newTab(state.url, state.title, activeIndex + 1)
    }

    private fun destroyWebView(tab: BrowserTab) {
        val wv = tab.webView ?: return
        tab.webView = null
        webArea.removeView(wv)
        wv.stopLoading()
        wv.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun attachWebView(tab: BrowserTab): WebView {
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = userAgent()
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = TabClient(tab)
        wv.webChromeClient = TabChromeClient(tab)
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        }
        wv.setOnCreateContextMenuListener { menu, _, _ -> linkMenu(menu, wv) }
        wv.visibility = if (tab === activeTab) View.VISIBLE else View.GONE
        webArea.addView(wv, MATCH_PARENT, MATCH_PARENT)
        tab.webView = wv
        return wv
    }

    private inner class TabClient(private val tab: BrowserTab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            return when (request.url.scheme?.lowercase()) {
                "http", "https", "about", "data", "blob", "javascript" -> false
                else -> {
                    openExternal(request.url.toString())
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (url != null) tab.url = url
            if (tab === activeTab) updateAddress()
        }

        // Also fires for history.pushState, which SPAs like GitHub and Claude use for navigation.
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (url != null) tab.url = url
            if (tab === activeTab) updateAddress()
            persist()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            persist()
        }

        // A killed renderer would otherwise crash the app; recreate the tab when it is shown again.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (tab.webView === view) {
                destroyWebView(tab)
            } else {
                webArea.removeView(view)
                view.destroy()
            }
            webArea.post {
                val i = tabs.indexOf(tab)
                if (i >= 0 && i == activeIndex) activate(i)
            }
            return true
        }
    }

    private inner class TabChromeClient(private val tab: BrowserTab) : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) {
            tab.title = title.orEmpty()
            renderTabs()
            persist()
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (tab !== activeTab) return
            progress.progress = newProgress
            progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        // target="_blank" and window.open (including OAuth popups) open as a new tab.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val child = BrowserTab()
            tabs.add(tabs.indexOf(tab) + 1, child)
            (resultMsg.obj as WebView.WebViewTransport).webView = attachWebView(child)
            resultMsg.sendToTarget()
            activate(tabs.indexOf(child))
            return true
        }

        override fun onCloseWindow(window: WebView) {
            val i = tabs.indexOfFirst { it.webView === window }
            if (i >= 0) closeTab(i)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            val intent = fileChooserParams.createIntent()
            if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            return try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_FILE)
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }
    }

    private fun linkMenu(menu: ContextMenu, wv: WebView) {
        val hit = wv.hitTestResult
        if (hit.type != WebView.HitTestResult.SRC_ANCHOR_TYPE) return
        val url = hit.extra ?: return
        menu.setHeaderTitle(url)
        menu.add("新しいタブで開く").setOnMenuItemClickListener { newTab(url, index = activeIndex + 1); true }
        menu.add("バックグラウンドのタブで開く").setOnMenuItemClickListener { backgroundTab(url); true }
        menu.add("外部ブラウザで開く").setOnMenuItemClickListener { openExternal(url); true }
        menu.add("リンクをコピー").setOnMenuItemClickListener { copyToClipboard(url); true }
    }

    // ---- Navigation ---------------------------------------------------------------------

    private fun navigate(input: String) {
        val url = UrlInput.resolve(input) ?: return
        val tab = activeTab ?: newTab()
        load(tab, url)
        hideKeyboard()
        tab.webView?.requestFocus()
    }

    private fun openSite(site: Site) {
        if (site.external) {
            openExternal(site.url)
            return
        }
        val existing = tabs.indexOfFirst { it.url.startsWith(site.url.trimEnd('/')) }
        val current = activeTab
        when {
            existing >= 0 -> activate(existing)
            current != null && current.isHome -> load(current, site.url)
            else -> newTab(site.url, site.name)
        }
    }

    private fun goBack() {
        activeTab?.webView?.takeIf { it.canGoBack() }?.goBack()
    }

    private fun goForward() {
        activeTab?.webView?.takeIf { it.canGoForward() }?.goForward()
    }

    private fun reload() {
        activeTab?.webView?.reload()
    }

    private fun goBackOrLeave() {
        val wv = activeTab?.webView
        if (wv != null && wv.canGoBack()) wv.goBack() else moveTaskToBack(true)
    }

    private fun updateAddress() {
        if (!address.hasFocus()) address.setText(activeTab?.url.orEmpty())
    }

    private fun focusAddress() {
        address.requestFocus()
        address.selectAll()
        getSystemService(InputMethodManager::class.java).showSoftInput(address, 0)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus()
    }

    private fun toggleSidebar() {
        val show = sidebar.visibility != View.VISIBLE
        sidebar.visibility = if (show) View.VISIBLE else View.GONE
        prefs.edit().putBoolean(KEY_SIDEBAR, show).apply()
    }

    private fun userAgent(): String? = if (desktopMode) UserAgent.desktop(defaultUserAgent) else null

    private fun setDesktopMode(enabled: Boolean) {
        desktopMode = enabled
        prefs.edit().putBoolean(KEY_DESKTOP, enabled).apply()
        tabs.forEach { it.webView?.settings?.userAgentString = userAgent() }
        reload()
    }

    private fun handleShortcut(e: KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed
        val shift = e.isShiftPressed
        val alt = e.isAltPressed
        val key = e.keyCode
        when {
            ctrl && alt && key == KeyEvent.KEYCODE_T -> openTermux()
            ctrl && shift && key == KeyEvent.KEYCODE_T -> reopenClosedTab()
            ctrl && key == KeyEvent.KEYCODE_T -> newTab()
            ctrl && key == KeyEvent.KEYCODE_W -> closeTab(activeIndex)
            ctrl && key == KeyEvent.KEYCODE_TAB -> activate((activeIndex + if (shift) -1 else 1).mod(tabs.size))
            ctrl && key == KeyEvent.KEYCODE_L -> focusAddress()
            (ctrl && key == KeyEvent.KEYCODE_R) || key == KeyEvent.KEYCODE_F5 -> reload()
            ctrl && shift && key == KeyEvent.KEYCODE_B -> toggleSidebar()
            ctrl && shift && key == KeyEvent.KEYCODE_S -> saveSessionDialog()
            ctrl && key in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                val n = key - KeyEvent.KEYCODE_1
                activate(if (key == KeyEvent.KEYCODE_9) tabs.lastIndex else n.coerceAtMost(tabs.lastIndex))
            }
            alt && key == KeyEvent.KEYCODE_DPAD_LEFT -> goBack()
            alt && key == KeyEvent.KEYCODE_DPAD_RIGHT -> goForward()
            else -> return false
        }
        return true
    }

    // ---- Sessions -----------------------------------------------------------------------

    private fun snapshot() = Session(sessionName, tabs.map { it.state() }, activeIndex.coerceAtLeast(0), System.currentTimeMillis())

    /** Auto-saves the open tabs so the work is there on next launch (and in the named session). */
    private fun persist() {
        if (!restoring) sessionStore.saveCurrent(snapshot())
    }

    private fun restoreSession(session: Session?) {
        restoring = true
        tabs.forEach { destroyWebView(it) }
        tabs.clear()
        sessionName = session?.name
        session?.tabs?.forEach { tabs.add(BrowserTab(it.url, it.title)) }
        activeIndex = -1
        restoring = false
        activate(session?.activeIndex ?: 0)
        refreshLists()
    }

    private fun switchSession(target: Session?) {
        if (target != null && target.name == sessionName) return
        val current = snapshot()
        when {
            current.name != null -> sessionStore.saveNamed(current)
            // Never drop unsaved work when switching away from it.
            current.tabs.any { it.url.isNotBlank() } ->
                sessionStore.saveNamed(current.copy(name = "自動保存 ${formatTime(current.savedAt)}"))
        }
        restoreSession(target?.name?.let { sessionStore.findNamed(it) } ?: target)
    }

    private fun saveSessionDialog() {
        val input = dialogInput("セッション名", sessionName ?: "作業 ${formatTime(System.currentTimeMillis())}")
        AlertDialog.Builder(this)
            .setTitle("セッションを保存")
            .setMessage("開いているタブを名前を付けて保存します。以降の変更は自動で保存されます。")
            .setView(form(input))
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { return@setPositiveButton }
                sessionName = name
                sessionStore.saveNamed(snapshot())
                persist()
                refreshLists()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun sessionActions(session: Session) {
        val name = session.name ?: return
        val actions = arrayOf("開く", "タブを現在のセッションに追加", "名前を変更", "削除")
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> switchSession(session)
                    1 -> {
                        session.tabs.forEach { tabs.add(BrowserTab(it.url, it.title)) }
                        renderTabs()
                        persist()
                    }
                    2 -> renameSessionDialog(name)
                    3 -> confirm("「$name」を削除しますか？") {
                        sessionStore.deleteNamed(name)
                        if (sessionName == name) sessionName = null
                        persist()
                        refreshLists()
                    }
                }
            }
            .show()
    }

    private fun renameSessionDialog(oldName: String) {
        val input = dialogInput("セッション名", oldName)
        AlertDialog.Builder(this)
            .setTitle("名前を変更")
            .setView(form(input))
            .setPositiveButton("変更") { _, _ ->
                val newName = input.text.toString().trim()
                val stored = sessionStore.findNamed(oldName)
                if (newName.isEmpty() || newName == oldName || stored == null) return@setPositiveButton
                sessionStore.deleteNamed(oldName)
                sessionStore.saveNamed(stored.copy(name = newName))
                if (sessionName == oldName) sessionName = newName
                persist()
                refreshLists()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ---- Sites --------------------------------------------------------------------------

    private fun siteActions(site: Site) {
        val actions = arrayOf("編集", "上へ移動", "下へ移動", "外部ブラウザで開く", "削除")
        AlertDialog.Builder(this)
            .setTitle(site.name)
            .setItems(actions) { _, which ->
                val list = siteStore.load().toMutableList()
                val i = list.indexOfFirst { it.id == site.id }
                if (i < 0) return@setItems
                when (which) {
                    0 -> editSiteDialog(site)
                    1 -> if (i > 0) Collections.swap(list, i, i - 1)
                    2 -> if (i < list.lastIndex) Collections.swap(list, i, i + 1)
                    3 -> openExternal(site.url)
                    4 -> list.removeAt(i)
                }
                if (which in 1..4) {
                    siteStore.save(list)
                    refreshLists()
                }
            }
            .show()
    }

    private fun editSiteDialog(site: Site?, name: String = "", url: String = "") {
        val nameInput = dialogInput("名前", site?.name ?: name)
        val urlInput = dialogInput("URL", site?.url ?: url).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val external = CheckBox(this).apply {
            text = "外部ブラウザで開く（Google ログインが必要なサイト向け）"
            isChecked = site?.external ?: false
        }
        AlertDialog.Builder(this)
            .setTitle(if (site == null) "サイトを追加" else "サイトを編集")
            .setView(form(nameInput, urlInput, external))
            .setPositiveButton("保存") { _, _ ->
                val resolved = UrlInput.resolve(urlInput.text.toString()) ?: return@setPositiveButton
                val updated = Site(
                    id = site?.id ?: Site.newId(),
                    name = nameInput.text.toString().trim().ifEmpty { Uri.parse(resolved).host ?: resolved },
                    url = resolved,
                    external = external.isChecked,
                )
                val list = siteStore.load()
                siteStore.save(if (site == null) list + updated else list.map { if (it.id == site.id) updated else it })
                refreshLists()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ---- Outside the app ----------------------------------------------------------------

    private fun openTermux() {
        val launch = packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
        if (launch != null) {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Termux が見つかりません")
            .setMessage("ターミナルは Termux を使います。F-Droid 版のインストールをおすすめします。")
            .setPositiveButton("F-Droid で開く") { _, _ -> openExternal(TERMUX_FDROID_URL) }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /** Opens a URL in another app; on DeX it opens in a window next to ws. */
    private fun openExternal(url: String) {
        val intent = try {
            if (url.startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    // Pages must not be able to target arbitrary components.
                    component = null
                    selector = null
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
        } catch (e: URISyntaxException) {
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            val tab = activeTab
            if (fallback != null && URLUtil.isNetworkUrl(fallback) && tab != null) {
                load(tab, fallback)
            } else {
                toast("開けるアプリがありません: $url")
            }
        }
    }

    private fun download(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        val uri = Uri.parse(url)
        if (!URLUtil.isNetworkUrl(url)) {
            toast("この形式のダウンロードには対応していません")
            return
        }
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(uri)
            .setMimeType(mimeType)
            .addRequestHeader("User-Agent", userAgent)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        getSystemService(DownloadManager::class.java).enqueue(request)
        toast("ダウンロード中: $fileName")
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val match = Patterns.WEB_URL.matcher(text)
        val url = UrlInput.resolve(if (match.find()) match.group() else text) ?: return
        newTab(url)
    }

    // ---- Menus and dialogs --------------------------------------------------------------

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        menu.add("新しいタブ  Ctrl+T").setOnMenuItemClickListener { newTab(); true }
        menu.add("閉じたタブを開く  Ctrl+Shift+T").setEnabled(closedTabs.isNotEmpty())
            .setOnMenuItemClickListener { reopenClosedTab(); true }
        menu.add("このページをサイトに追加").setEnabled(activeTab?.isHome == false).setOnMenuItemClickListener {
            activeTab?.let { editSiteDialog(null, it.title, it.url) }
            true
        }
        menu.add("外部ブラウザで開く").setEnabled(activeTab?.isHome == false).setOnMenuItemClickListener {
            activeTab?.let { openExternal(it.url) }
            true
        }
        menu.add("リンクをコピー").setEnabled(activeTab?.isHome == false).setOnMenuItemClickListener {
            activeTab?.let { copyToClipboard(it.url) }
            true
        }
        menu.add("セッションを保存  Ctrl+Shift+S").setOnMenuItemClickListener { saveSessionDialog(); true }
        menu.add("デスクトップ表示").setCheckable(true).setChecked(desktopMode)
            .setOnMenuItemClickListener { setDesktopMode(!desktopMode); true }
        menu.add("Termux を開く  Ctrl+Alt+T").setOnMenuItemClickListener { openTermux(); true }
        menu.add("キーボードショートカット").setOnMenuItemClickListener { showShortcuts(); true }
        popup.show()
    }

    private fun showShortcuts() {
        val message = TextView(this).apply {
            text = SHORTCUTS
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        AlertDialog.Builder(this)
            .setTitle("キーボードショートカット")
            .setView(message)
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun confirm(message: String, onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> onOk() }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun dialogInput(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine()
        setSelectAllOnFocus(true)
    }

    private fun form(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
        views.forEach { addView(it) }
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("URL", text))
        toast("コピーしました")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(millis))

    private companion object {
        const val KEY_DESKTOP = "desktop_mode"
        const val KEY_SIDEBAR = "sidebar_visible"
        const val REQUEST_FILE = 1
        const val MAX_CLOSED_TABS = 25
    }
}
