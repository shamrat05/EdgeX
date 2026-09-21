package com.fan.edgex.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.fan.edgex.R
import com.fan.edgex.config.ClipEntry
import com.fan.edgex.config.ClipboardHistorySnapshot
import com.fan.edgex.config.HookClipboardHistoryStore
import de.robv.android.xposed.XposedBridge

/**
 * System-wide clipboard history overlay (framework Views, system_server).
 *
 * Visual/interaction model:
 *  - dark rounded bottom sheet over the dimmed app, drag handle dismisses
 *  - sticky search field, title header collapses while scrolling
 *  - Pinned section, user groups, then Today / Yesterday / Earlier
 *  - tap card = paste into the original target, long press = item menu
 *
 * State is persisted through [HookClipboardHistoryStore] and captured by
 * [ClipboardHook] from ClipboardService.
 */
object ClipboardOverlay {

    private const val TAG = "EdgeX"
    private const val AUTO_DISMISS_MS = 30_000L
    private const val UNDO_MS = 4_000L
    private const val PASTE_DELAY_MS = 250L
    private const val MAX_HISTORY = 50
    private const val MAX_PINNED = 50

    private val handler = Handler(Looper.getMainLooper())

    // ── Persisted state (guarded by the object monitor) ────────────────────────

    private val history = mutableListOf<ClipEntry>()
    private val groups = mutableListOf<String>()
    private var hintDismissed = false
    private var historyLoaded = false

    /** Text of a clip written by EdgeX itself; suppresses a duplicate history entry. */
    @Volatile
    private var skipNextClipText: String? = null

    // ── Live UI state (main thread only) ───────────────────────────────────────

    private var ui: OverlayUi? = null
    private var autoDismissRunnable: Runnable? = null
    private var undoRunnable: Runnable? = null
    private val sourceLabelCache = HashMap<Int, String?>()

    private class OverlayUi(
        val context: Context,
        val palette: ClipboardUiKit.Palette,
        val root: FrameLayout,
        val sheet: LinearLayout,
        val titleHeader: View,
        val search: EditText,
        val searchClear: View,
        val list: LinearLayout,
        val scroll: ScrollView,
        val countView: TextView,
        val clearAll: TextView,
        val hintBar: View?
    ) {
        val density: Float = context.resources.displayMetrics.density
        var sheetTop = 0
        var titleCollapsed = false
        var titleHeaderFullHeight = 0
        var keyboardVisible = false
        var rootHeight = 0
        var popup: View? = null
        var snackbar: View? = null

        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    }

    // ── Clipboard capture ──────────────────────────────────────────────────────

    @Synchronized
    fun onClipboardChanged(
        text: String?,
        timestamp: Long = System.currentTimeMillis(),
        sourceUid: Int = -1
    ) {
        if (text.isNullOrEmpty()) return
        if (skipNextClipText == text) {
            skipNextClipText = null
            return
        }
        ensureHistoryLoadedLocked()
        val existingIndex = history.indexOfFirst { it.text == text }
        if (existingIndex >= 0) {
            val existing = history.removeAt(existingIndex)
            history.add(
                0,
                existing.copy(
                    timestamp = timestamp,
                    sourceUid = if (sourceUid >= 0) sourceUid else existing.sourceUid
                )
            )
        } else {
            history.add(0, ClipEntry(text = text, timestamp = timestamp, sourceUid = sourceUid))
        }
        trimLocked()
        persistLocked()
        handler.post { refreshIfShowing() }
    }

    @Synchronized
    private fun historySnapshot(): List<ClipEntry> {
        ensureHistoryLoadedLocked()
        return ArrayList(history)
    }

    @Synchronized
    private fun groupsSnapshot(): List<String> {
        ensureHistoryLoadedLocked()
        return ArrayList(groups)
    }

    @Synchronized
    private fun isHintDismissed(): Boolean {
        ensureHistoryLoadedLocked()
        return hintDismissed
    }

    @Synchronized
    private fun setHintDismissed() {
        ensureHistoryLoadedLocked()
        hintDismissed = true
        persistLocked()
    }

    private fun ensureHistoryLoadedLocked() {
        if (historyLoaded) return
        val snapshot = HookClipboardHistoryStore.readForHook(MAX_HISTORY)
        history.clear()
        history.addAll(snapshot.entries)
        groups.clear()
        groups.addAll(snapshot.groups)
        hintDismissed = snapshot.hintDismissed
        historyLoaded = true
    }

    private fun persistLocked() {
        val snapshot = ClipboardHistorySnapshot(
            entries = history.toList(),
            groups = groups.toList(),
            hintDismissed = hintDismissed
        )
        if (!HookClipboardHistoryStore.writeForHook(snapshot)) {
            XposedBridge.log("$TAG: Clipboard history persist failed")
        }
    }

    /** Keeps pinned entries protected while the ordinary limit trims unpinned ones. */
    private fun trimLocked() {
        var unpinned = history.count { !it.pinned }
        var index = history.lastIndex
        while (unpinned > MAX_HISTORY && index >= 0) {
            if (!history[index].pinned) {
                history.removeAt(index)
                unpinned--
            }
            index--
        }
        var pinned = history.count { it.pinned }
        index = history.lastIndex
        while (pinned > MAX_PINNED && index >= 0) {
            if (history[index].pinned) {
                history.removeAt(index)
                pinned--
            }
            index--
        }
    }

    @Synchronized
    private fun togglePin(text: String) {
        ensureHistoryLoadedLocked()
        val index = history.indexOfFirst { it.text == text }
        if (index < 0) return
        val entry = history[index]
        history[index] = entry.copy(pinned = !entry.pinned)
        persistLocked()
    }

    /** Toggles the pin and lets the affected card glide into its new section. */
    private fun togglePinAndRefresh(current: OverlayUi, text: String) {
        togglePin(text)
        rebuildList(current)
        animateCardFor(current, text)
    }

    private fun animateCardFor(current: OverlayUi, text: String) {
        val list = current.list
        for (index in 0 until list.childCount) {
            val child = list.getChildAt(index)
            if (child.tag == text) {
                child.alpha = 0f
                child.translationY = current.dp(10).toFloat()
                child.animate().alpha(1f).translationY(0f).setDuration(200).start()
                return
            }
        }
    }

    @Synchronized
    private fun removeEntry(text: String): Pair<ClipEntry, Int>? {
        ensureHistoryLoadedLocked()
        val index = history.indexOfFirst { it.text == text }
        if (index < 0) return null
        val removed = history.removeAt(index)
        persistLocked()
        return removed to index
    }

    @Synchronized
    private fun restoreEntry(entry: ClipEntry, index: Int) {
        ensureHistoryLoadedLocked()
        if (history.any { it.text == entry.text }) return
        history.add(index.coerceIn(0, history.size), entry)
        trimLocked()
        persistLocked()
    }

    @Synchronized
    private fun clearUnpinned(): List<Pair<ClipEntry, Int>> {
        ensureHistoryLoadedLocked()
        val removed = ArrayList<Pair<ClipEntry, Int>>()
        var index = 0
        while (index < history.size) {
            if (!history[index].pinned) {
                removed += history.removeAt(index) to index
            } else {
                index++
            }
        }
        persistLocked()
        return removed
    }

