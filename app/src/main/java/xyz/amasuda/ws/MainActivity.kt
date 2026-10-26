package xyz.amasuda.ws

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
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
import android.view.WindowInsetsController
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
    private lateinit var keymapStore: KeymapStore
    private lateinit var keymap: Keymap

    private val tabs = mutableListOf<BrowserTab>()
    private var activeIndex = -1
    private val activeTab: BrowserTab? get() = tabs.getOrNull(activeIndex)
    private val closedTabs = ArrayDeque<TabState>()

    /** Name of the session being worked on; null until the tabs are saved as a session. */
    private var sessionName: String? = null
    private var restoring = false
    private var desktopMode = true
    private var fullscreen = true
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val defaultUserAgent by lazy { WebSettings.getDefaultUserAgent(this) }

    private lateinit var tabStrip: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var sidebar: View
    private lateinit var content: View
    private lateinit var sidebarList: LinearLayout
    private lateinit var webArea: FrameLayout
    private lateinit var home: View
    private lateinit var homeList: LinearLayout

    /** Tooltips that show the current shortcut, refreshed when the keymap changes. */
    private val shortcutHints = mutableListOf<() -> Unit>()

    /** Toolbar buttons hidden on phone-sized windows, where back is a gesture and the rest is in the menu. */
    private val wideOnly = mutableListOf<View>()

    /** Phone-sized window: the sidebar overlays the page like a drawer and starts hidden. */
    private val compact: Boolean get() = resources.configuration.screenWidthDp < COMPACT_WIDTH_DP

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        siteStore = SiteStore(this)
        sessionStore = SessionStore(this)
        keymapStore = KeymapStore(this)
        keymap = keymapStore.load()
        desktopMode = prefs.getBoolean(KEY_DESKTOP, true)
        fullscreen = prefs.getBoolean(KEY_FULLSCREEN, true)

        WebView.setWebContentsDebuggingEnabled((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        CookieManager.getInstance().setAcceptCookie(true)

        setContentView(buildLayout())
        applyFullscreen()
        applyWindowSize()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Moving between the phone screen and DeX arrives here instead of recreating the activity.
        applyWindowSize()
        applyFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Before Android 11 the immersive flags are cleared whenever a dialog or the keyboard takes focus.
        if (hasFocus && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) applyFullscreen()
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

    // Only used before Android 13; newer versions go through the OnBackInvokedCallback in onCreate.
    @SuppressLint("GestureBackNavigation")
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
        if (event.action == KeyEvent.ACTION_DOWN) {
            val action = keymap.actionFor(KeyBinding.of(event))
            if (action != null) {
                perform(action)
                return true
            }
        }
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
            addView(iconButton("+", "新しいタブ", ShortcutAction.NEW_TAB) { newTab() })
        }

        address = EditText(this).apply {
            setSingleLine()
            textSize = 14f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.MUTED)
            hint = withShortcut("URL または検索", ShortcutAction.FOCUS_ADDRESS)
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
            addView(iconButton("☰", "サイドバー", ShortcutAction.TOGGLE_SIDEBAR) { toggleSidebar() })
            addView(iconButton("←", "戻る", ShortcutAction.BACK) { goBack() }.also { wideOnly += it })
            addView(iconButton("→", "進む", ShortcutAction.FORWARD) { goForward() }.also { wideOnly += it })
            addView(iconButton("⟳", "再読み込み", ShortcutAction.RELOAD) { reload() })
            addView(address, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(
                iconButton("⛶", "全画面", ShortcutAction.TOGGLE_FULLSCREEN) { setFullscreen(!fullscreen) }
                    .also { wideOnly += it },
            )
            addView(iconButton(">_", "Termux", ShortcutAction.OPEN_TERMUX) { openTermux() })
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
            elevation = dp(8).toFloat()
            addView(sidebarList)
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
        content = FrameLayout(this).apply {
            addView(webArea, MATCH_PARENT, MATCH_PARENT)
            addView(home, MATCH_PARENT, MATCH_PARENT)
        }
        // The sidebar sits on top of the content; on wide windows the content is inset next to it.
        val body = FrameLayout(this).apply {
            addView(content, MATCH_PARENT, MATCH_PARENT)
            addView(sidebar, FrameLayout.LayoutParams(dp(SIDEBAR_WIDTH_DP), MATCH_PARENT, Gravity.START))
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

    private fun iconButton(
        glyph: String,
        hint: String,
        action: ShortcutAction? = null,
        onClick: (View) -> Unit,
    ) = TextView(this).apply {
        text = glyph
        textSize = 16f
        typeface = Typeface.MONOSPACE
        setTextColor(Palette.TEXT)
        gravity = Gravity.CENTER
        minWidth = dp(40)
        minHeight = dp(40)
        setPadding(dp(8), 0, dp(8), 0)
        val update = {
            val text = withShortcut(hint, action)
            contentDescription = text
            tooltipText = text
        }
        update()
        shortcutHints += update
        background = rippleBackground()
        setOnClickListener { onClick(it) }
    }

    private fun withShortcut(label: String, action: ShortcutAction?): String =
        action?.let { keymap.label(it) }?.let { "$label ($it)" } ?: label

    private fun refreshShortcutHints() {
        address.hint = withShortcut("URL または検索", ShortcutAction.FOCUS_ADDRESS)
        shortcutHints.forEach { it() }
        renderTabs()
        renderSidebar()
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
                tooltipText = withShortcut("閉じる", ShortcutAction.CLOSE_TAB)
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
        sidebarList.addView(listRow("Termux", keymap.label(ShortcutAction.OPEN_TERMUX), { openTermux() }))
        sidebarList.addView(listRow("ショートカットの設定", null, { shortcutSettings() }))
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
        closeSidebarIfCompact()
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

    private fun sidebarKey() = if (compact) KEY_SIDEBAR_COMPACT else KEY_SIDEBAR

    private fun applyWindowSize() {
        wideOnly.forEach { it.visibility = if (compact) View.GONE else View.VISIBLE }
        applySidebar()
    }

    private fun applySidebar() {
        val show = prefs.getBoolean(sidebarKey(), !compact)
        sidebar.visibility = if (show) View.VISIBLE else View.GONE
        (content.layoutParams as FrameLayout.LayoutParams).marginStart =
            if (show && !compact) dp(SIDEBAR_WIDTH_DP) else 0
        content.requestLayout()
    }

    private fun setSidebarVisible(show: Boolean) {
        prefs.edit().putBoolean(sidebarKey(), show).apply()
        applySidebar()
    }

    private fun toggleSidebar() = setSidebarVisible(sidebar.visibility != View.VISIBLE)

    /** On a phone the sidebar is a drawer: picking something from it closes it. */
    private fun closeSidebarIfCompact() {
        if (compact && sidebar.visibility == View.VISIBLE) setSidebarVisible(false)
    }

    private fun setFullscreen(enabled: Boolean) {
        fullscreen = enabled
        prefs.edit().putBoolean(KEY_FULLSCREEN, enabled).apply()
        applyFullscreen()
    }

    /** Hides the status and navigation bars; swiping from an edge shows them for a moment. */
    private fun applyFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (fullscreen) {
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.systemBars())
            } else {
                controller.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (fullscreen) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } else {
                0
            }
        }
    }

    private fun userAgent(): String? = if (desktopMode) UserAgent.desktop(defaultUserAgent) else null

    private fun setDesktopMode(enabled: Boolean) {
        desktopMode = enabled
        prefs.edit().putBoolean(KEY_DESKTOP, enabled).apply()
        tabs.forEach { it.webView?.settings?.userAgentString = userAgent() }
        reload()
    }

    private fun perform(action: ShortcutAction) {
        when (action) {
            ShortcutAction.NEW_TAB -> newTab()
            ShortcutAction.CLOSE_TAB -> closeTab(activeIndex)
            ShortcutAction.REOPEN_TAB -> reopenClosedTab()
            ShortcutAction.NEXT_TAB -> activate((activeIndex + 1).mod(tabs.size))
            ShortcutAction.PREV_TAB -> activate((activeIndex - 1).mod(tabs.size))
            ShortcutAction.TAB_1, ShortcutAction.TAB_2, ShortcutAction.TAB_3, ShortcutAction.TAB_4,
            ShortcutAction.TAB_5, ShortcutAction.TAB_6, ShortcutAction.TAB_7, ShortcutAction.TAB_8 ->
                activate((action.ordinal - ShortcutAction.TAB_1.ordinal).coerceAtMost(tabs.lastIndex))
            ShortcutAction.LAST_TAB -> activate(tabs.lastIndex)
            ShortcutAction.FOCUS_ADDRESS -> focusAddress()
            ShortcutAction.RELOAD -> reload()
            ShortcutAction.BACK -> goBack()
            ShortcutAction.FORWARD -> goForward()
            ShortcutAction.TOGGLE_SIDEBAR -> toggleSidebar()
            ShortcutAction.TOGGLE_FULLSCREEN -> setFullscreen(!fullscreen)
            ShortcutAction.SAVE_SESSION -> saveSessionDialog()
            ShortcutAction.OPEN_TERMUX -> openTermux()
            ShortcutAction.SHOW_SHORTCUTS -> shortcutSettings()
        }
    }

    // ---- Shortcut settings --------------------------------------------------------------

    private fun shortcutSettings() {
        val actions = ShortcutAction.entries
        val items = actions.map { action ->
            val keys = keymap.bindingsFor(action).joinToString(" / ") { it.label() }.ifEmpty { "なし" }
            "${action.label}　—　$keys"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("キーボードショートカット")
            .setItems(items) { _, which -> shortcutActions(actions[which]) }
            .setPositiveButton("閉じる", null)
            .setNeutralButton("すべて元に戻す") { _, _ ->
                confirm("すべてのショートカットをデフォルトに戻しますか？") { updateKeymap(Keymap.DEFAULT) }
            }
            .show()
    }

    private fun shortcutActions(action: ShortcutAction) {
        val defaults = action.defaults.joinToString(" / ") { it.label() }.ifEmpty { "なし" }
        val choices = arrayOf("キーを追加…", "割り当てを解除", "デフォルトに戻す ($defaults)")
        AlertDialog.Builder(this)
            .setTitle(action.label)
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> captureKey(action)
                    1 -> updateKeymap(keymap.clear(action))
                    2 -> updateKeymap(keymap.reset(action))
                }
                if (which != 0) shortcutSettings()
            }
            .setOnCancelListener { shortcutSettings() }
            .show()
    }

    private fun captureKey(action: ShortcutAction) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(action.label)
            .setMessage("割り当てるキーを押してください。\nCtrl / Alt / Meta との組み合わせか F1〜F12 が使えます。\nEsc でキャンセル。")
            .setNegativeButton("キャンセル") { _, _ -> shortcutSettings() }
            .create()
        // The dialog sees keys before its buttons, so every key (Enter and Tab included) can be captured.
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || KeyEvent.isModifierKey(keyCode)) return@setOnKeyListener true
            if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK) {
                dialog.dismiss()
                shortcutSettings()
                return@setOnKeyListener true
            }
            val binding = KeyBinding.of(event)
            if (!binding.isUsableShortcut()) {
                toast("${binding.label()} は文字入力と重なるので使えません")
                return@setOnKeyListener true
            }
            val previous = keymap.actionFor(binding)
            updateKeymap(keymap.assign(action, binding))
            if (previous != null && previous != action) toast("${binding.label()} を「${previous.label}」から付け替えました")
            dialog.dismiss()
            shortcutSettings()
            true
        }
        dialog.show()
    }

    private fun updateKeymap(updated: Keymap) {
        keymap = updated
        keymapStore.save(updated)
        refreshShortcutHints()
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
        closeSidebarIfCompact()
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
        val onPage = activeTab?.isHome == false
        menu.add(withShortcut("新しいタブ", ShortcutAction.NEW_TAB)).setOnMenuItemClickListener { newTab(); true }
        menu.add(withShortcut("閉じたタブを開く", ShortcutAction.REOPEN_TAB)).setEnabled(closedTabs.isNotEmpty())
            .setOnMenuItemClickListener { reopenClosedTab(); true }
        menu.add("このページをサイトに追加").setEnabled(onPage).setOnMenuItemClickListener {
            activeTab?.let { editSiteDialog(null, it.title, it.url) }
            true
        }
        menu.add("外部ブラウザで開く").setEnabled(onPage).setOnMenuItemClickListener {
            activeTab?.let { openExternal(it.url) }
            true
        }
        menu.add("リンクをコピー").setEnabled(onPage).setOnMenuItemClickListener {
            activeTab?.let { copyToClipboard(it.url) }
            true
        }
        menu.add(withShortcut("セッションを保存", ShortcutAction.SAVE_SESSION))
            .setOnMenuItemClickListener { saveSessionDialog(); true }
        menu.add(withShortcut("全画面", ShortcutAction.TOGGLE_FULLSCREEN)).setCheckable(true).setChecked(fullscreen)
            .setOnMenuItemClickListener { setFullscreen(!fullscreen); true }
        menu.add("デスクトップ表示").setCheckable(true).setChecked(desktopMode)
            .setOnMenuItemClickListener { setDesktopMode(!desktopMode); true }
        menu.add(withShortcut("Termux を開く", ShortcutAction.OPEN_TERMUX)).setOnMenuItemClickListener { openTermux(); true }
        menu.add(withShortcut("ショートカットの設定", ShortcutAction.SHOW_SHORTCUTS))
            .setOnMenuItemClickListener { shortcutSettings(); true }
        popup.show()
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
        const val KEY_SIDEBAR_COMPACT = "sidebar_visible_compact"
        const val KEY_FULLSCREEN = "fullscreen"
        const val COMPACT_WIDTH_DP = 600
        const val SIDEBAR_WIDTH_DP = 184
        const val REQUEST_FILE = 1
        const val MAX_CLOSED_TABS = 25
    }
}
