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
import android.content.pm.PackageManager
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
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Patterns
import android.util.TypedValue
import android.view.ContextMenu
import android.view.DragEvent
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import android.widget.ImageView
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
import kotlin.math.abs
import org.json.JSONArray

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var starStore: StarStore
    private lateinit var sessionStore: SessionStore
    private lateinit var keymapStore: KeymapStore
    private lateinit var faviconStore: FaviconStore
    private lateinit var keymap: Keymap

    private val tabs = mutableListOf<BrowserTab>()
    private var activeIndex = -1
    private val activeTab: BrowserTab? get() = tabs.getOrNull(activeIndex)
    private val closedTabs = ArrayDeque<TabState>()
    private var tabDisplay = TabDisplay.DEFAULT
    private var groups = listOf<TabGroup>()

    /** What each child of [tabList] shows: a group header or a tab. */
    private var renderedRows = listOf<TabRow>()

    /** The tab being moved by a long-press drag, and whether it has moved yet. */
    private var dragTab: BrowserTab? = null
    private var dragMoved = false

    /** Name of the session being worked on; null until the tabs are saved as a session. */
    private var sessionName: String? = null
    private var restoring = false
    private var desktopMode = true
    private var fullscreen = true
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val defaultUserAgent by lazy { WebSettings.getDefaultUserAgent(this) }

    private lateinit var tabList: LinearLayout
    private lateinit var tabScroll: ScrollView
    private lateinit var dropIndicator: View
    private lateinit var tabHeader: LinearLayout
    private lateinit var tabHeaderLabel: View
    private lateinit var starButton: View
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var sidebar: View
    private lateinit var sidebarHandle: View
    private lateinit var sidebarHandleLine: View
    private lateinit var scrim: View
    private lateinit var content: View
    private lateinit var webArea: FrameLayout
    private lateinit var home: View
    private lateinit var homeList: LinearLayout
    private lateinit var pullIndicator: TextView
    private lateinit var pullGesture: PullGesture

    /** When the user last pressed a mouse button or touched a page; pages can only open tabs right after. */
    private var lastPointerPress = 0L

    /** When the user last pressed a key; link hints can only open tabs right after. */
    private var lastKeyPress = 0L

    /** Tooltips that show the current shortcut, refreshed when the keymap changes. */
    private val shortcutHints = mutableListOf<() -> Unit>()

    /** Toolbar buttons hidden on phone-sized windows, where back is a gesture and the rest is in the menu. */
    private val wideOnly = mutableListOf<View>()

    /** Phone-sized window: the sidebar overlays the page like a drawer and starts hidden. */
    private val compact: Boolean get() = resources.configuration.screenWidthDp < COMPACT_WIDTH_DP

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        starStore = StarStore(this)
        sessionStore = SessionStore(this)
        keymapStore = KeymapStore(this)
        faviconStore = FaviconStore(this)
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
        if (requestCode == REQUEST_EXPORT_SETTINGS || requestCode == REQUEST_IMPORT_SETTINGS) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                if (requestCode == REQUEST_EXPORT_SETTINGS) writeSettings(uri) else readSettings(uri)
            }
            return
        }
        if (requestCode != REQUEST_FILE) return super.onActivityResult(requestCode, resultCode, data)
        val uris = if (resultCode != RESULT_OK) null else data?.clipData?.let { clip ->
            Array(clip.itemCount) { clip.getItemAt(it).uri }
        } ?: WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            lastKeyPress = SystemClock.uptimeMillis()
            val binding = KeyBinding.of(event)
            val action = keymap.actionFor(binding)
            if (action != null) {
                perform(action)
                return true
            }
            if (showHintsOutsidePage(binding)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * F is caught by the page's script, which only sees keys while the WebView has focus. When the focus is
     * elsewhere (a toolbar button, nothing at all) and not in a text field, start the hints from here instead.
     */
    private fun showHintsOutsidePage(binding: KeyBinding): Boolean {
        val newTab = when (binding) {
            KeyBinding(KeyEvent.KEYCODE_F) -> false
            KeyBinding(KeyEvent.KEYCODE_F, KeyBinding.SHIFT) -> true
            else -> return false
        }
        val wv = activeTab?.webView ?: return false
        val focus = currentFocus
        if (focus === wv || focus is EditText || wv.visibility != View.VISIBLE) return false
        showLinkHints(newTab)
        return true
    }

    private fun showLinkHints(newTab: Boolean) {
        val wv = activeTab?.webView ?: return
        wv.requestFocus()
        wv.evaluateJavascript(LinkHints.showScript(newTab), null)
    }

    // ---- Layout ---------------------------------------------------------------------------

    private fun buildLayout(): View {
        tabHeaderLabel = sectionLabel(getString(R.string.section_tabs)).apply { setPadding(dp(10), 0, 0, 0) }
        tabHeader = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(tabHeaderLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(iconButton("▾", getString(R.string.search_tabs), ShortcutAction.SEARCH_TABS) { searchTabsDialog() })
            addView(iconButton("+", getString(R.string.new_tab), ShortcutAction.NEW_TAB) {
                closeSidebarIfCompact()
                newTab()
            })
        }
        tabList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(12))
        }
        // Shows where a dragged tab will land: a line between rows, or a box around the tab or group it joins.
        dropIndicator = View(this).apply { visibility = View.GONE }
        tabScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(FrameLayout(context).apply {
                addView(tabList, MATCH_PARENT, WRAP_CONTENT)
                addView(dropIndicator, FrameLayout.LayoutParams(MATCH_PARENT, dp(2)))
            })
            setOnDragListener(TabDragListener())
        }

        address = EditText(this).apply {
            setSingleLine()
            textSize = 14f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.MUTED)
            hint = withShortcut(getString(R.string.address_hint), ShortcutAction.FOCUS_ADDRESS)
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
            addView(iconButton("☰", getString(R.string.sidebar), ShortcutAction.TOGGLE_SIDEBAR) { toggleSidebar() })
            addView(iconButton("←", getString(R.string.back), ShortcutAction.BACK) { goBack() }.also { wideOnly += it })
            addView(iconButton("→", getString(R.string.forward), ShortcutAction.FORWARD) { goForward() }.also { wideOnly += it })
            addView(iconButton("⟳", getString(R.string.reload), ShortcutAction.RELOAD) { reload() })
            addView(address, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(iconButton("★", getString(R.string.section_stars), ShortcutAction.SHOW_STARS) { showStarMenu(it) }.also { starButton = it })
            addView(
                iconButton("⛶", getString(R.string.fullscreen), ShortcutAction.TOGGLE_FULLSCREEN) { setFullscreen(!fullscreen) }
                    .also { wideOnly += it },
            )
            addView(iconButton(">_", "Termux", ShortcutAction.OPEN_TERMUX) { openTermux() })
            addView(iconButton("⋮", getString(R.string.menu)) { showMenu(it) })
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        sidebarHandleLine = View(this).apply { setBackgroundColor(Palette.PANEL_DARK) }
        // Drag the right edge to resize; the wide strip is the touch target, the line is what shows.
        sidebarHandle = FrameLayout(this).apply {
            contentDescription = getString(R.string.sidebar_width)
            pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)
            addView(sidebarHandleLine, FrameLayout.LayoutParams(dp(1), MATCH_PARENT, Gravity.END))
            setOnTouchListener(SidebarResizer())
        }
        sidebar = FrameLayout(this).apply {
            setBackgroundColor(Palette.PANEL)
            elevation = dp(8).toFloat()
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(tabHeader, MATCH_PARENT, WRAP_CONTENT)
                addView(tabScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            }, MATCH_PARENT, MATCH_PARENT)
            addView(sidebarHandle, FrameLayout.LayoutParams(dp(SIDEBAR_HANDLE_DP), MATCH_PARENT, Gravity.END))
        }
        // Compact only: dims the page behind the drawer; tapping it closes the drawer.
        scrim = View(this).apply {
            setBackgroundColor(0x80000000.toInt())
            visibility = View.GONE
            setOnClickListener { setSidebarVisible(false) }
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
        pullIndicator = TextView(this).apply {
            text = "⟳"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Palette.TEXT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.INPUT)
            }
            elevation = dp(4).toFloat()
            visibility = View.GONE
        }
        pullGesture = PullGesture(
            triggerPx = dp(PULL_TRIGGER_DP).toFloat(),
            slopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat(),
        )
        content = FrameLayout(this).apply {
            addView(webArea, MATCH_PARENT, MATCH_PARENT)
            addView(home, MATCH_PARENT, MATCH_PARENT)
            addView(
                pullIndicator,
                FrameLayout.LayoutParams(dp(PULL_INDICATOR_DP), dp(PULL_INDICATOR_DP), Gravity.TOP or Gravity.CENTER_HORIZONTAL),
            )
        }
        // The sidebar sits on top of the content; on wide windows the content is inset next to it.
        val body = FrameLayout(this).apply {
            addView(content, MATCH_PARENT, MATCH_PARENT)
            addView(scrim, MATCH_PARENT, MATCH_PARENT)
            addView(sidebar, FrameLayout.LayoutParams(dp(SidebarWidth.DEFAULT_DP), MATCH_PARENT, Gravity.START))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
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
        address.hint = withShortcut(getString(R.string.address_hint), ShortcutAction.FOCUS_ADDRESS)
        shortcutHints.forEach { it() }
        renderTabs()
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
        tabList.removeAllViews()
        renderedRows = TabGroups.rows(tabs.map { it.slot() }, groups, activeIndex)
        renderedRows.forEach { row ->
            when (row) {
                is TabRow.Header -> groups.firstOrNull { it.id == row.group }?.let { tabList.addView(groupHeader(it, row)) }
                    ?: tabList.addView(View(this))
                is TabRow.Tab -> tabList.addView(tabRow(tabs[row.index], row.index))
            }
        }
        // While a tab is dragged the list follows the finger instead of the active tab.
        if (dragTab != null) return
        tabScroll.post { rowOf(activeIndex)?.let { scrollTabIntoView(it) } }
    }

    private fun rowOf(index: Int): View? =
        tabList.getChildAt(renderedRows.indexOfFirst { it is TabRow.Tab && it.index == index })

    private fun tabRow(tab: BrowserTab, index: Int): View {
        val home = getString(R.string.home)
        val iconOnly = tabDisplay == TabDisplay.ICON
        val selected = index == activeIndex
        val showIcon = tab.pinned || tabDisplay != TabDisplay.TITLE
        // The icon rail is too narrow for a close button; Ctrl+W and the tab menu still close tabs.
        val showClose = !tab.pinned && !iconOnly
        val lastPinned = tab.pinned && tabs.getOrNull(index + 1)?.pinned != true
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (iconOnly) Gravity.CENTER else Gravity.CENTER_VERTICAL
            minimumHeight = dp(36)
            setPadding(dp(if (iconOnly) 0 else 8), dp(6), dp(if (showClose) 4 else 8), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(if (selected) Palette.SELECTED else Palette.PANEL)
            }
            alpha = if (tab === dragTab) DRAGGED_TAB_ALPHA else 1f
            contentDescription = tab.label(home)
            tooltipText = tab.details(home)
            if (showIcon) addView(tabIcon(tab))
            if (!iconOnly) {
                addView(TextView(context).apply {
                    text = tab.label(home)
                    textSize = 13f
                    setSingleLine()
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(if (selected) Palette.TEXT else Palette.MUTED)
                    if (showIcon) setPadding(dp(8), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            if (showClose) {
                addView(TextView(context).apply {
                    text = "×"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(Palette.MUTED)
                    minWidth = dp(28)
                    tooltipText = withShortcut(getString(R.string.close), ShortcutAction.CLOSE_TAB)
                    setOnClickListener { closeTab(tabs.indexOf(tab)) }
                })
            }
            setOnClickListener { selectTab(tabs.indexOf(tab)) }
            setOnLongClickListener { startTabDrag(it, tab) }
            setOnContextClickListener { tabMenu(it, tab); true }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                // Grouped tabs are indented under their header; a gap separates the pinned tabs from the rest.
                if (tab.group != null && !iconOnly) marginStart = dp(GROUP_INDENT_DP)
                bottomMargin = dp(if (lastPinned) PINNED_GAP_DP else 1)
            }
        }
    }

    /** A group's header: tap to fold or unfold it, long-press or right-click for the group menu. */
    private fun groupHeader(group: TabGroup, row: TabRow.Header): View {
        val arrow = if (group.collapsed) "▸" else "▾"
        val iconOnly = tabDisplay == TabDisplay.ICON
        return TextView(this).apply {
            text = if (iconOnly) "$arrow${group.name.take(1)}" else "$arrow  ${group.name}  ·  ${row.size}"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Palette.TEXT)
            gravity = if (iconOnly) Gravity.CENTER else Gravity.CENTER_VERTICAL
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(32)
            setPadding(dp(if (iconOnly) 0 else 8), 0, dp(if (iconOnly) 0 else 8), 0)
            contentDescription = group.name
            tooltipText = "${group.name} · ${tabCount(row.size)}"
            background = rippleBackground()
            setOnClickListener { setGroupCollapsed(group.id, !group.collapsed) }
            setOnLongClickListener { groupMenu(it, group.id); true }
            setOnContextClickListener { groupMenu(it, group.id); true }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) }
        }
    }

    private fun scrollTabIntoView(row: View) {
        val top = tabScroll.scrollY
        val bottom = top + tabScroll.height
        when {
            row.top < top -> tabScroll.smoothScrollTo(0, row.top - dp(TAB_SCROLL_MARGIN_DP))
            row.bottom > bottom -> tabScroll.smoothScrollTo(0, row.bottom - tabScroll.height + dp(TAB_SCROLL_MARGIN_DP))
        }
    }

    /** Fits the tab list's header to the display: a row with a label, or stacked buttons on the icon rail. */
    private fun applyTabHeader() {
        val iconOnly = tabDisplay == TabDisplay.ICON
        tabHeader.orientation = if (iconOnly) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        tabHeaderLabel.visibility = if (iconOnly) View.GONE else View.VISIBLE
        sidebarHandle.visibility = if (iconOnly) View.GONE else View.VISIBLE
    }

    /** The tab's favicon, or a letter badge until the page has sent one. */
    private fun tabIcon(tab: BrowserTab): View {
        val icon = faviconStore.load(tab.url)
        val view = if (icon != null) {
            ImageView(this).apply { setImageBitmap(icon) }
        } else {
            TextView(this).apply {
                text = if (tab.url.isBlank()) "⌂" else Favicons.fallbackLetter(tab.url, tab.title)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Palette.TEXT)
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setColor(Palette.INPUT)
                }
            }
        }
        view.layoutParams = LinearLayout.LayoutParams(dp(TAB_ICON_DP), dp(TAB_ICON_DP))
        return view
    }

    /** Long-press picks a tab up to move it; releasing it without moving opens its menu instead. */
    private fun startTabDrag(view: View, tab: BrowserTab): Boolean {
        dragTab = tab
        dragMoved = false
        view.alpha = DRAGGED_TAB_ALPHA
        if (!view.startDragAndDrop(ClipData.newPlainText("", ""), View.DragShadowBuilder(view), null, 0)) {
            dragTab = null
            view.alpha = 1f
            tabMenu(view, tab)
        }
        return true
    }

    /** Shows where the dragged tab would land, drops it there, and scrolls the list at its edges. */
    private inner class TabDragListener : View.OnDragListener {
        private var startY = 0f

        override fun onDrag(v: View, event: DragEvent): Boolean {
            val tab = dragTab ?: return false
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> startY = event.y
                DragEvent.ACTION_DRAG_LOCATION -> {
                    if (abs(event.y - startY) > ViewConfiguration.get(this@MainActivity).scaledTouchSlop) dragMoved = true
                    val edge = dp(TAB_DRAG_EDGE_DP)
                    when {
                        event.y < edge -> tabScroll.scrollBy(0, -dp(TAB_DRAG_SCROLL_DP))
                        event.y > tabScroll.height - edge -> tabScroll.scrollBy(0, dp(TAB_DRAG_SCROLL_DP))
                    }
                    val target = dropTarget(event.y.toInt() + tabScroll.scrollY)
                    val changes = target != null && dragMoved && TabGroups.drop(tabs.map { it.slot() }, tabs.indexOf(tab), target, "") != null
                    showDropIndicator(if (changes) target else null)
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    dragMoved = true
                    showDropIndicator(null)
                }
                DragEvent.ACTION_DROP -> if (dragMoved) dropTarget(event.y.toInt() + tabScroll.scrollY)?.let { dropTab(tab, it) }
                DragEvent.ACTION_DRAG_ENDED -> finishTabDrag(tab)
            }
            return true
        }
    }

    /** The drop target at [y] in the tab list's coordinates, or null until the redrawn list is laid out. */
    private fun dropTarget(y: Int): TabDrop? {
        if (tabList.isLayoutRequested || tabList.childCount != renderedRows.size) return null
        val spans = List(tabList.childCount) { tabList.getChildAt(it).let { c -> c.top until c.bottom } }
        return TabGroups.dropAt(renderedRows, spans, y)
    }

    private fun showDropIndicator(target: TabDrop?) {
        val rowIndex = when (target) {
            null -> -1
            // Before a group's first tab means above the group's header.
            is TabDrop.Before -> renderedRows.indexOfFirst { it is TabRow.Header && it.firstIndex == target.index }
                .takeIf { it >= 0 } ?: renderedRows.indexOfFirst { it is TabRow.Tab && it.index == target.index }
            is TabDrop.After -> renderedRows.indexOfFirst { it is TabRow.Tab && it.index == target.index }
            is TabDrop.Onto -> renderedRows.indexOfFirst { it is TabRow.Tab && it.index == target.index }
            is TabDrop.IntoGroup -> renderedRows.indexOfFirst { it is TabRow.Header && it.group == target.group }
            TabDrop.End -> renderedRows.lastIndex
        }
        val row = tabList.getChildAt(rowIndex)
        if (target == null || row == null) {
            dropIndicator.visibility = View.GONE
            return
        }
        val box = target is TabDrop.Onto || target is TabDrop.IntoGroup
        dropIndicator.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, if (box) row.height else dp(2))
        dropIndicator.background = if (box) {
            GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setStroke(dp(2), Palette.ACCENT)
            }
        } else {
            ColorDrawable(Palette.ACCENT)
        }
        dropIndicator.translationY = when {
            box -> row.top
            target is TabDrop.Before -> row.top - dp(1)
            else -> row.bottom - dp(1)
        }.toFloat()
        dropIndicator.visibility = View.VISIBLE
    }

    /** Moves the tab to the drop target, joining, leaving or creating a group as [TabGroups.drop] decides. */
    private fun dropTab(tab: BrowserTab, target: TabDrop) {
        val newGroupId = TabGroup.newId()
        val placed = TabGroups.drop(tabs.map { it.slot() }, tabs.indexOf(tab), target, newGroupId) ?: return
        applyPlacement(placed)
        if (placed.any { it.group == newGroupId }) groups = groups + TabGroup(newGroupId, newGroupName())
    }

    private fun finishTabDrag(tab: BrowserTab) {
        dragTab = null
        showDropIndicator(null)
        renderTabs()
        if (dragMoved) {
            persist()
        } else {
            tabList.post { rowOf(tabs.indexOf(tab))?.let { tabMenu(it, tab) } }
        }
    }

    private fun tabMenu(anchor: View, tab: BrowserTab) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(getString(if (tab.pinned) R.string.unpin_tab else R.string.pin_tab))
            .setOnMenuItemClickListener { togglePin(tab); true }
        if (!tab.pinned) {
            popup.menu.addSubMenu(getString(R.string.add_to_group)).apply {
                add(getString(R.string.new_group)).setOnMenuItemClickListener { moveToNewGroup(tab); true }
                groups.filter { it.id != tab.group }.forEach { group ->
                    add(group.name).setOnMenuItemClickListener { moveToGroup(tab, group.id); true }
                }
            }
            if (tab.group != null) {
                popup.menu.add(getString(R.string.remove_from_group)).setOnMenuItemClickListener {
                    applyPlacement(TabGroups.removeFromGroup(tabs.map { it.slot() }, tabs.indexOf(tab)))
                    renderTabs()
                    persist()
                    true
                }
            }
        }
        addTabDisplayMenu(popup.menu)
        popup.menu.add(getString(R.string.action_close_tab)).setOnMenuItemClickListener { closeTab(tabs.indexOf(tab)); true }
        popup.show()
    }

    private fun groupMenu(anchor: View, groupId: String) {
        val group = groups.firstOrNull { it.id == groupId } ?: return
        val popup = PopupMenu(this, anchor)
        popup.menu.add(getString(R.string.rename_group)).setOnMenuItemClickListener { renameGroupDialog(groupId); true }
        popup.menu.add(getString(if (group.collapsed) R.string.expand_group else R.string.collapse_group))
            .setOnMenuItemClickListener { setGroupCollapsed(groupId, !group.collapsed); true }
        popup.menu.add(getString(R.string.ungroup)).setOnMenuItemClickListener {
            tabs.filter { it.group == groupId }.forEach { it.group = null }
            groups = TabGroups.prune(groups, tabs.map { it.slot() })
            renderTabs()
            persist()
            true
        }
        popup.menu.add(getString(R.string.close_group)).setOnMenuItemClickListener {
            tabs.filter { it.group == groupId }.forEach { closeTab(tabs.indexOf(it)) }
            true
        }
        popup.show()
    }

    /** Applies a new tab arrangement from [TabGroups], dropping groups left without tabs. */
    private fun applyPlacement(placed: List<Placed>) {
        val old = tabs.toList()
        placed.forEach { old[it.from].group = it.group }
        reorderTabs(placed.map { old[it.from] })
        groups = TabGroups.prune(groups, tabs.map { it.slot() })
    }

    private fun moveToNewGroup(tab: BrowserTab) {
        applyPlacement(TabGroups.removeFromGroup(tabs.map { it.slot() }, tabs.indexOf(tab)))
        val group = TabGroup(TabGroup.newId(), newGroupName())
        tab.group = group.id
        groups = groups + group
        renderTabs()
        persist()
    }

    private fun moveToGroup(tab: BrowserTab, groupId: String) {
        val last = tabs.indexOfLast { it.group == groupId }
        if (last < 0) return
        TabGroups.drop(tabs.map { it.slot() }, tabs.indexOf(tab), TabDrop.Onto(last), "")?.let { applyPlacement(it) }
        renderTabs()
        persist()
    }

    private fun setGroupCollapsed(groupId: String, collapsed: Boolean) {
        groups = groups.map { if (it.id == groupId) it.copy(collapsed = collapsed) else it }
        renderTabs()
        persist()
    }

    private fun renameGroupDialog(groupId: String) {
        val group = groups.firstOrNull { it.id == groupId } ?: return
        val input = dialogInput(getString(R.string.group_name), group.name)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_group))
            .setView(form(input))
            .setPositiveButton(getString(R.string.rename_button)) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { return@setPositiveButton }
                groups = groups.map { if (it.id == groupId) it.copy(name = name) else it }
                renderTabs()
                persist()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** "Group N" with the smallest N not already used. */
    private fun newGroupName(): String {
        val names = groups.map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { getString(R.string.group_default_name, it) }.first { it !in names }
    }

    private fun addTabDisplayMenu(menu: Menu) {
        menu.addSubMenu(getString(R.string.tab_display)).apply {
            TabDisplay.entries.forEach { mode ->
                add(getString(mode.labelRes)).setCheckable(true).setChecked(mode == tabDisplay)
                    .setOnMenuItemClickListener { setTabDisplay(mode); true }
            }
        }
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
            text = sessionName?.let { getString(R.string.home_session, it) } ?: getString(R.string.home_tagline)
            setTextColor(Palette.MUTED)
        })
        homeList.addView(sectionLabel(getString(R.string.section_stars)))
        starStore.load().forEach { star ->
            homeList.addView(listRow(star.name, starSubtitle(star), { openStar(star) }, { starActions(star) }))
        }
        val named = sessionStore.listNamed().filter { it.name != sessionName }
        if (named.isNotEmpty()) {
            homeList.addView(sectionLabel(getString(R.string.section_resume)))
            named.take(8).forEach { session ->
                homeList.addView(listRow(
                    session.name.orEmpty(),
                    "${tabCount(session.tabs.size)} · ${formatTime(session.savedAt)}",
                    { switchSession(session) },
                ))
            }
        }
        homeList.addView(sectionLabel(getString(R.string.section_tips)))
        homeList.addView(TextView(this).apply {
            text = getString(R.string.home_tips)
            setTextColor(Palette.MUTED)
            setPadding(dp(10), 0, dp(10), 0)
        })
    }

    private fun refreshLists() {
        if (home.visibility == View.VISIBLE) renderHome()
    }

    private fun starSubtitle(star: Star): String {
        val host = Uri.parse(star.url).host ?: star.url
        return if (star.external) "$host ↗" else host
    }

    // ---- Tabs ---------------------------------------------------------------------------

    private fun newTab(
        url: String = "",
        title: String = "",
        index: Int = tabs.size,
        textZoom: Int = TextZoom.DEFAULT,
        pinned: Boolean = false,
        group: String? = null,
    ): BrowserTab {
        val tab = BrowserTab(url, title, textZoom, pinned, group)
        activate(insertTab(index, tab))
        if (url.isBlank()) focusAddress()
        return tab
    }

    /** Adds a tab after the current one without switching to it; it loads when first shown. */
    private fun backgroundTab(url: String) {
        insertTab(activeIndex + 1, BrowserTab(url, url, activeTab?.textZoom ?: TextZoom.DEFAULT, group = activeTab?.group))
        renderTabs()
        persist()
    }

    /**
     * Adds a tab as close to [index] as the pinned tabs allow and returns where it went. It keeps its group
     * (its opener's) only next to that group, and joins the group it lands inside so groups stay together.
     */
    private fun insertTab(index: Int, tab: BrowserTab): Int {
        val at = TabOrder.insertionIndex(index, tabs.count { it.pinned }, tabs.size, tab.pinned)
        val wanted = tab.group?.takeIf { id -> groups.any { it.id == id } }
        tab.group = if (tab.pinned) null else TabGroups.groupForInsert(tabs.getOrNull(at - 1)?.group, tabs.getOrNull(at)?.group, wanted)
        tabs.add(at, tab)
        if (at <= activeIndex) activeIndex++
        return at
    }

    /** Replaces the tab order, keeping the same tab active. */
    private fun reorderTabs(order: List<BrowserTab>) {
        val active = activeTab
        tabs.clear()
        tabs.addAll(order)
        activeIndex = active?.let { tabs.indexOf(it) } ?: -1
    }

    /** Pinned tabs stay at the start of the strip as icons, in every session they are saved in. */
    private fun togglePin(tab: BrowserTab) {
        if (!tab.pinned) applyPlacement(TabGroups.removeFromGroup(tabs.map { it.slot() }, tabs.indexOf(tab)))
        tab.pinned = !tab.pinned
        reorderTabs(TabOrder.pinnedFirst(tabs) { it.pinned })
        renderTabs()
        persist()
    }

    private fun setTabDisplay(mode: TabDisplay) {
        tabDisplay = mode
        applySidebar()
        renderTabs()
        persist()
    }

    private fun searchTabsDialog() {
        val home = getString(R.string.home)
        val input = dialogInput(getString(R.string.search_tabs_hint), "").apply { imeOptions = EditorInfo.IME_ACTION_GO }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dialog = AlertDialog.Builder(this)
            .setTitle(withShortcut(getString(R.string.search_tabs), ShortcutAction.SEARCH_TABS))
            .setView(form(input, ScrollView(this).apply { addView(results) }))
            .setNegativeButton(getString(R.string.close), null)
            .create()
        fun matches() = TabSearch.filter(tabs.map { it.state() }, input.text.toString())
        fun open(index: Int) {
            dialog.dismiss()
            activate(index)
        }
        fun render() {
            results.removeAllViews()
            val found = matches()
            if (found.isEmpty()) {
                results.addView(TextView(this).apply {
                    text = getString(R.string.no_matching_tabs)
                    setTextColor(Palette.MUTED)
                    setPadding(dp(10), dp(12), dp(10), dp(12))
                })
            }
            found.forEach { i ->
                val tab = tabs[i]
                results.addView(listRow(tab.label(home), tab.url.ifBlank { null }, { open(i) }, highlighted = i == activeIndex))
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render()
        })
        // Enter opens the first match, so a few letters and Enter are enough to switch.
        input.setOnEditorActionListener { _, _, event ->
            if (event == null || event.action == KeyEvent.ACTION_DOWN) matches().firstOrNull()?.let { open(it) }
            true
        }
        render()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    /** Switches to a tab picked from the tab list; on a phone the drawer gets out of the way. */
    private fun selectTab(index: Int) {
        closeSidebarIfCompact()
        activate(index)
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
        groups = TabGroups.prune(groups, tabs.map { it.slot() })
        val next = when {
            index < activeIndex -> activeIndex - 1
            index == activeIndex -> index.coerceAtMost(tabs.lastIndex)
            else -> activeIndex
        }
        activate(next.coerceAtLeast(0))
    }

    private fun reopenClosedTab() {
        val state = closedTabs.removeLastOrNull() ?: return
        newTab(state.url, state.title, activeIndex + 1, state.textZoom, state.pinned, state.group)
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
            textZoom = tab.textZoom
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = TabClient(tab)
        wv.webChromeClient = TabChromeClient(tab)
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        }
        wv.setOnCreateContextMenuListener { menu, _, _ -> linkMenu(menu, wv) }
        wv.addJavascriptInterface(LinkBridge(tab), LinkClicks.INTERFACE)
        wv.addJavascriptInterface(PullBridge(tab), PullToRefresh.INTERFACE)
        wv.addJavascriptInterface(HintBridge(tab), LinkHints.INTERFACE)
        // Record presses without consuming them (a middle click arrives as a generic motion event).
        wv.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) lastPointerPress = SystemClock.uptimeMillis()
            trackPull(wv, e)
            false
        }
        wv.setOnGenericMotionListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) lastPointerPress = SystemClock.uptimeMillis()
            false
        }
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
            applyPageFixes(view, url)
            persist()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            applyPageFixes(view, url)
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

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            if (icon != null && faviconStore.save(view.url ?: tab.url, icon)) renderTabs()
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (tab !== activeTab) return
            progress.progress = newProgress
            progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        // target="_blank" and window.open (including OAuth popups) open as a new tab.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val child = BrowserTab(textZoom = tab.textZoom, group = tab.group)
            val index = insertTab(tabs.indexOf(tab) + 1, child)
            (resultMsg.obj as WebView.WebViewTransport).webView = attachWebView(child)
            resultMsg.sendToTarget()
            activate(index)
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

    private fun applyPageFixes(view: WebView, url: String?) {
        view.evaluateJavascript(LinkClicks.SCRIPT, null)
        view.evaluateJavascript(PullToRefresh.SCRIPT, null)
        view.evaluateJavascript(LinkHints.SCRIPT, null)
        PageFixes.scriptFor(url)?.let { view.evaluateJavascript(it, null) }
    }

    /** Receives Ctrl+click / middle-click on links from [LinkClicks.SCRIPT]. */
    private inner class LinkBridge(private val tab: BrowserTab) {
        @JavascriptInterface
        fun open(url: String?, foreground: Boolean) {
            if (!LinkClicks.isOpenable(url)) return
            webArea.post {
                // Ignore calls a page makes on its own, without a click in the tab being looked at.
                if (tab !== activeTab || SystemClock.uptimeMillis() - lastPointerPress > LINK_CLICK_WINDOW_MS) return@post
                lastPointerPress = 0L
                if (foreground) {
                    newTab(url!!, index = activeIndex + 1, textZoom = tab.textZoom, group = tab.group)
                } else {
                    backgroundTab(url!!)
                    toast(getString(R.string.opened_in_background))
                }
            }
        }
    }

    /** Gives [LinkHints.SCRIPT] its labels and opens the links picked with Shift+F. */
    private inner class HintBridge(private val tab: BrowserTab) {
        @JavascriptInterface
        fun labels(count: Int): String = JSONArray(LinkHints.labels(count)).toString()

        @JavascriptInterface
        fun openInNewTab(url: String?) {
            if (!LinkClicks.isOpenable(url)) return
            webArea.post {
                // Ignore calls a page makes on its own, without a key press in the tab being looked at.
                if (tab !== activeTab || SystemClock.uptimeMillis() - lastKeyPress > LINK_CLICK_WINDOW_MS) return@post
                lastKeyPress = 0L
                backgroundTab(url!!)
                toast(getString(R.string.opened_in_background))
            }
        }
    }

    /** Receives whether a touch may pull to refresh from [PullToRefresh.SCRIPT]. */
    private inner class PullBridge(private val tab: BrowserTab) {
        @JavascriptInterface
        fun start(atTop: Boolean) {
            webArea.post { if (tab === activeTab) pullGesture.pageReported(atTop) }
        }

        @JavascriptInterface
        fun block() {
            webArea.post {
                if (tab !== activeTab) return@post
                pullGesture.block()
                showPull()
            }
        }
    }

    /** Follows finger drags on the page without consuming them; a long enough pull at the top reloads. */
    private fun trackPull(wv: WebView, e: MotionEvent) {
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> pullGesture.down(e.x, e.y, documentAtTop = !wv.canScrollVertically(-1))
            MotionEvent.ACTION_MOVE -> pullGesture.move(e.x, e.y)
            MotionEvent.ACTION_UP -> if (pullGesture.up()) wv.reload()
            else -> pullGesture.cancel()
        }
        showPull()
    }

    /** Slides the reload indicator down with the pull; it turns blue once releasing would reload. */
    private fun showPull() {
        if (!pullGesture.pulling) {
            pullIndicator.visibility = View.GONE
            return
        }
        val progress = pullGesture.progress.coerceAtMost(PULL_MAX_PROGRESS)
        pullIndicator.visibility = View.VISIBLE
        pullIndicator.translationY = progress * dp(PULL_TRIGGER_DP) - dp(PULL_INDICATOR_DP)
        pullIndicator.rotation = progress * 270f
        pullIndicator.alpha = progress.coerceAtMost(1f)
        (pullIndicator.background as GradientDrawable).setColor(if (progress >= 1f) Palette.ACCENT else Palette.INPUT)
    }

    private fun linkMenu(menu: ContextMenu, wv: WebView) {
        val hit = wv.hitTestResult
        if (hit.type != WebView.HitTestResult.SRC_ANCHOR_TYPE) return
        val url = hit.extra ?: return
        menu.setHeaderTitle(url)
        menu.add(getString(R.string.open_in_new_tab)).setOnMenuItemClickListener {
            newTab(url, index = activeIndex + 1, textZoom = activeTab?.textZoom ?: TextZoom.DEFAULT, group = activeTab?.group)
            true
        }
        menu.add(getString(R.string.open_in_background_tab)).setOnMenuItemClickListener { backgroundTab(url); true }
        menu.add(getString(R.string.open_in_external_browser)).setOnMenuItemClickListener { openExternal(url); true }
        menu.add(getString(R.string.copy_link)).setOnMenuItemClickListener { copyToClipboard(url); true }
    }

    // ---- Navigation ---------------------------------------------------------------------

    private fun navigate(input: String) {
        val url = UrlInput.resolve(input) ?: return
        val tab = activeTab ?: newTab()
        load(tab, url)
        hideKeyboard()
        tab.webView?.requestFocus()
    }

    private fun openStar(star: Star) {
        closeSidebarIfCompact()
        if (star.external) {
            openExternal(star.url)
            return
        }
        val stars = starStore.load()
        val existing = tabs.indexOfFirst { Star.ownerOf(it.url, stars)?.id == star.id }
        val current = activeTab
        when {
            existing >= 0 -> activate(existing)
            current != null && current.isHome -> load(current, star.url)
            else -> newTab(star.url, star.name)
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
        scrim.visibility = if (show && compact) View.VISIBLE else View.GONE
        applyTabHeader()
        applySidebarWidth(
            SidebarWidth.of(tabDisplay, prefs.getInt(KEY_SIDEBAR_WIDTH, SidebarWidth.DEFAULT_DP), resources.configuration.screenWidthDp, compact),
        )
    }

    private fun sidebarWidthDp(): Int = SidebarWidth.clamp(
        prefs.getInt(KEY_SIDEBAR_WIDTH, SidebarWidth.DEFAULT_DP),
        resources.configuration.screenWidthDp,
        compact,
    )

    /** Sizes the sidebar and, on wide windows, moves the page next to it. */
    private fun applySidebarWidth(widthDp: Int) {
        sidebar.layoutParams = (sidebar.layoutParams as FrameLayout.LayoutParams).apply { width = dp(widthDp) }
        // Reassign the params (not just mutate them): a changed marginStart is not re-resolved
        // after the first layout, which left a blank strip where a hidden sidebar used to be.
        val inset = if (sidebar.visibility == View.VISIBLE && !compact) dp(widthDp) else 0
        content.layoutParams = (content.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = inset
            marginStart = inset
        }
    }

    private inner class SidebarResizer : View.OnTouchListener {
        private var startX = 0f
        private var startWidthDp = 0
        private var widthDp = 0

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startWidthDp = sidebarWidthDp()
                    widthDp = startWidthDp
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    sidebarHandleLine.setBackgroundColor(Palette.ACCENT)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startX) / resources.displayMetrics.density
                    val next = SidebarWidth.clamp(
                        startWidthDp + dx.toInt(),
                        resources.configuration.screenWidthDp,
                        compact,
                    )
                    if (next != widthDp) {
                        widthDp = next
                        applySidebarWidth(next)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sidebarHandleLine.setBackgroundColor(Palette.PANEL_DARK)
                    prefs.edit().putInt(KEY_SIDEBAR_WIDTH, widthDp).apply()
                }
                else -> return false
            }
            return true
        }
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

    /** Changes the current tab's text size; it is saved with the tab in the session. */
    private fun setTextZoom(change: (Int) -> Int) {
        val tab = activeTab ?: return
        tab.textZoom = TextZoom.clamp(change(tab.textZoom))
        tab.webView?.settings?.textZoom = tab.textZoom
        toast(getString(R.string.text_size_toast, tab.textZoom))
        persist()
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
            ShortcutAction.SEARCH_TABS -> searchTabsDialog()
            ShortcutAction.TOGGLE_PIN -> activeTab?.let { togglePin(it) }
            ShortcutAction.FOCUS_ADDRESS -> focusAddress()
            ShortcutAction.RELOAD -> reload()
            ShortcutAction.BACK -> goBack()
            ShortcutAction.FORWARD -> goForward()
            ShortcutAction.ZOOM_IN -> setTextZoom { TextZoom.larger(it) }
            ShortcutAction.ZOOM_OUT -> setTextZoom { TextZoom.smaller(it) }
            ShortcutAction.ZOOM_RESET -> setTextZoom { TextZoom.DEFAULT }
            ShortcutAction.TOGGLE_SIDEBAR -> toggleSidebar()
            ShortcutAction.TOGGLE_FULLSCREEN -> setFullscreen(!fullscreen)
            ShortcutAction.SAVE_SESSION -> saveSessionDialog()
            ShortcutAction.SHOW_STARS -> showStarMenu(starButton)
            ShortcutAction.LINK_HINTS -> showLinkHints(newTab = false)
            ShortcutAction.LINK_HINTS_NEW_TAB -> showLinkHints(newTab = true)
            ShortcutAction.OPEN_TERMUX -> openTermux()
            ShortcutAction.SHOW_SHORTCUTS -> shortcutSettings()
        }
    }

    // ---- Shortcut settings --------------------------------------------------------------

    /** Lists every operation and its keys as a two-column table; picking a row edits its keys. */
    private fun shortcutSettings() {
        val table = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(shortcutRow(getString(R.string.shortcut_column_action), getString(R.string.shortcut_column_keys), header = true))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.shortcuts_title))
            .setView(ScrollView(this).apply { addView(table) })
            .setPositiveButton(getString(R.string.close), null)
            .setNeutralButton(getString(R.string.reset_all)) { _, _ ->
                confirm(getString(R.string.reset_all_confirm)) { updateKeymap(Keymap.DEFAULT) }
            }
            .create()
        ShortcutAction.entries.forEachIndexed { i, action ->
            val keys = keymap.bindingsFor(action).joinToString("\n") { it.label() }.ifEmpty { getString(R.string.none) }
            table.addView(shortcutRow(actionLabel(action), keys, striped = i % 2 == 1) {
                dialog.dismiss()
                shortcutActions(action)
            })
        }
        dialog.show()
    }

    private fun shortcutRow(
        action: String,
        keys: String,
        header: Boolean = false,
        striped: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
        background = when {
            header -> ColorDrawable(Palette.PANEL_DARK)
            onClick != null -> rippleBackground()
            else -> null
        }
        if (striped) foreground = ColorDrawable(STRIPE_COLOR)
        addView(TextView(context).apply {
            text = action
            textSize = if (header) 12f else 14f
            setTextColor(if (header) Palette.MUTED else Palette.TEXT)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = keys
            textSize = if (header) 12f else 13f
            typeface = if (header) Typeface.DEFAULT else Typeface.MONOSPACE
            setTextColor(if (header || keys == getString(R.string.none)) Palette.MUTED else Palette.TEXT)
            gravity = Gravity.END
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun shortcutActions(action: ShortcutAction) {
        val defaults = action.defaults.joinToString(" / ") { it.label() }.ifEmpty { getString(R.string.none) }
        val choices = arrayOf(getString(R.string.add_key), getString(R.string.remove_keys), getString(R.string.reset_to_default, defaults))
        AlertDialog.Builder(this)
            .setTitle(actionLabel(action))
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
            .setTitle(actionLabel(action))
            .setMessage(getString(R.string.press_key))
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> shortcutSettings() }
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
                toast(getString(R.string.key_not_usable, binding.label()))
                return@setOnKeyListener true
            }
            val previous = keymap.actionFor(binding)
            updateKeymap(keymap.assign(action, binding))
            if (previous != null && previous != action) toast(getString(R.string.key_moved, binding.label(), actionLabel(previous)))
            dialog.dismiss()
            shortcutSettings()
            true
        }
        dialog.show()
    }

    // ---- Settings export / import --------------------------------------------------------

    /** Asks where to save the shortcuts and stars as a file (the result arrives in [onActivityResult]). */
    private fun exportSettings() {
        val name = "ws-settings-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".json"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(SettingsBackup.MIME_TYPE)
            .putExtra(Intent.EXTRA_TITLE, name)
        startDocumentPicker(intent, REQUEST_EXPORT_SETTINGS)
    }

    private fun importSettings() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            // Files from chat apps or Drive are often typed text/plain or octet-stream rather than JSON.
            .setType("*/*")
        startDocumentPicker(intent, REQUEST_IMPORT_SETTINGS)
    }

    private fun startDocumentPicker(intent: Intent, requestCode: Int) {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, requestCode)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.settings_file_failed))
        }
    }

    private fun writeSettings(uri: Uri) {
        val json = SettingsBackup(keymap, starStore.load()).encode()
        val written = runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } != null
        }.getOrDefault(false)
        toast(getString(if (written) R.string.settings_exported else R.string.settings_file_failed))
    }

    /** Reads an exported file and, after asking, replaces the current shortcuts and stars with it. */
    private fun readSettings(uri: Uri) {
        val backup = runCatching {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot open $uri")
            require(bytes.size <= MAX_SETTINGS_BYTES) { "file too large" }
            SettingsBackup.decode(String(bytes))
        }.getOrElse {
            toast(getString(R.string.settings_not_valid))
            return
        }
        confirm(resources.getQuantityString(R.plurals.import_settings_confirm, backup.stars.size, backup.stars.size)) {
            updateKeymap(backup.keymap)
            starStore.save(backup.stars)
            refreshLists()
            toast(getString(R.string.settings_imported))
        }
    }

    private fun updateKeymap(updated: Keymap) {
        keymap = updated
        keymapStore.save(updated)
        refreshShortcutHints()
    }

    // ---- Sessions -----------------------------------------------------------------------

    private fun snapshot() =
        Session(sessionName, tabs.map { it.state() }, activeIndex.coerceAtLeast(0), System.currentTimeMillis(), tabDisplay, groups)

    /** Auto-saves the open tabs so the work is there on next launch (and in the named session). */
    private fun persist() {
        if (!restoring) sessionStore.saveCurrent(snapshot())
    }

    private fun restoreSession(session: Session?) {
        restoring = true
        tabs.forEach { destroyWebView(it) }
        tabs.clear()
        sessionName = session?.name
        tabDisplay = session?.tabDisplay ?: TabDisplay.DEFAULT
        groups = session?.groups.orEmpty()
        session?.tabs?.forEach { tabs.add(it.toTab()) }
        activeIndex = session?.activeIndex ?: 0
        reorderTabs(TabOrder.pinnedFirst(tabs) { it.pinned })
        applyPlacement(TabGroups.normalize(tabs.map { it.slot() }, groups))
        val index = activeIndex
        activeIndex = -1
        restoring = false
        activate(index)
        applySidebar()
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
                sessionStore.saveNamed(current.copy(name = getString(R.string.autosave_name, formatTime(current.savedAt))))
        }
        restoreSession(target?.name?.let { sessionStore.findNamed(it) } ?: target)
    }

    private fun saveSessionDialog() {
        val input = dialogInput(getString(R.string.session_name), sessionName ?: getString(R.string.default_session_name, formatTime(System.currentTimeMillis())))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.save_session))
            .setMessage(getString(R.string.save_session_message))
            .setView(form(input))
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { return@setPositiveButton }
                sessionName = name
                sessionStore.saveNamed(snapshot())
                persist()
                refreshLists()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun sessionActions(session: Session) {
        val name = session.name ?: return
        val actions = arrayOf(getString(R.string.open), getString(R.string.add_tabs_to_current), getString(R.string.rename), getString(R.string.delete))
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> switchSession(session)
                    1 -> {
                        session.tabs.forEach { insertTab(tabs.size, it.toTab()) }
                        renderTabs()
                        persist()
                    }
                    2 -> renameSessionDialog(name)
                    3 -> confirm(getString(R.string.delete_confirm, name)) {
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
        val input = dialogInput(getString(R.string.session_name), oldName)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename))
            .setView(form(input))
            .setPositiveButton(getString(R.string.rename_button)) { _, _ ->
                val newName = input.text.toString().trim()
                val stored = sessionStore.findNamed(oldName)
                if (newName.isEmpty() || newName == oldName || stored == null) return@setPositiveButton
                sessionStore.deleteNamed(oldName)
                sessionStore.saveNamed(stored.copy(name = newName))
                if (sessionName == oldName) sessionName = newName
                persist()
                refreshLists()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ---- Stars --------------------------------------------------------------------------

    private fun starActions(star: Star) {
        val actions = arrayOf(getString(R.string.edit), getString(R.string.move_up), getString(R.string.move_down), getString(R.string.open_in_external_browser), getString(R.string.delete))
        AlertDialog.Builder(this)
            .setTitle(star.name)
            .setItems(actions) { _, which ->
                val list = starStore.load().toMutableList()
                val i = list.indexOfFirst { it.id == star.id }
                if (i < 0) return@setItems
                when (which) {
                    0 -> editStarDialog(star)
                    1 -> if (i > 0) Collections.swap(list, i, i - 1)
                    2 -> if (i < list.lastIndex) Collections.swap(list, i, i + 1)
                    3 -> openExternal(star.url)
                    4 -> list.removeAt(i)
                }
                if (which in 1..4) {
                    starStore.save(list)
                    refreshLists()
                }
            }
            .show()
    }

    private fun editStarDialog(star: Star?, name: String = "", url: String = "") {
        val nameInput = dialogInput(getString(R.string.name), star?.name ?: name)
        val urlInput = dialogInput("URL", star?.url ?: url).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val external = CheckBox(this).apply {
            text = getString(R.string.external_checkbox)
            isChecked = star?.external ?: false
        }
        AlertDialog.Builder(this)
            .setTitle(if (star == null) getString(R.string.add_star) else getString(R.string.edit_star))
            .setView(form(nameInput, urlInput, external))
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val resolved = UrlInput.resolve(urlInput.text.toString()) ?: return@setPositiveButton
                val updated = Star(
                    id = star?.id ?: Star.newId(),
                    name = nameInput.text.toString().trim().ifEmpty { Uri.parse(resolved).host ?: resolved },
                    url = resolved,
                    external = external.isChecked,
                )
                val list = starStore.load()
                starStore.save(if (star == null) list + updated else list.map { if (it.id == star.id) updated else it })
                refreshLists()
            }
            .setNegativeButton(getString(R.string.cancel), null)
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
            .setTitle(getString(R.string.termux_missing_title))
            .setMessage(getString(R.string.termux_missing_message))
            .setPositiveButton(getString(R.string.open_fdroid)) { _, _ -> openExternal(TERMUX_FDROID_URL) }
            .setNegativeButton(getString(R.string.close), null)
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
                toast(getString(R.string.no_app, url))
            }
        }
    }

    private fun download(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        val uri = Uri.parse(url)
        if (!URLUtil.isNetworkUrl(url)) {
            toast(getString(R.string.download_unsupported))
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
        toast(getString(R.string.downloading, fileName))
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
        menu.add(withShortcut(getString(R.string.new_tab), ShortcutAction.NEW_TAB)).setOnMenuItemClickListener { newTab(); true }
        menu.add(withShortcut(getString(R.string.action_reopen_tab), ShortcutAction.REOPEN_TAB)).setEnabled(closedTabs.isNotEmpty())
            .setOnMenuItemClickListener { reopenClosedTab(); true }
        menu.add(withShortcut(getString(R.string.search_tabs), ShortcutAction.SEARCH_TABS))
            .setOnMenuItemClickListener { searchTabsDialog(); true }
        val pinned = activeTab?.pinned == true
        menu.add(withShortcut(getString(if (pinned) R.string.unpin_tab else R.string.pin_tab), ShortcutAction.TOGGLE_PIN))
            .setEnabled(activeTab != null).setOnMenuItemClickListener { activeTab?.let { togglePin(it) }; true }
        addTabDisplayMenu(menu)
        menu.add(getString(R.string.open_in_external_browser)).setEnabled(onPage).setOnMenuItemClickListener {
            activeTab?.let { openExternal(it.url) }
            true
        }
        menu.add(getString(R.string.copy_link)).setEnabled(onPage).setOnMenuItemClickListener {
            activeTab?.let { copyToClipboard(it.url) }
            true
        }
        val zoom = activeTab?.textZoom ?: TextZoom.DEFAULT
        menu.addSubMenu(getString(R.string.text_size_menu, zoom)).apply {
            add(withShortcut(getString(R.string.larger), ShortcutAction.ZOOM_IN)).setEnabled(zoom < TextZoom.LEVELS.last())
                .setOnMenuItemClickListener { setTextZoom { TextZoom.larger(it) }; true }
            add(withShortcut(getString(R.string.smaller), ShortcutAction.ZOOM_OUT)).setEnabled(zoom > TextZoom.LEVELS.first())
                .setOnMenuItemClickListener { setTextZoom { TextZoom.smaller(it) }; true }
            add(withShortcut(getString(R.string.reset_text_size, TextZoom.DEFAULT), ShortcutAction.ZOOM_RESET)).setEnabled(zoom != TextZoom.DEFAULT)
                .setOnMenuItemClickListener { setTextZoom { TextZoom.DEFAULT }; true }
        }
        addSessionMenu(menu)
        menu.add(withShortcut(getString(R.string.fullscreen), ShortcutAction.TOGGLE_FULLSCREEN)).setCheckable(true).setChecked(fullscreen)
            .setOnMenuItemClickListener { setFullscreen(!fullscreen); true }
        menu.add(getString(R.string.desktop_mode)).setCheckable(true).setChecked(desktopMode)
            .setOnMenuItemClickListener { setDesktopMode(!desktopMode); true }
        menu.add(withShortcut(getString(R.string.open_termux), ShortcutAction.OPEN_TERMUX)).setOnMenuItemClickListener { openTermux(); true }
        menu.add(withShortcut(getString(R.string.shortcut_settings), ShortcutAction.SHOW_SHORTCUTS))
            .setOnMenuItemClickListener { shortcutSettings(); true }
        menu.add(getString(R.string.export_settings)).setOnMenuItemClickListener { exportSettings(); true }
        menu.add(getString(R.string.import_settings)).setOnMenuItemClickListener { importSettings(); true }
        menu.add(getString(R.string.about)).setOnMenuItemClickListener { aboutDialog(); true }
        popup.show()
    }

    /** The ★ menu: open a star, star the current page, or edit the stars. */
    private fun showStarMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        menu.setGroupDividerEnabled(true)
        val stars = starStore.load()
        stars.forEach { star ->
            menu.add(GROUP_ITEMS, Menu.NONE, Menu.NONE, if (star.external) "${star.name} ↗" else star.name)
                .setOnMenuItemClickListener { openStar(star); true }
        }
        menu.add(GROUP_ACTIONS, Menu.NONE, Menu.NONE, getString(R.string.add_page_as_star)).setEnabled(activeTab?.isHome == false)
            .setOnMenuItemClickListener {
                activeTab?.let { editStarDialog(null, it.title, it.url) }
                true
            }
        menu.addSubMenu(GROUP_ACTIONS, Menu.NONE, Menu.NONE, getString(R.string.edit_stars)).apply {
            setGroupDividerEnabled(true)
            stars.forEach { star -> add(GROUP_ITEMS, Menu.NONE, Menu.NONE, star.name).setOnMenuItemClickListener { starActions(star); true } }
            add(GROUP_ACTIONS, Menu.NONE, Menu.NONE, getString(R.string.add_star)).setOnMenuItemClickListener { editStarDialog(null); true }
        }
        popup.show()
    }

    /** Sessions live in a submenu of ⋮: switch to one, save the tabs as one, or start or manage them. */
    private fun addSessionMenu(menu: Menu) {
        val named = sessionStore.listNamed()
        menu.addSubMenu(getString(R.string.section_sessions)).apply {
            setGroupDividerEnabled(true)
            if (sessionName == null) {
                add(GROUP_ITEMS, Menu.NONE, Menu.NONE, getString(R.string.unsaved_session, tabCount(tabs.size)))
                    .setCheckable(true).setChecked(true)
                    .setOnMenuItemClickListener { saveSessionDialog(); true }
            }
            named.forEach { session ->
                add(GROUP_ITEMS, Menu.NONE, Menu.NONE, "${session.name} · ${tabCount(session.tabs.size)}")
                    .setCheckable(true).setChecked(session.name == sessionName)
                    .setOnMenuItemClickListener { switchSession(session); true }
            }
            add(GROUP_ACTIONS, Menu.NONE, Menu.NONE, withShortcut(getString(R.string.save_session), ShortcutAction.SAVE_SESSION))
                .setOnMenuItemClickListener { saveSessionDialog(); true }
            add(GROUP_ACTIONS, Menu.NONE, Menu.NONE, getString(R.string.new_session)).setOnMenuItemClickListener { switchSession(null); true }
            add(GROUP_ACTIONS, Menu.NONE, Menu.NONE, getString(R.string.manage_sessions)).setEnabled(named.isNotEmpty())
                .setOnMenuItemClickListener { manageSessionsDialog(); true }
        }
    }

    private fun manageSessionsDialog() {
        val named = sessionStore.listNamed()
        val items = named.map { "${it.name}\n${tabCount(it.tabs.size)} · ${formatTime(it.savedAt)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.manage_sessions))
            .setItems(items) { _, which -> sessionActions(named[which]) }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    /** Shows which build of the app (and of the system WebView) is running, so it can be checked and reported. */
    private fun aboutDialog() {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        val webView = WebView.getCurrentWebViewPackage()?.versionName ?: "-"
        val text = getString(R.string.about_text, info.versionName, info.longVersionCode, packageName, webView, Build.VERSION.RELEASE)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(text)
            .setPositiveButton(getString(R.string.close), null)
            .setNeutralButton(getString(R.string.copy)) { _, _ -> copyToClipboard(text) }
            .show()
    }

    private fun confirm(message: String, onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> onOk() }
            .setNegativeButton(getString(R.string.cancel), null)
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
        toast(getString(R.string.copied))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun tabCount(count: Int): String = resources.getQuantityString(R.plurals.tab_count, count, count)

    private fun actionLabel(action: ShortcutAction): String = when (action) {
        ShortcutAction.TAB_1, ShortcutAction.TAB_2, ShortcutAction.TAB_3, ShortcutAction.TAB_4,
        ShortcutAction.TAB_5, ShortcutAction.TAB_6, ShortcutAction.TAB_7, ShortcutAction.TAB_8 ->
            getString(action.labelRes, action.ordinal - ShortcutAction.TAB_1.ordinal + 1)
        else -> getString(action.labelRes)
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(millis))

    private companion object {
        const val KEY_DESKTOP = "desktop_mode"
        const val KEY_SIDEBAR = "sidebar_visible"
        const val KEY_SIDEBAR_COMPACT = "sidebar_visible_compact"
        const val KEY_FULLSCREEN = "fullscreen"
        const val COMPACT_WIDTH_DP = 600
        const val KEY_SIDEBAR_WIDTH = "sidebar_width"
        const val SIDEBAR_HANDLE_DP = 12
        const val REQUEST_FILE = 1
        const val REQUEST_EXPORT_SETTINGS = 2
        const val REQUEST_IMPORT_SETTINGS = 3
        const val MAX_SETTINGS_BYTES = 1 shl 20
        const val MAX_CLOSED_TABS = 25
        const val LINK_CLICK_WINDOW_MS = 1500L
        const val STRIPE_COLOR = 0x0DFFFFFF
        const val TAB_ICON_DP = 16
        const val DRAGGED_TAB_ALPHA = 0.4f
        const val TAB_DRAG_EDGE_DP = 32
        const val TAB_DRAG_SCROLL_DP = 16
        const val PULL_TRIGGER_DP = 64
        const val PULL_INDICATOR_DP = 40
        const val PULL_MAX_PROGRESS = 1.5f
        const val PINNED_GAP_DP = 8
        const val TAB_SCROLL_MARGIN_DP = 8
        const val GROUP_INDENT_DP = 12
        const val GROUP_ITEMS = 1
        const val GROUP_ACTIONS = 2
    }
}