    @Synchronized
    private fun clearAll() {
        ensureHistoryLoadedLocked()
        history.clear()
        persistLocked()
    }

    @Synchronized
    private fun createGroup(name: String): Boolean {
        ensureHistoryLoadedLocked()
        val trimmed = name.trim()
        if (trimmed.isEmpty() || groups.any { it.equals(trimmed, ignoreCase = true) }) return false
        groups.add(trimmed)
        persistLocked()
        return true
    }

    @Synchronized
    private fun renameGroup(oldName: String, newName: String): Boolean {
        ensureHistoryLoadedLocked()
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        val index = groups.indexOfFirst { it == oldName }
        if (index < 0) return false
        if (groups.any { it != oldName && it.equals(trimmed, ignoreCase = true) }) return false
        groups[index] = trimmed
        for (i in history.indices) {
            if (history[i].group == oldName) history[i] = history[i].copy(group = trimmed)
        }
        persistLocked()
        return true
    }

    @Synchronized
    private fun deleteGroup(name: String) {
        ensureHistoryLoadedLocked()
        groups.remove(name)
        for (i in history.indices) {
            if (history[i].group == name) history[i] = history[i].copy(group = null)
        }
        persistLocked()
    }

    @Synchronized
    private fun moveToGroup(text: String, group: String?) {
        ensureHistoryLoadedLocked()
        val index = history.indexOfFirst { it.text == text }
        if (index < 0) return
        history[index] = history[index].copy(group = group)
        persistLocked()
    }

    @Synchronized
    private fun updateEntryText(oldText: String, newText: String): Boolean {
        ensureHistoryLoadedLocked()
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return false
        val index0 = history.indexOfFirst { it.text == oldText }
        if (index0 < 0) return false
        val entry = history[index0]
        var index = index0
        var cursor = 0
        while (cursor < history.size) {
            if (cursor != index && history[cursor].text == trimmed) {
                history.removeAt(cursor)
                if (cursor < index) index--
            } else {
                cursor++
            }
        }
        history[index] = entry.copy(text = trimmed)
        persistLocked()
        return true
    }

    // ── Show / dismiss ─────────────────────────────────────────────────────────

    fun isShowing(): Boolean = ui != null

    fun show(context: Context) {
        handler.post {
            dismiss()
            try {
                addOverlay(context)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: ClipboardOverlay show failed: ${t.message}")
            }
        }
    }

    fun dismiss() {
        autoDismissRunnable?.let { handler.removeCallbacks(it) }
        autoDismissRunnable = null
        undoRunnable?.let { handler.removeCallbacks(it) }
        undoRunnable = null
        val current = ui ?: return
        ui = null
        try {
            val wm = current.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(current.root)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: ClipboardOverlay dismiss failed: ${t.message}")
        }
    }

    private fun resetAutoDismiss() {
        autoDismissRunnable?.let { handler.removeCallbacks(it) }
        autoDismissRunnable = Runnable { dismiss() }.also {
            handler.postDelayed(it, AUTO_DISMISS_MS)
        }
    }

    // ── Window / root ──────────────────────────────────────────────────────────

    private fun addOverlay(context: Context) {
        val palette = ClipboardUiKit.palette(context)
        val density = context.resources.displayMetrics.density
        val dp = { value: Int -> (value * density + 0.5f).toInt() }
        val screenH = context.resources.displayMetrics.heightPixels
        val bottomSafeArea = maxOf(dp(12), navigationBarHeight(context))
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val root = object : FrameLayout(context) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.action == MotionEvent.ACTION_DOWN) {
                    resetAutoDismiss()
                    val current = ui
                    if (current != null && current.popup != null) {
                        return super.dispatchTouchEvent(ev)
                    }
                    if (current != null && current.sheetTop > 0 && ev.y < current.sheetTop) {
                        dismiss()
                        return true
                    }
                }
                super.dispatchTouchEvent(ev)
                return true
            }

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    val current = ui
                    if (event.action == KeyEvent.ACTION_DOWN) return current != null
                    if (current != null) {
                        if (current.popup != null) {
                            closePopup(current)
                        } else if (current.keyboardVisible) {
                            hideKeyboard(current)
                        } else {
                            dismiss()
                        }
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val sheetHolder = SheetLayout(context) { ui?.keyboardVisible == true }
        val built = buildSheet(context, sheetHolder, palette, dp, bottomSafeArea)

        root.addView(
            sheetHolder,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        )

        val newUi = OverlayUi(
            context = context,
            palette = palette,
            root = root,
            sheet = sheetHolder,
            titleHeader = built.titleHeader,
            search = built.search,
            searchClear = built.searchClear,
            list = built.list,
            scroll = built.scroll,
            countView = built.countView,
            clearAll = built.clearAll,
            hintBar = built.hintBar
        )
        ui = newUi

        @Suppress("DEPRECATION")
        wm.addView(root, WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            flags = WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            dimAmount = if (palette.dark) 0.5f else 0.35f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        })

        sheetHolder.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
            newUi.sheetTop = top
        }
        root.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, _ ->
            if (newUi.rootHeight == 0 && bottom > 0) {
                newUi.rootHeight = bottom
            }
            val shrunk = newUi.rootHeight - bottom
            newUi.keyboardVisible = shrunk > dp(100)
        }

        // Entrance animation.
        sheetHolder.translationY = dp(56).toFloat()
        sheetHolder.alpha = 0f
        sheetHolder.animate().translationY(0f).alpha(1f).setDuration(240).start()

        rebuildList(newUi)
        resetAutoDismiss()
    }

    private class SheetLayout(
        context: Context,
        private val keyboardVisible: () -> Boolean
    ) : LinearLayout(context) {

        init {
            orientation = VERTICAL
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val screen = resources.displayMetrics.heightPixels
            val fraction = if (keyboardVisible()) 0.95f else 0.82f
            val available = MeasureSpec.getSize(heightMeasureSpec)
            val cap = minOf((screen * fraction).toInt(), available)
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
            )
        }
    }

    private class SheetParts(
        val titleHeader: View,
        val search: EditText,
        val searchClear: View,
        val list: LinearLayout,
        val scroll: ScrollView,
        val countView: TextView,
        val clearAll: TextView,
        val hintBar: View?
    )

    private fun buildSheet(
        context: Context,
        sheet: LinearLayout,
        palette: ClipboardUiKit.Palette,
        dp: (Int) -> Int,
        bottomSafeArea: Int
    ): SheetParts {
        sheet.apply {
            background = ClipboardUiKit.roundedTop(palette.sheet, dp(28).toFloat())
            elevation = dp(24).toFloat()
            isClickable = true
            setPaddingRelative(0, 0, 0, 0)
        }

        // ── Drag handle ────────────────────────────────────────────────────────
        val handleWrap = FrameLayout(context)
        val handle = View(context).apply {
            background = ClipboardUiKit.rounded(palette.border, dp(2).toFloat())
        }
        handleWrap.addView(
            handle,
            FrameLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER }
        )
        sheet.addView(
            handleWrap,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26)
            ).apply { topMargin = dp(6) }
        )

        // ── Title header (collapses when the list scrolls) ─────────────────────
        val titleHeader = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(18), dp(2), dp(10), dp(10))
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconTile = FrameLayout(context).apply {
            background = ClipboardUiKit.rounded(palette.raised, dp(14).toFloat())
        }
        val clipboardIcon = ImageView(context).apply {
            setImageDrawable(ModuleRes.getDrawable(R.drawable.ic_clipboard, palette.accent))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        iconTile.addView(
            clipboardIcon,
            FrameLayout.LayoutParams(dp(24), dp(24)).apply { gravity = Gravity.CENTER }
        )
        titleRow.addView(iconTile, LinearLayout.LayoutParams(dp(46), dp(46)))

        val titleColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleView = TextView(context).apply {
            text = getString(R.string.clipboard_overlay_title)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textPrimary)
            letterSpacing = -0.02f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val countView = TextView(context).apply {
            textSize = 14f
            setTextColor(palette.textSecondary)
        }
        titleColumn.addView(titleView)
        titleColumn.addView(countView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(1) })
        titleRow.addView(
            titleColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        )

        val clearAll = TextView(context).apply {
            text = getString(R.string.clipboard_clear_all)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
            setPaddingRelative(dp(12), 0, dp(12), 0)
            background = ClipboardUiKit.ripple(rippleColor(palette))
            setOnClickListener { onClearAllClicked() }
        }
        titleRow.addView(
            clearAll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)
            )
        )

        val overflow = actionIcon(
            context, R.drawable.ic_more_vert, palette, palette.iconMuted, dp(44), dp(44),
            getString(R.string.clipboard_more_actions)
        ) { }
        overflow.setOnClickListener { currentUi()?.let { showHeaderMenu(it, overflow) } }
        titleRow.addView(overflow)

        titleHeader.addView(
            titleRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        sheet.addView(
            titleHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // ── Search (sticky, outside the scroll view) ───────────────────────────
        val searchWrap = FrameLayout(context).apply {
            background = ClipboardUiKit.rounded(
                palette.card, dp(16).toFloat(), dp(1), palette.border
            )
        }
        val search = EditText(context).apply {
            hint = getString(R.string.clipboard_search_hint)
            setHintTextColor(palette.textSecondary)
            setTextColor(palette.textPrimary)
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_FULLSCREEN
            background = null
            setPaddingRelative(dp(14), 0, dp(40), 0)
            val searchIcon = ModuleRes.getDrawable(R.drawable.ic_search, palette.iconMuted)
            searchIcon?.setBounds(0, 0, dp(18), dp(18))
            setCompoundDrawablesRelative(searchIcon, null, null, null)
            compoundDrawablePadding = dp(10)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                textCursorDrawable = ClipboardUiKit.rounded(
                    palette.accent, dp(1).toFloat()
                )
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    currentUi()?.let { hideKeyboard(it) }
                    true
                } else {
                    false
                }
            }
        }
        val searchClear = actionIcon(
            context, R.drawable.ic_close, palette, palette.textSecondary, dp(32), dp(32),
            getString(R.string.clipboard_clear_search)
        ) { search.text.clear() }
        searchClear.visibility = View.GONE
        searchWrap.addView(
            search,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(46)
            )
        )
        searchWrap.addView(
            searchClear,
            FrameLayout.LayoutParams(dp(32), dp(32)).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = dp(8)
            }
        )
        sheet.addView(
            searchWrap,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
            ).apply {
                marginStart = dp(18)
                marginEnd = dp(18)
                topMargin = dp(2)
                bottomMargin = dp(10)
            }
        )

        // ── Scrollable list ────────────────────────────────────────────────────
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(16), dp(4), dp(16), dp(8))
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                list,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        sheet.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // ── First-run hint ─────────────────────────────────────────────────────
        val hintBar: View?
        if (!isHintDismissed()) {
            hintBar = buildHintBar(context, palette, dp) { bar ->
                setHintDismissed()
                (bar.parent as? ViewGroup)?.removeView(bar)
            }
            sheet.addView(
                hintBar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(16)
                    marginEnd = dp(16)
                    topMargin = dp(4)
                }
            )
        } else {
            hintBar = null
        }

        sheet.addView(
            View(context),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bottomSafeArea)
        )

        // ── Wiring ─────────────────────────────────────────────────────────────
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                currentUi()?.let { rebuildList(it) }
                resetAutoDismiss()
            }
        })

        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val current = currentUi() ?: return@setOnScrollChangeListener
            val shouldCollapse = scrollY > current.dp(18)
            if (shouldCollapse != current.titleCollapsed) {
                animateTitleHeader(current, shouldCollapse)
            }
        }

        return SheetParts(
            titleHeader = titleHeader,
            search = search,
            searchClear = searchClear,
            list = list,
            scroll = scroll,
            countView = countView,
            clearAll = clearAll,
            hintBar = hintBar
        )
    }

    private fun buildHintBar(
        context: Context,
        palette: ClipboardUiKit.Palette,
        dp: (Int) -> Int,
        onDismiss: (View) -> Unit
    ): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ClipboardUiKit.rounded(palette.raised, dp(14).toFloat())
            setPaddingRelative(dp(12), dp(10), dp(4), dp(10))
        }
        val info = ImageView(context).apply {
            setImageDrawable(ModuleRes.getDrawable(R.drawable.ic_info, palette.accent))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        bar.addView(info, LinearLayout.LayoutParams(dp(18), dp(18)))
        bar.addView(
            TextView(context).apply {
                text = getString(R.string.clipboard_hint)
                textSize = 12.5f
                setTextColor(palette.textSecondary)
                maxLines = 2
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10) }
        )
        val close = actionIcon(
            context, R.drawable.ic_close, palette, palette.textSecondary, dp(32), dp(32),
            getString(R.string.clipboard_dismiss_hint)
        ) { }
        close.setOnClickListener { onDismiss(bar) }
        bar.addView(close)
        return bar
    }

    private fun animateTitleHeader(current: OverlayUi, collapse: Boolean) {
        val header = current.titleHeader
        if (collapse) {
            if (current.titleHeaderFullHeight <= 0) {
                current.titleHeaderFullHeight = header.height
            }
            val full = current.titleHeaderFullHeight
            if (full <= 0) {
                header.visibility = View.GONE
                current.titleCollapsed = true
                return
            }
            ValueAnimator.ofInt(full, 0).apply {
                duration = 180
                addUpdateListener { animator ->
                    val height = animator.animatedValue as Int
                    header.layoutParams = header.layoutParams.apply { this.height = height }
                    header.alpha = height.toFloat() / full
                    header.requestLayout()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        header.visibility = View.GONE
                        header.alpha = 1f
                    }
                })
                start()
            }.also { current.titleCollapsed = true }
        } else {
            val full = current.titleHeaderFullHeight
            header.visibility = View.VISIBLE
            if (full <= 0) {
                header.layoutParams = header.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                header.alpha = 1f
                header.requestLayout()
                current.titleCollapsed = false
                return
            }
            ValueAnimator.ofInt(0, full).apply {
                duration = 180
                addUpdateListener { animator ->
                    val height = animator.animatedValue as Int
                    header.layoutParams = header.layoutParams.apply { this.height = height }
                    header.alpha = height.toFloat() / full
                    header.requestLayout()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        header.layoutParams = header.layoutParams.apply {
                            height = ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                        header.alpha = 1f
                        header.requestLayout()
                    }
                })
                start()
            }.also { current.titleCollapsed = false }
        }
    }

    // ── List rendering ─────────────────────────────────────────────────────────

    private fun currentUi(): OverlayUi? = ui

    private fun refreshIfShowing() {
        val current = ui ?: return
        rebuildList(current, animateTop = true)
    }

    private fun rebuildList(
        current: OverlayUi,
        preserveScroll: Boolean = true,
        animateTop: Boolean = false
    ) {
        val previousScroll = if (preserveScroll) current.scroll.scrollY else 0
        val entries = historySnapshot()
        val groupNames = groupsSnapshot()
        val queryText = current.search.text?.toString()?.trim()?.lowercase().orEmpty()

        current.list.removeAllViews()
        current.countView.text = getString(R.string.clipboard_count, entries.size)
        val hasUnpinned = entries.any { !it.pinned }
        current.clearAll.isEnabled = hasUnpinned
        current.clearAll.alpha = if (hasUnpinned) 1f else 0.4f

        if (entries.isEmpty()) {
            addEmptyState(current)
            return
        }

        var firstCard: View? = null
        fun addCards(items: List<ClipEntry>) {
            items.forEach { entry ->
                val card = buildCard(current, entry)
                if (firstCard == null) firstCard = card
                current.list.addView(card)
            }
        }

        if (queryText.isNotEmpty()) {
            val matches = entries.filter { it.text.lowercase().contains(queryText) }
            if (matches.isEmpty()) {
                addNoResultsState(current)
                return
            }
            val pinned = matches.filter { it.pinned }
            if (pinned.isNotEmpty()) {
                addSection(
                    current, "search:pinned", R.drawable.ic_pin_filled,
                    getString(R.string.clipboard_pinned), pinned.size
                ) { addCards(pinned) }
            }
            groupNames.forEach { name ->
                val items = matches.filter { !it.pinned && it.group == name }
                if (items.isNotEmpty()) {
                    addSection(
                        current, "search:group:$name", R.drawable.ic_folder, name, items.size
                    ) { addCards(items) }
                }
            }
            val rest = matches.filter { !it.pinned && (it.group == null || it.group !in groupNames) }
            if (rest.isNotEmpty()) {
                addSection(
                    current, "search:other", R.drawable.ic_clock,
                    getString(R.string.clipboard_history), rest.size
                ) { addCards(rest) }
            }
        } else {
            val pinned = entries.filter { it.pinned }
            if (pinned.isNotEmpty()) {
                addSection(
                    current, "pinned", R.drawable.ic_pin_filled,
                    getString(R.string.clipboard_pinned), pinned.size
                ) { addCards(pinned) }
            }
            groupNames.forEach { name ->
                val items = entries.filter { !it.pinned && it.group == name }
                addSection(
                    current, "group:$name", R.drawable.ic_folder, name, items.size,
                    groupName = name
                ) {
                    if (items.isEmpty()) {
                        current.list.addView(sectionHint(current, getString(R.string.clipboard_group_empty)))
                    } else {
                        addCards(items)
                    }
                }
            }
            val rest = entries.filter { !it.pinned && (it.group == null || it.group !in groupNames) }
            val today = rest.filter { ClipboardUiKit.daySection(it.timestamp) == ClipboardUiKit.DaySection.TODAY }
            val yesterday = rest.filter { ClipboardUiKit.daySection(it.timestamp) == ClipboardUiKit.DaySection.YESTERDAY }
            val earlier = rest.filter { ClipboardUiKit.daySection(it.timestamp) == ClipboardUiKit.DaySection.EARLIER }
            if (today.isNotEmpty()) {
                addSection(
                    current, "today", R.drawable.ic_clock,
                    getString(R.string.clipboard_today), today.size
                ) { addCards(today) }
            }
            if (yesterday.isNotEmpty()) {
                addSection(
                    current, "yesterday", R.drawable.ic_clock,
                    getString(R.string.clipboard_yesterday), yesterday.size
                ) { addCards(yesterday) }
            }
            if (earlier.isNotEmpty()) {
                addSection(
                    current, "earlier", R.drawable.ic_clock,
                    getString(R.string.clipboard_earlier), earlier.size
                ) { addCards(earlier) }
            }
            if (rest.isEmpty()) {
                current.list.addView(buildHistoryEmptyHint(current))
            }
        }

        if (preserveScroll && previousScroll > 0) {
            current.scroll.post { current.scroll.scrollTo(0, previousScroll) }
        }
        if (animateTop) {
            firstCard?.apply {
                alpha = 0f
                animate().alpha(1f).setDuration(180).start()
            }
        }
    }

    private fun addEmptyState(current: OverlayUi) {
        val container = LinearLayout(current.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPaddingRelative(0, current.dp(44), 0, current.dp(44))
        }
        val icon = ImageView(current.context).apply {
            setImageDrawable(ModuleRes.getDrawable(R.drawable.ic_clipboard, current.palette.iconMuted))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            alpha = 0.7f
        }
        container.addView(icon, LinearLayout.LayoutParams(current.dp(40), current.dp(40)))
        container.addView(
            TextView(current.context).apply {
                text = getString(R.string.clipboard_empty)
                textSize = 16f
                setTextColor(current.palette.textPrimary)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = current.dp(14) }
        )
        container.addView(
            TextView(current.context).apply {
                text = getString(R.string.clipboard_empty_hint)
                textSize = 13f
                setTextColor(current.palette.textSecondary)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = current.dp(6) }
        )
        current.list.addView(container)
    }

    private fun addNoResultsState(current: OverlayUi) {
        current.list.addView(
            TextView(current.context).apply {
                text = getString(R.string.clipboard_no_results)
                textSize = 14f
                setTextColor(current.palette.textSecondary)
                gravity = Gravity.CENTER
                setPaddingRelative(0, current.dp(36), 0, current.dp(36))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun sectionHint(current: OverlayUi, text: String): View =
        TextView(current.context).apply {
            this.text = text
            textSize = 13f
            setTextColor(current.palette.textSecondary)
            setPaddingRelative(current.dp(10), current.dp(2), current.dp(10), current.dp(10))
        }

    /** Shown when only pinned or grouped clips remain, e.g. after "Clear all". */
    private fun buildHistoryEmptyHint(current: OverlayUi): View =
        LinearLayout(current.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPaddingRelative(current.dp(16), current.dp(22), current.dp(16), current.dp(26))
            addView(
                TextView(current.context).apply {
                    text = getString(R.string.clipboard_history_empty)
                    textSize = 14f
                    setTextColor(current.palette.textPrimary)
                    gravity = Gravity.CENTER
                }
            )
            addView(
                TextView(current.context).apply {
                    text = getString(R.string.clipboard_empty_hint)
                    textSize = 12.5f
                    setTextColor(current.palette.textSecondary)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = current.dp(4) }
            )
        }

    private fun addSection(
        current: OverlayUi,
        key: String,
        @DrawableRes iconRes: Int,
        title: String,
        count: Int,
        groupName: String? = null,
        content: () -> Unit
    ) {
        val collapsedNow = key in collapsedSections
        current.list.addView(buildSectionHeader(current, key, iconRes, title, count, groupName, collapsedNow))
        if (!collapsedNow) content()
    }

    private val collapsedSections = mutableSetOf<String>()

    private fun buildSectionHeader(
        current: OverlayUi,
        key: String,
        @DrawableRes iconRes: Int,
        title: String,
        count: Int,
        groupName: String?,
        collapsedNow: Boolean
    ): View {
        val row = LinearLayout(current.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(current.dp(8), 0, current.dp(4), 0)
            background = ClipboardUiKit.ripple(rippleColor(current.palette))
            isClickable = true
            setOnClickListener {
                if (collapsedNow) collapsedSections.remove(key) else collapsedSections.add(key)
                rebuildList(current, preserveScroll = true)
            }
        }
        val icon = ImageView(current.context).apply {
            setImageDrawable(ModuleRes.getDrawable(iconRes, current.palette.accent))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(icon, LinearLayout.LayoutParams(current.dp(17), current.dp(17)))
        row.addView(
            TextView(current.context).apply {
                text = title
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(current.palette.textPrimary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = current.dp(8) }
        )
        row.addView(
            TextView(current.context).apply {
                text = count.toString()
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(current.palette.accent)
                gravity = Gravity.CENTER
                background = ClipboardUiKit.rounded(current.palette.accentAlpha(0.16f), current.dp(9).toFloat())
                setPaddingRelative(current.dp(7), current.dp(1), current.dp(7), current.dp(1))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = current.dp(8) }
        )
        if (groupName != null) {
            val more = actionIcon(
                current.context, R.drawable.ic_more_vert, current.palette, current.palette.iconMuted,
                current.dp(36), current.dp(36), getString(R.string.clipboard_group_actions)
            ) { }
            more.setOnClickListener { showGroupMenu(current, groupName, more) }
            row.addView(
                more,
                LinearLayout.LayoutParams(current.dp(36), current.dp(36)).apply {
                    marginStart = current.dp(4)
                }
            )
        }
        val chevron = ImageView(current.context).apply {
            setImageDrawable(ModuleRes.getDrawable(R.drawable.ic_expand_more, current.palette.iconMuted))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            rotation = if (collapsedNow) -90f else 0f
        }
        row.addView(chevron, LinearLayout.LayoutParams(current.dp(20), current.dp(20)))
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, current.dp(42)
        ).apply { topMargin = current.dp(4) }
        return row
    }

    private fun buildCard(current: OverlayUi, entry: ClipEntry): View {
        val kind = ClipboardUiKit.classify(entry.text)
        val card = LinearLayout(current.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ClipboardUiKit.cardRipple(
                current.palette.card, current.dp(14).toFloat(),
                current.dp(1), current.palette.border, rippleColor(current.palette)
            )
            setPaddingRelative(current.dp(10), current.dp(10), current.dp(4), current.dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { pasteEntry(entry.text) }
            setOnLongClickListener {
                showItemMenu(current, this, entry)
                true
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = current.dp(8) }
        }

        val tile = FrameLayout(current.context).apply {
            background = ClipboardUiKit.rounded(current.palette.raised, current.dp(12).toFloat())
        }
        val kindIcon = ImageView(current.context).apply {
            setImageDrawable(ModuleRes.getDrawable(kindIconRes(kind), current.palette.iconMuted))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        tile.addView(
            kindIcon,
            FrameLayout.LayoutParams(current.dp(20), current.dp(20)).apply { gravity = Gravity.CENTER }
        )
        card.addView(tile, LinearLayout.LayoutParams(current.dp(40), current.dp(40)))

        val column = LinearLayout(current.context).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(
            TextView(current.context).apply {
                text = entry.text
                textSize = 14.5f
                setTextColor(current.palette.textPrimary)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(current.dp(2).toFloat(), 1f)
                if (kind == ClipboardUiKit.ClipKind.CODE) {
                    typeface = Typeface.MONOSPACE
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val meta = metaLabel(current, entry)
        if (meta.isNotEmpty()) {
            column.addView(
                TextView(current.context).apply {
                    text = meta
                    textSize = 11.5f
                    setTextColor(current.palette.textSecondary)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = current.dp(3) }
            )
        }
        card.addView(
            column,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = current.dp(10) }
        )

        val actions = LinearLayout(current.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pin = actionIcon(
            current.context,
            if (entry.pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin,
            current.palette,
            if (entry.pinned) current.palette.accent else current.palette.iconMuted,
            current.dp(42), current.dp(42),
            getString(if (entry.pinned) R.string.clipboard_unpin else R.string.clipboard_pin)
        ) {
            togglePinAndRefresh(current, entry.text)
        }
        actions.addView(pin)
        val copy = actionIcon(
            current.context, R.drawable.ic_content_copy, current.palette, current.palette.iconMuted,
            current.dp(42), current.dp(42), getString(R.string.clipboard_copy)
        ) {
            copyEntry(current, entry.text)
        }
        actions.addView(copy)
        val more = actionIcon(
            current.context, R.drawable.ic_more_vert, current.palette, current.palette.iconMuted,
            current.dp(42), current.dp(42), getString(R.string.clipboard_item_actions)
        ) { }
        more.setOnClickListener { showItemMenu(current, more, entry) }
        actions.addView(more)
        card.addView(actions)
        card.tag = entry.text
        return card
    }

    private fun kindIconRes(kind: ClipboardUiKit.ClipKind): Int = when (kind) {
        ClipboardUiKit.ClipKind.CODE -> R.drawable.ic_code
        ClipboardUiKit.ClipKind.LINK -> R.drawable.ic_link
        ClipboardUiKit.ClipKind.EMAIL -> R.drawable.ic_mail
        ClipboardUiKit.ClipKind.NOTE -> R.drawable.ic_note
    }

    private fun metaLabel(current: OverlayUi, entry: ClipEntry): String {
        val parts = ArrayList<String>(2)
        ClipboardUiKit.timeLabel(current.context, entry.timestamp)
            .takeIf { it.isNotEmpty() }?.let(parts::add)
        sourceLabel(current.context, entry.sourceUid)?.let(parts::add)
        return parts.joinToString(" · ")
    }

    private fun sourceLabel(context: Context, uid: Int): String? {
        if (uid < 10_000) return null
        if (sourceLabelCache.containsKey(uid)) return sourceLabelCache[uid]
        val label = runCatching {
            val pm = context.packageManager
            val packageName = pm.getPackagesForUid(uid)?.firstOrNull() ?: return@runCatching null
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        }.getOrNull()
        sourceLabelCache[uid] = label
        return label
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    private fun onClearAllClicked() {
        val current = ui ?: return
        val removed = clearUnpinned()
        rebuildList(current)
        if (removed.isEmpty()) return
        showUndoSnackbar(current, getString(R.string.clipboard_cleared)) {
            for ((entry, index) in removed.asReversed()) {
                restoreEntry(entry, index)
            }
            rebuildList(current)
        }
    }

    private fun pasteEntry(text: String) {
        val current = ui ?: return
        val context = current.context
        hideKeyboard(current)
        dismiss()
        handler.postDelayed({ pasteText(context, text) }, PASTE_DELAY_MS)
    }

    private fun copyEntry(current: OverlayUi, text: String) {
        markSkipNextClip(text)
        try {
            val clipboard = current.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("EdgeX", text))
            Toast.makeText(current.context, getString(R.string.clipboard_copied), Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Clipboard copy failed: ${t.message}")
        }
    }

    private fun markSkipNextClip(text: String) {
        skipNextClipText = text
        handler.postDelayed({ if (skipNextClipText == text) skipNextClipText = null }, 1_500L)
    }

    private fun deleteEntry(current: OverlayUi, entry: ClipEntry) {
        val removed = removeEntry(entry.text) ?: return
        rebuildList(current)
        showUndoSnackbar(current, getString(R.string.clipboard_deleted)) {
            restoreEntry(removed.first, removed.second)
            rebuildList(current)
        }
    }

    private fun showUndoSnackbar(current: OverlayUi, message: String, onUndo: () -> Unit) {
        current.snackbar?.let { current.root.removeView(it) }
        undoRunnable?.let { handler.removeCallbacks(it) }

        val bar = LinearLayout(current.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ClipboardUiKit.rounded(current.palette.snackbar, current.dp(14).toFloat())
            setPaddingRelative(current.dp(16), 0, current.dp(8), 0)
            elevation = current.dp(12).toFloat()
        }
        bar.addView(
            TextView(current.context).apply {
                text = message
                textSize = 14f
                setTextColor(current.palette.textPrimary)
                maxLines = 1
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(
            TextView(current.context).apply {
                text = getString(R.string.clipboard_undo)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(current.palette.accent)
                gravity = Gravity.CENTER
                setPaddingRelative(current.dp(12), 0, current.dp(12), 0)
                background = ClipboardUiKit.ripple(rippleColor(current.palette))
                setOnClickListener {
                    undoRunnable?.let { handler.removeCallbacks(it) }
                    undoRunnable = null
                    current.root.removeView(bar)
                    current.snackbar = null
                    onUndo()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, current.dp(44)
            )
        )

        val sheetHeight = current.sheet.height.takeIf { it > 0 } ?: current.dp(320)
        current.root.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, current.dp(52)
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = sheetHeight + current.dp(12)
                marginStart = current.dp(16)
                marginEnd = current.dp(16)
            }
        )
        current.snackbar = bar
        bar.alpha = 0f
        bar.translationY = current.dp(12).toFloat()
        bar.animate().alpha(1f).translationY(0f).setDuration(160).start()

        val runnable = Runnable {
            undoRunnable = null
            current.snackbar?.let { view ->
                view.animate().alpha(0f).setDuration(160).withEndAction {
                    current.root.removeView(view)
                }.start()
            }
            current.snackbar = null
        }
        undoRunnable = runnable
        handler.postDelayed(runnable, UNDO_MS)
    }

    // ── Menus ──────────────────────────────────────────────────────────────────

    private class MenuEntry(
        val label: String,
        @DrawableRes val iconRes: Int? = null,
        val tint: Int? = null,
        val submenu: (() -> List<MenuEntry>)? = null,
        val dividerAfter: Boolean = false,
        val action: (() -> Unit)? = null
    )

    private fun showHeaderMenu(current: OverlayUi, anchor: View) {
        showMenu(
            current, anchor,
            listOf(
                MenuEntry(
                    label = getString(R.string.clipboard_new_group),
                    iconRes = R.drawable.ic_add
                ) { showGroupNameDialog(current, null) },
                MenuEntry(
                    label = getString(R.string.clipboard_manage_groups),
                    iconRes = R.drawable.ic_folder
                ) { showManageGroupsDialog(current) },
                MenuEntry(
                    label = getString(R.string.clipboard_clear_everything),
                    iconRes = R.drawable.ic_delete,
                    tint = current.palette.danger,
                    dividerAfter = false
                ) {
                    showConfirmDialog(
                        current,
                        getString(R.string.clipboard_clear_everything),
                        getString(R.string.clipboard_clear_everything_message),
                        getString(R.string.clipboard_clear_everything),
                        destructive = true
                    ) {
                        clearAll()
                        rebuildList(current)
                    }
                }
            )
        )
    }

    private fun showGroupMenu(current: OverlayUi, name: String, anchor: View) {
        showMenu(
            current, anchor,
            listOf(
                MenuEntry(
                    label = getString(R.string.clipboard_rename_group),
                    iconRes = R.drawable.ic_edit
                ) { showGroupNameDialog(current, name) },
                MenuEntry(
                    label = getString(R.string.clipboard_delete_group),
                    iconRes = R.drawable.ic_delete,
                    tint = current.palette.danger
                ) {
                    showConfirmDialog(
                        current,
                        getString(R.string.clipboard_delete_group),
                        getString(R.string.clipboard_delete_group_message, name),
                        getString(R.string.clipboard_delete),
                        destructive = true
                    ) {
                        deleteGroup(name)
                        rebuildList(current)
                    }
                }
            )
        )
    }

    private fun showItemMenu(current: OverlayUi, anchor: View, entry: ClipEntry) {
        val groupItems = ArrayList<MenuEntry>()
        if (entry.group != null) {
            groupItems += MenuEntry(
                label = getString(R.string.clipboard_remove_from_group),
                iconRes = R.drawable.ic_close
            ) {
                moveToGroup(entry.text, null)
                rebuildList(current)
            }
        }
        groupsSnapshot().forEach { name ->
            groupItems += MenuEntry(
                label = name,
                iconRes = R.drawable.ic_folder,
                tint = if (entry.group == name) current.palette.accent else null
            ) {
                moveToGroup(entry.text, name)
                rebuildList(current)
            }
        }
        groupItems += MenuEntry(
            label = getString(R.string.clipboard_new_group),
            iconRes = R.drawable.ic_add,
            dividerAfter = false
        ) { showGroupNameDialog(current, null, moveEntryText = entry.text) }

        showMenu(
            current, anchor,
            listOf(
                MenuEntry(
                    label = getString(R.string.clipboard_paste),
                    iconRes = R.drawable.ic_paste
                ) { pasteEntry(entry.text) },
                MenuEntry(
                    label = getString(R.string.clipboard_copy),
                    iconRes = R.drawable.ic_content_copy
                ) { copyEntry(current, entry.text) },
                MenuEntry(
                    label = getString(
                        if (entry.pinned) R.string.clipboard_unpin else R.string.clipboard_pin
                    ),
                    iconRes = if (entry.pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin,
                    tint = if (entry.pinned) current.palette.accent else null
                ) {
                    togglePinAndRefresh(current, entry.text)
                },
                MenuEntry(
                    label = getString(R.string.clipboard_move_to_group),
                    iconRes = R.drawable.ic_folder,
                    submenu = { groupItems }
                ),
                MenuEntry(
                    label = getString(R.string.clipboard_edit),
                    iconRes = R.drawable.ic_edit,
                    dividerAfter = true
                ) { showEditDialog(current, entry) },
                MenuEntry(
                    label = getString(R.string.clipboard_delete),
                    iconRes = R.drawable.ic_delete,
                    tint = current.palette.danger,
                    dividerAfter = false
                ) { deleteEntry(current, entry) }
            )
        )
    }

    private fun showMenu(current: OverlayUi, anchor: View, items: List<MenuEntry>) {
        closePopup(current)
        val context = current.context

        val wrapper = FrameLayout(context).apply {
            isClickable = true
            setOnClickListener { closePopup(current) }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ClipboardUiKit.rounded(current.palette.raised, current.dp(16).toFloat())
            setPaddingRelative(current.dp(6), current.dp(6), current.dp(6), current.dp(6))
            elevation = current.dp(16).toFloat()
        }
        for (entry in items) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(current.dp(10), 0, current.dp(10), 0)
                background = ClipboardUiKit.ripple(rippleColor(current.palette))
                isClickable = true
                setOnClickListener {
                    val submenu = entry.submenu
                    if (submenu != null) {
                        closePopup(current)
                        showMenu(current, anchor, submenu())
                    } else {
                        closePopup(current)
                        entry.action?.invoke()
                    }
                }
            }
            entry.iconRes?.let { res ->
                val icon = ImageView(context).apply {
                    setImageDrawable(
                        ModuleRes.getDrawable(res, entry.tint ?: current.palette.iconMuted)
                    )
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                row.addView(icon, LinearLayout.LayoutParams(current.dp(18), current.dp(18)))
            }
            row.addView(
                TextView(context).apply {
                    text = entry.label
                    textSize = 14.5f
                    setTextColor(entry.tint ?: current.palette.textPrimary)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = if (entry.iconRes != null) current.dp(12) else 0 }
            )
            if (entry.submenu != null) {
                val arrow = ImageView(context).apply {
                    setImageDrawable(ModuleRes.getDrawable(R.drawable.ic_chevron_right, current.palette.iconMuted))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                row.addView(arrow, LinearLayout.LayoutParams(current.dp(16), current.dp(16)))
            }
            card.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, current.dp(46)
                )
            )
            if (entry.dividerAfter) {
                card.addView(
                    View(context).apply { setBackgroundColor(current.palette.border) },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, current.dp(1)
                    ).apply {
                        marginStart = current.dp(10)
                        marginEnd = current.dp(10)
                        topMargin = current.dp(4)
                        bottomMargin = current.dp(4)
                    }
                )
            }
        }

        val menuWidth = current.dp(232)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuHeight = card.measuredHeight
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val rootWidth = current.root.width.takeIf { it > 0 }
            ?: current.context.resources.displayMetrics.widthPixels
        val rootHeight = current.root.height.takeIf { it > 0 }
            ?: current.context.resources.displayMetrics.heightPixels
        val x = (location[0] + anchor.width - menuWidth)
            .coerceIn(current.dp(8), (rootWidth - menuWidth - current.dp(8)).coerceAtLeast(current.dp(8)))
        val below = location[1] + anchor.height + current.dp(4)
        val y = if (below + menuHeight <= rootHeight - current.dp(8)) {
            below
        } else {
            (location[1] - menuHeight - current.dp(4)).coerceAtLeast(current.dp(8))
        }
        wrapper.addView(
            card,
            FrameLayout.LayoutParams(menuWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = x
                topMargin = y
            }
        )
        current.root.addView(
            wrapper,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        current.popup = wrapper
        card.alpha = 0f
        card.translationY = current.dp(6).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(130).start()
    }

    private fun closePopup(current: OverlayUi) {
        val popup = current.popup ?: return
        current.popup = null
        current.root.removeView(popup)
        hideKeyboard(current)
    }

    // ── Dialogs ────────────────────────────────────────────────────────────────

    private fun showDialogShell(current: OverlayUi, content: View, dismissible: Boolean = true) {
        closePopup(current)
        val context = current.context
        val wrapper = FrameLayout(context).apply {
            setBackgroundColor(current.palette.scrim)
            isClickable = true
            if (dismissible) setOnClickListener { closePopup(current) }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ClipboardUiKit.rounded(current.palette.raised, current.dp(20).toFloat())
            setPaddingRelative(current.dp(20), current.dp(20), current.dp(20), current.dp(16))
            elevation = current.dp(20).toFloat()
            isClickable = true
            setOnClickListener { /* consume */ }
        }
        card.addView(content)
        wrapper.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                marginStart = current.dp(22)
                marginEnd = current.dp(22)
            }
        )
        current.root.addView(
            wrapper,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        current.popup = wrapper
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
    }

    private fun dialogTitle(current: OverlayUi, text: String): TextView =
        TextView(current.context).apply {
            this.text = text
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(current.palette.textPrimary)
        }

    private fun dialogMessage(current: OverlayUi, text: String): TextView =
        TextView(current.context).apply {
            this.text = text
            textSize = 14f
            setTextColor(current.palette.textSecondary)
            setLineSpacing(current.dp(2).toFloat(), 1f)
        }

    private fun dialogButtons(
        current: OverlayUi,
        confirmLabel: String,
        confirmTint: Int,
        onConfirm: () -> Unit
    ): View {
        val row = LinearLayout(current.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(
            TextView(current.context).apply {
                text = getString(R.string.clipboard_cancel)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(current.palette.textSecondary)
                gravity = Gravity.CENTER
                setPaddingRelative(current.dp(16), 0, current.dp(16), 0)
                background = ClipboardUiKit.ripple(rippleColor(current.palette))
                setOnClickListener { closePopup(current) }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, current.dp(44)
            )
        )
        row.addView(
            TextView(current.context).apply {
                text = confirmLabel
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(confirmTint)
                gravity = Gravity.CENTER
                setPaddingRelative(current.dp(18), 0, current.dp(18), 0)
                background = ClipboardUiKit.rounded(
                    current.palette.accentAlpha(0.14f), current.dp(22).toFloat()
                )
                setOnClickListener {
                    closePopup(current)
                    onConfirm()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, current.dp(44)
            ).apply { marginStart = current.dp(8) }
        )
        return row
    }

    private fun showConfirmDialog(
        current: OverlayUi,
        title: String,
        message: String,
        confirmLabel: String,
        destructive: Boolean,
        onConfirm: () -> Unit
    ) {
        val content = LinearLayout(current.context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(dialogTitle(current, title))
        content.addView(
            dialogMessage(current, message),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = current.dp(10) }
        )
        content.addView(
            dialogButtons(
                current, confirmLabel,
                if (destructive) current.palette.danger else current.palette.accent,
                onConfirm
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = current.dp(18) }
        )
        showDialogShell(current, content)
    }

    private fun showGroupNameDialog(
        current: OverlayUi,
        existingName: String?,
        moveEntryText: String? = null
    ) {
        val content = LinearLayout(current.context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = if (existingName == null) {
            getString(R.string.clipboard_new_group)
        } else {
            getString(R.string.clipboard_rename_group)
        }
        content.addView(dialogTitle(current, title))
        val input = EditText(current.context).apply {
            hint = getString(R.string.clipboard_group_name_hint)
            setHintTextColor(current.palette.textSecondary)
            setTextColor(current.palette.textPrimary)
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            background = ClipboardUiKit.rounded(
                current.palette.card, current.dp(12).toFloat(),
                current.dp(1), current.palette.border
            )
            setPaddingRelative(current.dp(14), current.dp(10), current.dp(14), current.dp(10))
            setText(existingName.orEmpty())
            setSelection(text.length)
        }
        content.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = current.dp(14) }
        )
        content.addView(
            dialogButtons(current, getString(R.string.clipboard_save), current.palette.accent) {
                val name = input.text?.toString().orEmpty().trim()
                if (name.isNotEmpty()) {
                    val ok = if (existingName == null) createGroup(name) else renameGroup(existingName, name)
                    if (ok) {
                        if (moveEntryText != null) moveToGroup(moveEntryText, name)
                        rebuildList(current)
                    }
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = current.dp(18) }
        )
        showDialogShell(current, content)
        focusInput(current, input)
    }

    private fun showEditDialog(current: OverlayUi, entry: ClipEntry) {
        val content = LinearLayout(current.context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(dialogTitle(current, getString(R.string.clipboard_edit)))
        val input = EditText(current.context).apply {
            setTextColor(current.palette.textPrimary)
            textSize = 15f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = ClipboardUiKit.rounded(
                current.palette.card, current.dp(12).toFloat(),
                current.dp(1), current.palette.border
            )
            setPaddingRelative(current.dp(14), current.dp(12), current.dp(14), current.dp(12))
            minLines = 3
            maxLines = 8
            setText(entry.text)
            setSelection(text.length)
        }
        content.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = current.dp(14) }
        )
        content.addView(
            dialogButtons(current, getString(R.string.clipboard_save), current.palette.accent) {
                val newText = input.text?.toString().orEmpty()
                if (updateEntryText(entry.text, newText)) {
                    rebuildList(current)
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = current.dp(18) }
        )
        showDialogShell(current, content)
        focusInput(current, input)
    }

    private fun showManageGroupsDialog(current: OverlayUi) {
        val content = LinearLayout(current.context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(dialogTitle(current, getString(R.string.clipboard_manage_groups)))
        val list = LinearLayout(current.context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val names = groupsSnapshot()
        if (names.isEmpty()) {
            list.addView(
                dialogMessage(current, getString(R.string.clipboard_no_groups)).apply {
                    setPaddingRelative(0, current.dp(12), 0, current.dp(6))
                }
            )
        } else {
            names.forEach { name ->
                val row = LinearLayout(current.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPaddingRelative(0, current.dp(2), 0, current.dp(2))
                }
                val folder = ImageView(current.context).apply {
                    setImageDrawable(ModuleRes.getDrawable(R.drawable.ic_folder, current.palette.accent))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                row.addView(folder, LinearLayout.LayoutParams(current.dp(18), current.dp(18)))
                row.addView(
                    TextView(current.context).apply {
                        text = name
                        textSize = 14.5f
                        setTextColor(current.palette.textPrimary)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = current.dp(10) }
                )
                val rename = actionIcon(
                    current.context, R.drawable.ic_edit, current.palette, current.palette.iconMuted,
                    current.dp(38), current.dp(38), getString(R.string.clipboard_rename_group)
                ) { showGroupNameDialog(current, name) }
                row.addView(rename)
                val delete = actionIcon(
                    current.context, R.drawable.ic_delete, current.palette, current.palette.danger,
                    current.dp(38), current.dp(38), getString(R.string.clipboard_delete_group)
                ) {
                    showConfirmDialog(
                        current,
                        getString(R.string.clipboard_delete_group),
                        getString(R.string.clipboard_delete_group_message, name),
                        getString(R.string.clipboard_delete),
                        destructive = true
                    ) {
                        deleteGroup(name)
                        showManageGroupsDialog(current)
                        rebuildList(current)
                    }
                }
                row.addView(delete)
                list.addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, current.dp(46)
                    )
                )
            }
        }
        content.addView(
            list,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = current.dp(8) }
        )
        content.addView(
            LinearLayout(current.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(current.dp(2), 0, 0, 0)
                background = ClipboardUiKit.ripple(rippleColor(current.palette))
                isClickable = true
                setOnClickListener { showGroupNameDialog(current, null) }
                val add = ImageView(current.context).apply {
                    setImageDrawable(ModuleRes.getDrawable(R.drawable.ic_add, current.palette.accent))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                addView(add, LinearLayout.LayoutParams(current.dp(18), current.dp(18)))
                addView(
                    TextView(current.context).apply {
                        text = getString(R.string.clipboard_new_group)
                        textSize = 14.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(current.palette.accent)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = current.dp(10) }
                )
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, current.dp(46)
            ).apply { topMargin = current.dp(4) }
        )
        content.addView(
            dialogButtons(current, getString(R.string.clipboard_done), current.palette.accent) { },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = current.dp(10) }
        )
        showDialogShell(current, content)
    }

    private fun focusInput(current: OverlayUi, input: EditText) {
        input.requestFocus()
        input.postDelayed({
            val imm = current.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 150L)
    }

    private fun hideKeyboard(current: OverlayUi) {
        try {
            val imm = current.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(current.search.windowToken, 0)
        } catch (_: Throwable) {
        }
        current.search.clearFocus()
        current.root.requestFocus()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun actionIcon(
        context: Context,
        @DrawableRes res: Int,
        palette: ClipboardUiKit.Palette,
        tint: Int,
        sizePx: Int,
        heightPx: Int,
        contentDesc: String,
        onClick: () -> Unit
    ): ImageView = ImageView(context).apply {
        setImageDrawable(ModuleRes.getDrawable(res, tint))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = contentDesc
        isClickable = true
        isFocusable = true
        background = ClipboardUiKit.ripple(rippleColor(palette))
        setPaddingRelative(sizePx / 4, heightPx / 4, sizePx / 4, heightPx / 4)
        setOnClickListener { onClick() }
    }

    private fun rippleColor(palette: ClipboardUiKit.Palette): Int =
        if (palette.dark) 0x1FFFFFFF else 0x14000000

    private fun getString(@StringRes id: Int, vararg args: Any?): String =
        ModuleRes.getString(id, *args)

    private fun navigationBarHeight(context: Context): Int {
        return try {
            val res = context.resources
            val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) res.getDimensionPixelSize(id) else 0
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * Paste [text] without writing to ClipboardManager, avoiding the system
     * clipboard-change notification. Accessibility insertion handles Unicode
     * input methods better; key-event injection remains a fallback.
     */
    private fun pasteText(context: Context, text: String) {
        UniversalCopyManager.injectIntoFocusedField(context, text) { inserted ->
            if (!inserted) {
                handler.post { injectText(context, text) }
            }
        }
    }

    /**
     * Inject [text] directly into the focused input field via key events,
     * mirroring XPE's approach (y0.i0 / KeyCharacterMap.getEvents).
     */
    private fun injectText(context: Context, text: String) {
        if (text.isEmpty()) return
        try {
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) ?: return
            val injectMethod = inputManager.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )

            val charMap = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            val events = charMap.getEvents(text.toCharArray())

            if (events != null) {
                for (event in events) {
                    val timed = android.view.KeyEvent.changeTimeRepeat(
                        event, android.os.SystemClock.uptimeMillis(), 0
                    )
                    injectMethod.invoke(inputManager, timed, 0)
                }
            } else {
                @Suppress("DEPRECATION")
                val charEvent = android.view.KeyEvent(
                    android.os.SystemClock.uptimeMillis(),
                    text,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0
                )
                injectMethod.invoke(inputManager, charEvent, 0)
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: injectText failed: ${t.message}")
        }
    }
}
