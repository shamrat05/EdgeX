package com.fan.edgex.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
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
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
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
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fan.edgex.BuildConfig
import com.fan.edgex.IClipboardImageBridge
import com.fan.edgex.IShellCallback
import com.fan.edgex.IShellExecutor
import com.fan.edgex.R
import com.fan.edgex.config.ClipEntry
import com.fan.edgex.config.ClipType
import com.fan.edgex.config.ClipboardHistoryMerge
import com.fan.edgex.config.ClipboardHistorySnapshot
import com.fan.edgex.config.ScreenshotMediaFilter
import com.fan.edgex.config.ClipboardImageStore
import com.fan.edgex.config.HookClipboardHistoryStore
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * System-wide clipboard history overlay (framework Views, system_server).
 *
 * Single-window architecture:
 *
 *   overlayRoot (FrameLayout)
 *     ├── sheet                 (elevation 24dp)
 *     └── transientLayer        (elevation 48dp) — menus, dialogs, snackbar
 *
 * Menus/dialogs are ordinary children of [OverlayUi.transientLayer]; no
 * PopupWindow/PopupMenu/Dialog is used, which is what keeps their Z-order
 * above the sheet inside this injected window.
 *
 * The clip list is a RecyclerView backed by a flattened row model and a
 * DiffUtil adapter, so pin/search/collapse/group changes rebind only the
 * rows that actually changed instead of rebuilding the whole hierarchy.
 */
/**
 * Diff callback shared by the clipboard ListAdapter. Identity is the row's
 * deterministic stable key; content equality is structural so unchanged rows
 * are never rebound.
 */
private val CLIP_DIFF = object : DiffUtil.ItemCallback<DisplayRow>() {
    override fun areItemsTheSame(oldItem: DisplayRow, newItem: DisplayRow): Boolean =
        oldItem.stableKey == newItem.stableKey

    override fun areContentsTheSame(oldItem: DisplayRow, newItem: DisplayRow): Boolean =
        oldItem == newItem
}

object ClipboardOverlay {

    private const val TAG = "EdgeX"
    private const val AUTO_DISMISS_MS = 30_000L
    private const val UNDO_MS = 4_000L
    private const val PASTE_DELAY_MS = 250L
    private const val IMAGE_BRIDGE_TIMEOUT_MS = 5_000L
    private const val MAX_HISTORY = 50
    private const val MAX_PINNED = 50
    private const val MAX_CLIP_PREVIEW_CHARS = 512
    private const val SEARCH_DEBOUNCE_MS = 50L

    private const val TYPE_HEADER = 0
    private const val TYPE_CLIP = 1
    private const val TYPE_MESSAGE = 2

    private val handler = Handler(Looper.getMainLooper())

    // ── Persisted state (guarded by the object monitor) ────────────────────────

    private val history = mutableListOf<ClipEntry>()
    private val groups = mutableListOf<String>()
    private var hintDismissed = false
    private var historyLoaded = false

    /** Text of a clip written by EdgeX itself; suppresses a duplicate history entry. */
    @Volatile
    private var skipNextClipText: String? = null

    /** URI of an image EdgeX itself placed on the clipboard (duplicate guard). */
    @Volatile
    private var skipNextImageUri: String? = null

    // ── Live UI state (main thread only) ───────────────────────────────────────

    private var ui: OverlayUi? = null
    private var adapter: ClipAdapter? = null
    @Volatile
    private var lastContext: Context? = null
    private var autoDismissRunnable: Runnable? = null
    private var undoRunnable: Runnable? = null
    private var searchRefreshRunnable: Runnable? = null
    private val collapsedSections = mutableSetOf<String>()
    private val selectedClipIds = LinkedHashSet<String>()
    private val sourceAppCache = java.util.Collections.synchronizedMap(HashMap<Int, SourceApp?>())
    private val sourceIconCache = java.util.Collections.synchronizedMap(
        HashMap<String, Drawable.ConstantState?>()
    )
    private val sourceIconLoads = java.util.Collections.synchronizedSet(HashSet<String>())
    private val rowBuildGeneration = AtomicLong(0L)
    private val backupInProgress = AtomicBoolean(false)
    private val restoreInProgress = AtomicBoolean(false)

    // Root shell bridge (app's ShellExecutorService) used only by backup/restore.
    private var shellExecutor: IShellExecutor? = null
    private var shellBound = false
    private var shellContext: Context? = null
    private var activeShellCalls = 0
    private var shellBindTimeout: Runnable? = null
    private class PendingShellCall(
        val run: (IShellExecutor) -> Unit,
        val unavailable: () -> Unit
    )
    private val pendingShellCalls = ArrayDeque<PendingShellCall>()
    private var dismissAnimating = false

    private val shellConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            shellExecutor = IShellExecutor.Stub.asInterface(binder)
            shellBindTimeout?.let(handler::removeCallbacks)
            shellBindTimeout = null
            val executor = shellExecutor ?: return
            while (pendingShellCalls.isNotEmpty()) {
                val pending = pendingShellCalls.removeFirst()
                runCatching { pending.run(executor) }.onFailure { pending.unavailable() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            shellExecutor = null
            shellBound = false
            if (pendingShellCalls.isNotEmpty()) shellContext?.let(::ensureShellService)
        }
    }

    private class OverlayUi(
        val context: Context,
        val palette: ClipboardUiKit.Palette,
        val root: FrameLayout,
        val sheet: LinearLayout,
        val bottomSpacer: View,
        val bottomSafeArea: Int,
        val transientLayer: FrameLayout,
        val titleHeader: View,
        val search: EditText,
        val searchClear: View,
        val recycler: RecyclerView,
        val countView: TextView,
        val clearAll: TextView,
        val selectionBar: LinearLayout,
        val selectionCount: TextView,
        val selectionPaste: TextView,
        val selectionCancel: TextView,
        val hintBar: View?
    ) {
        val density: Float = context.resources.displayMetrics.density
        var sheetTop = 0
        var titleCollapsed = false
        var titleHeaderFullHeight = 0
        var keyboardVisible = false
        var imeInsetsKnown = false
        var rootHeight = 0
        var popup: View? = null
        var snackbar: View? = null
        var onBackPressed: (() -> Boolean)? = null
        var editorLayoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
        var lastWidth: Int = context.resources.displayMetrics.widthPixels
        var lastHeight: Int = context.resources.displayMetrics.heightPixels

        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    }

    // ── View holders / adapter ─────────────────────────────────────────────────

    private class HeaderHolder(
        val root: LinearLayout,
        val icon: ImageView,
        val title: TextView,
        val badge: TextView,
        val more: ImageView,
        val chevron: ImageView
    ) : RecyclerView.ViewHolder(root) {
        var bound: DisplayRow.Header? = null
    }

    private class ClipHolder(
        val root: LinearLayout,
        val iconColumn: LinearLayout,
        val icon: ImageView,
        val sourceIcon: ImageView,
        val text: TextView,
        val meta: TextView,
        val pin: ImageView,
        val copy: ImageView,
        val more: ImageView
    ) : RecyclerView.ViewHolder(root) {
        var bound: DisplayRow.Clip? = null
        var selectedState: Boolean? = null
    }

    private class MessageHolder(
        val root: LinearLayout,
        val icon: ImageView,
        val title: TextView,
        val secondary: TextView
    ) : RecyclerView.ViewHolder(root)

    /**
     * ListAdapter = AsyncListDiffer: diffs run on a background executor and the
     * adapter only rebinds rows that actually changed. Stable ids are a
     * deterministic 64-bit hash of [DisplayRow.stableKey].
     */
    private class ClipAdapter : ListAdapter<DisplayRow, RecyclerView.ViewHolder>(CLIP_DIFF) {

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = stableId(currentList[position])

        override fun getItemViewType(position: Int): Int = when (currentList[position]) {
            is DisplayRow.Header -> TYPE_HEADER
            is DisplayRow.Clip -> TYPE_CLIP
            is DisplayRow.Message -> TYPE_MESSAGE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val context = parent.context
            return when (viewType) {
                TYPE_HEADER -> createHeaderHolder(context)
                TYPE_CLIP -> createClipHolder(context)
                else -> createMessageHolder(context)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = currentList[position]
            val current = currentUi() ?: return
            when (holder) {
                is HeaderHolder -> if (row is DisplayRow.Header) bindHeader(holder, row, current)
                is ClipHolder -> if (row is DisplayRow.Clip) bindClip(holder, row, current)
                is MessageHolder -> if (row is DisplayRow.Message) bindMessage(holder, row, current)
            }
        }

        // ── Creation (static visuals + listeners happen exactly once) ──────────

        private fun createHeaderHolder(context: Context): HeaderHolder {
            val density = context.resources.displayMetrics.density
            val dp = { value: Int -> (value * density + 0.5f).toInt() }
            val palette = currentUi()?.palette

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(dp(8), 0, dp(4), 0)
                isClickable = true
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
                ).apply { topMargin = dp(4) }
            }
            val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
            root.addView(icon, LinearLayout.LayoutParams(dp(17), dp(17)))
            val title = TextView(context).apply {
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            root.addView(
                title,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(8) }
            )
            val badge = TextView(context).apply {
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            root.addView(
                badge,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
            )
            val more = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isClickable = true
                setPaddingRelative(dp(9), dp(9), dp(9), dp(9))
            }
            root.addView(
                more,
                LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(4) }
            )
            val chevron = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
            root.addView(chevron, LinearLayout.LayoutParams(dp(20), dp(20)))

            val holder = HeaderHolder(root, icon, title, badge, more, chevron)
            if (palette != null) {
                root.background = ClipboardUiKit.ripple(rippleColor(palette))
                title.setTextColor(palette.textPrimary)
                badge.setTextColor(palette.accent)
                badge.background = ClipboardUiKit.rounded(
                    palette.accentAlpha(0.16f), dp(9).toFloat()
                )
                badge.setPaddingRelative(dp(7), dp(1), dp(7), dp(1))
                more.background = ClipboardUiKit.ripple(rippleColor(palette))
                more.contentDescription = getString(R.string.clipboard_group_actions)
                more.setImageDrawable(ClipboardUiKit.icon(context, R.drawable.ic_more_vert, palette.iconMuted))
                chevron.setImageDrawable(
                    ClipboardUiKit.icon(context, R.drawable.ic_expand_more, palette.iconMuted)
                )
            }
            root.setOnClickListener {
                val row = holder.bound ?: return@setOnClickListener
                if (row.collapsed) collapsedSections.remove(row.key) else collapsedSections.add(row.key)
                currentUi()?.let { refreshRows(it, preserveScroll = true) }
            }
            more.setOnClickListener {
                val row = holder.bound ?: return@setOnClickListener
                val group = row.group ?: return@setOnClickListener
                currentUi()?.let { showGroupMenu(it, group, more) }
            }
            return holder
        }

        private fun createClipHolder(context: Context): ClipHolder {
            val density = context.resources.displayMetrics.density
            val dp = { value: Int -> (value * density + 0.5f).toInt() }
            val palette = currentUi()?.palette

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(dp(8), dp(6), dp(2), dp(6))
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(5) }
            }
            val iconColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val icon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            iconColumn.addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)))
            val sourceIcon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                visibility = View.GONE
                background = ClipboardUiKit.rounded(palette?.raised ?: Color.TRANSPARENT, dp(5).toFloat())
                clipToOutline = true
            }
            iconColumn.addView(
                sourceIcon,
                LinearLayout.LayoutParams(dp(14), dp(14)).apply { topMargin = dp(2) }
            )
            root.addView(
                iconColumn,
                LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val text = TextView(context).apply {
                textSize = 14.5f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(dp(2).toFloat(), 1f)
            }
            column.addView(text)
            val meta = TextView(context).apply {
                textSize = 11.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            column.addView(
                meta,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            )
            root.addView(
                column,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(8) }
            )

            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fun actionView(): ImageView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isClickable = true
                isFocusable = true
                setPaddingRelative(dp(7), dp(7), dp(7), dp(7))
            }
            val pin = actionView()
            actions.addView(pin, LinearLayout.LayoutParams(dp(30), dp(32)))
            val copy = actionView()
            actions.addView(
                copy,
                LinearLayout.LayoutParams(dp(30), dp(32)).apply { marginStart = dp(1) }
            )
            val more = actionView()
            actions.addView(
                more,
                LinearLayout.LayoutParams(dp(27), dp(32)).apply { marginStart = dp(1) }
            )
            root.addView(actions)

            val holder = ClipHolder(root, iconColumn, icon, sourceIcon, text, meta, pin, copy, more)
            if (palette != null) {
                root.background = ClipboardUiKit.cardRipple(
                    palette.card, dp(14).toFloat(), dp(1), palette.border, rippleColor(palette)
                )
                text.setTextColor(palette.textPrimary)
                meta.setTextColor(palette.textSecondary)
                copy.setImageDrawable(
                    ClipboardUiKit.icon(context, R.drawable.ic_content_copy, palette.iconMuted)
                )
                copy.background = ClipboardUiKit.ripple(rippleColor(palette))
                copy.contentDescription = getString(R.string.clipboard_copy)
                more.setImageDrawable(ClipboardUiKit.icon(context, R.drawable.ic_more_vert, palette.iconMuted))
                more.background = ClipboardUiKit.ripple(rippleColor(palette))
                more.contentDescription = getString(R.string.clipboard_item_actions)
                pin.background = ClipboardUiKit.ripple(rippleColor(palette))
            }
            root.setOnClickListener {
                val row = holder.bound ?: return@setOnClickListener
                val current = currentUi() ?: return@setOnClickListener
                if (selectedClipIds.isNotEmpty()) {
                    toggleClipSelection(current, row)
                } else if (row.isImage) {
                    pasteImageEntry(row.entry)
                } else {
                    pasteEntry(row.text, feedbackView = root)
                }
            }
            root.setOnLongClickListener {
                val row = holder.bound
                if (row != null) {
                    root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    currentUi()?.let { toggleClipSelection(it, row) }
                }
                true
            }
            pin.setOnClickListener {
                holder.bound?.let { row -> currentUi()?.let { togglePinAndRefresh(it, row.entry) } }
            }
            copy.setOnClickListener {
                holder.bound?.let { row ->
                    currentUi()?.let {
                        if (row.isImage) copyImageEntry(it, row.entry) else copyEntry(it, row.text)
                    }
                }
            }
            more.setOnClickListener {
                holder.bound?.let { row -> currentUi()?.let { showItemMenu(it, more, row.entry) } }
            }
            return holder
        }

        private fun createMessageHolder(context: Context): MessageHolder {
            val density = context.resources.displayMetrics.density
            val dp = { value: Int -> (value * density + 0.5f).toInt() }
            val palette = currentUi()?.palette
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPaddingRelative(dp(16), dp(34), dp(16), dp(34))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val icon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                alpha = 0.7f
            }
            root.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
            val title = TextView(context).apply {
                textSize = 15f
                gravity = Gravity.CENTER
            }
            root.addView(
                title,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
            )
            val secondary = TextView(context).apply {
                textSize = 12.5f
                gravity = Gravity.CENTER
            }
            root.addView(
                secondary,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            )
            if (palette != null) {
                title.setTextColor(palette.textPrimary)
                secondary.setTextColor(palette.textSecondary)
            }
            return MessageHolder(root, icon, title, secondary)
        }

        // ── Binding (cheap: no IO, no resources, no classification) ────────────

        private fun bindHeader(holder: HeaderHolder, row: DisplayRow.Header, current: OverlayUi) {
            holder.bound = row
            holder.icon.setImageDrawable(
                ClipboardUiKit.icon(current.context, rowIconRes(row.icon), current.palette.accent)
            )
            holder.title.text = row.title
            holder.badge.text = row.count.toString()
            holder.chevron.rotation = if (row.collapsed) -90f else 0f
            holder.more.visibility = if (row.group != null) View.VISIBLE else View.GONE
        }

        private fun bindClip(holder: ClipHolder, row: DisplayRow.Clip, current: OverlayUi) {
            holder.bound = row
            bindSourceIcon(holder, current, row.entry.sourceUid)
            val selected = row.entry.id in selectedClipIds
            if (holder.selectedState != selected) {
                holder.root.background = ClipboardUiKit.cardRipple(
                    if (selected) current.palette.accentAlpha(0.14f) else current.palette.card,
                    current.dp(14).toFloat(), current.dp(1),
                    if (selected) current.palette.accentAlpha(0.42f) else current.palette.border,
                    rippleColor(current.palette)
                )
                holder.text.setTextColor(
                    if (selected) current.palette.accent else current.palette.textPrimary
                )
                holder.selectedState = selected
            }
            if (row.isImage) {
                bindImage(holder, row, current)
                return
            }
            holder.iconColumn.layoutParams = holder.iconColumn.layoutParams.apply {
                width = current.dp(22)
            }
            holder.icon.layoutParams = holder.icon.layoutParams.apply {
                width = current.dp(18)
                height = current.dp(18)
            }
            holder.icon.background = null
            holder.icon.clipToOutline = false
            holder.icon.tag = null
            holder.icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            holder.icon.visibility = View.VISIBLE
            holder.icon.setImageDrawable(
                ClipboardUiKit.icon(current.context, kindIconRes(row.kind), current.palette.iconMuted)
            )
            holder.text.text = row.text.take(MAX_CLIP_PREVIEW_CHARS)
            holder.text.typeface = if (row.kind == ClipboardUiKit.ClipKind.CODE) {
                Typeface.MONOSPACE
            } else {
                Typeface.DEFAULT
            }
            if (row.meta.isEmpty()) {
                holder.meta.visibility = View.GONE
            } else {
                holder.meta.visibility = View.VISIBLE
                holder.meta.text = row.meta
            }
            holder.pin.setImageDrawable(
                ClipboardUiKit.icon(
                    current.context, R.drawable.ic_pin_filled,
                    if (row.pinned) current.palette.accent else current.palette.iconMuted
                )
            )
            holder.pin.contentDescription = getString(
                if (row.pinned) R.string.clipboard_unpin else R.string.clipboard_pin
            )
        }

        private fun bindSourceIcon(holder: ClipHolder, current: OverlayUi, uid: Int) {
            val image = holder.sourceIcon
            image.tag = null
            image.setImageDrawable(null)
            image.visibility = View.GONE
            val source = synchronized(sourceAppCache) { sourceAppCache[uid] } ?: return
            val packageName = source.packageName
            image.tag = packageName

            val cached = synchronized(sourceIconCache) {
                sourceIconCache.containsKey(packageName) to sourceIconCache[packageName]
            }
            if (cached.first) {
                applySourceIcon(holder, uid, packageName, cached.second)
                return
            }
            if (!sourceIconLoads.add(packageName)) return
            ioExecutor.execute {
                val state = runCatching {
                    current.context.packageManager.getApplicationIcon(packageName).constantState
                }.getOrNull()
                synchronized(sourceIconCache) {
                    if (!sourceIconCache.containsKey(packageName)) {
                        sourceIconCache[packageName] = state
                    }
                }
                sourceIconLoads.remove(packageName)
                handler.post {
                    val loaded = synchronized(sourceIconCache) { sourceIconCache[packageName] }
                    for (index in 0 until current.recycler.childCount) {
                        val visibleHolder = current.recycler.getChildViewHolder(
                            current.recycler.getChildAt(index)
                        ) as? ClipHolder ?: continue
                        val visibleUid = visibleHolder.bound?.entry?.sourceUid ?: continue
                        if (visibleHolder.sourceIcon.tag == packageName) {
                            applySourceIcon(visibleHolder, visibleUid, packageName, loaded)
                        }
                    }
                }
            }
        }

        private fun applySourceIcon(
            holder: ClipHolder,
            uid: Int,
            packageName: String,
            state: Drawable.ConstantState?
        ) {
            if (holder.sourceIcon.tag != packageName || holder.bound?.entry?.sourceUid != uid) return
            val drawable = runCatching { state?.newDrawable()?.mutate() }.getOrNull() ?: return
            holder.sourceIcon.setImageDrawable(drawable)
            holder.sourceIcon.visibility = View.VISIBLE
        }

        /**
         * Image rows reuse the same card: the icon slot becomes a bounded
         * thumbnail. The bitmap is only ever assigned from the cache here; a
         * miss schedules a background decode and the row is filled in later.
         * The drawable is always cleared first so a recycled row can never
         * flash the previous image.
         */
        private fun bindImage(holder: ClipHolder, row: DisplayRow.Clip, current: OverlayUi) {
            val path = row.entry.imagePath
            holder.text.text = ClipboardImageStore.displayLabel(current.context, row.entry)
            holder.text.typeface = Typeface.DEFAULT
            holder.iconColumn.layoutParams = holder.iconColumn.layoutParams.apply {
                width = current.dp(58)
            }
            holder.icon.layoutParams = holder.icon.layoutParams.apply {
                width = current.dp(52)
                height = current.dp(52)
            }
            holder.sourceIcon.apply {
                tag = null
                setImageDrawable(null)
                visibility = View.GONE
            }
            holder.icon.tag = path
            holder.icon.background = ClipboardUiKit.rounded(
                current.palette.raised, current.dp(9).toFloat()
            )
            holder.icon.clipToOutline = true
            if (row.meta.isEmpty()) {
                holder.meta.visibility = View.GONE
            } else {
                holder.meta.visibility = View.VISIBLE
                holder.meta.text = row.meta
            }
            holder.pin.setImageDrawable(
                ClipboardUiKit.icon(
                    current.context, R.drawable.ic_pin_filled,
                    if (row.pinned) current.palette.accent else current.palette.iconMuted
                )
            )
            holder.pin.contentDescription = getString(
                if (row.pinned) R.string.clipboard_unpin else R.string.clipboard_pin
            )

            holder.icon.setImageDrawable(null)
            holder.icon.scaleType = ImageView.ScaleType.CENTER_CROP
            val cached = ClipboardImageStore.cachedThumbnail(path)
            if (cached != null) {
                holder.icon.setImageBitmap(cached)
                return
            }
            holder.icon.setImageDrawable(
                ClipboardUiKit.icon(current.context, R.drawable.ic_image, current.palette.iconMuted)
            )
            val target = holder
            val tag = path ?: return
            val targetPx = current.dp(64)
            holder.icon.tag = tag
            ioExecutor.execute {
                val bitmap = ClipboardImageStore.loadThumbnail(
                    current.context, row.entry, targetPx
                )
                handler.post {
                    if (bitmap != null && target.icon.tag == tag && target.bound?.isImage == true) {
                        target.icon.setImageBitmap(bitmap)
                    } else if (bitmap == null && target.icon.tag == tag && target.bound?.isImage == true) {
                        if (!ClipboardImageStore.markBridgeThumbnailAttempt(tag)) return@post
                        requestImageThumbnail(current.context, row.entry, targetPx) { fallback ->
                            if (fallback != null && target.icon.tag == tag && target.bound?.isImage == true) {
                                target.icon.setImageBitmap(fallback)
                            } else if (fallback == null) {
                                ClipboardImageStore.reportThumbnailUnavailable(tag)
                            }
                        }
                    }
                }
            }
        }

        private fun requestImageThumbnail(
            context: Context,
            entry: ClipEntry,
            targetPx: Int,
            onComplete: (android.graphics.Bitmap?) -> Unit
        ) {
            val path = entry.imagePath ?: run {
                onComplete(null)
                return
            }
            ClipboardImageStore.cachedThumbnail(path)?.let {
                onComplete(it)
                return
            }
            val uri = ClipboardImageStore.shareUri(context, path)
            if (uri == null) {
                onComplete(null)
                return
            }
            val intent = Intent().apply {
                component = ComponentName(
                    BuildConfig.APPLICATION_ID,
                    "${BuildConfig.APPLICATION_ID}.config.ClipboardImageBridgeService"
                )
            }
            var finished = false
            var connection: ServiceConnection? = null
            fun finish(bitmap: android.graphics.Bitmap?) {
                if (finished) return
                finished = true
                connection?.let { runCatching { context.unbindService(it) } }
                ClipboardImageStore.cacheThumbnail(path, bitmap)
                onComplete(bitmap)
            }
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val bridge = IClipboardImageBridge.Stub.asInterface(binder)
                    if (bridge == null) {
                        finish(null)
                        return
                    }
                    ioExecutor.execute {
                        val bitmap = runCatching {
                            bridge.loadImageThumbnail(uri.toString(), targetPx)
                        }.getOrNull()
                        handler.post { finish(bitmap) }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) = finish(null)
            }
            val bound = runCatching {
                context.bindService(intent, connection!!, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) onComplete(null)
        }

        private fun bindMessage(holder: MessageHolder, row: DisplayRow.Message, current: OverlayUi) {
            if (row.icon == RowIcon.NONE) {
                holder.icon.visibility = View.GONE
            } else {
                holder.icon.visibility = View.VISIBLE
                holder.icon.setImageDrawable(
                    ClipboardUiKit.icon(current.context, rowIconRes(row.icon), current.palette.iconMuted)
                )
            }
            holder.title.text = row.title
            if (row.secondary.isNullOrEmpty()) {
                holder.secondary.visibility = View.GONE
            } else {
                holder.secondary.visibility = View.VISIBLE
                holder.secondary.text = row.secondary
            }
        }
    }

    private fun rowIconRes(icon: RowIcon): Int = when (icon) {
        RowIcon.PIN -> R.drawable.ic_pin_filled
        RowIcon.FOLDER -> R.drawable.ic_folder
        RowIcon.CLOCK -> R.drawable.ic_clock
        RowIcon.CLIPBOARD -> R.drawable.ic_clipboard
        RowIcon.NONE -> 0
    }

    private fun stableId(row: DisplayRow): Long {
        var hash = -0x340d631b7bdddcdbL
        val key = row.stableKey
        for (index in key.indices) {
            hash = hash xor key[index].code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
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

    /**
     * Receives an image clip detected by [ClipboardHook]. The provider stream is
     * read off the UI/binder threads into a bounded system staging file, then
     * copied into EdgeX-owned storage. Failed captures are logged without ever
     * including clipboard URIs or contents.
     */
    fun onClipboardImage(
        clip: ClipboardHook.ImageClip,
        timestamp: Long = System.currentTimeMillis(),
        sourceUid: Int = -1,
        onStored: ((Boolean) -> Unit)? = null
    ) {
        if (skipNextImageUri == clip.uri) {
            skipNextImageUri = null
            onStored?.invoke(true)
            return
        }
        captureImage(clip, timestamp, sourceUid, null, false, null, null, onStored)
    }

    /** Imports a MediaStore screenshot and retains its original provider location. */
    fun onScreenshotFile(
        clip: ClipboardHook.ImageClip,
        sourcePath: String?,
        originalRelativePath: String,
        originalDisplayName: String,
        timestamp: Long,
        sourceUid: Int = -1,
        onStored: ((Boolean) -> Unit)? = null
    ) {
        captureImage(
            clip, timestamp, sourceUid,
            sourcePath?.takeIf(ScreenshotMediaFilter::isSafeSourcePath), true,
            originalRelativePath, originalDisplayName, onStored
        )
    }

    /** Reuses an already captured clipboard image when MediaStore identifies it as a screenshot. */
    @Synchronized
    fun attachScreenshotSource(
        uri: String,
        sourcePath: String?,
        originalRelativePath: String,
        originalDisplayName: String,
        timestamp: Long
    ): Boolean {
        ensureHistoryLoadedLocked()
        val index = history.indexOfFirst {
            it.type == ClipType.IMAGE && it.imageUri == uri
        }
        if (index < 0) return false
        history[index] = history[index].copy(
            timestamp = timestamp.takeIf { it > 0L } ?: history[index].timestamp,
            isScreenshot = true,
            sourcePath = sourcePath?.takeIf(ScreenshotMediaFilter::isSafeSourcePath)
                ?: history[index].sourcePath,
            originalRelativePath = originalRelativePath,
            originalDisplayName = originalDisplayName
        )
        persistLocked()
        handler.post { refreshIfShowing() }
        return true
    }

    private fun captureImage(
        clip: ClipboardHook.ImageClip,
        timestamp: Long,
        sourceUid: Int,
        sourcePath: String?,
        isScreenshot: Boolean,
        originalRelativePath: String? = null,
        originalDisplayName: String? = null,
        onStored: ((Boolean) -> Unit)?
    ) {
        handler.post {
            val current = ui
            val context = current?.context ?: lastContext
            if (context == null || !ClipboardImageStore.isSupported(clip.mimeType)) {
                onStored?.invoke(false)
                return@post
            }

            val snapshotIndex = synchronized(this) { history.size }
            val target = File(
                ClipboardImageStore.directory(context),
                ClipboardImageStore.fileNameFor("$timestamp-$snapshotIndex", clip.mimeType)
            )
            val destination = target.absolutePath
            val staging = File(
                "/data/system/edgex",
                "clipboard-image-$timestamp-$snapshotIndex-${System.nanoTime()}.tmp"
            )
            ioExecutor.execute {
                if (sourcePath == null) {
                    val stagedBytes = runCatching {
                        staging.parentFile?.mkdirs()
                        val uri = Uri.parse(clip.uri)
                        if (uri.scheme != "content" && uri.scheme != "file") {
                            throw IOException("unsupported clipboard URI scheme")
                        }
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IOException("clipboard image provider returned no stream")
                        input.use { stream ->
                            staging.outputStream().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    total += count
                                    if (total > ClipboardImageStore.MAX_BYTES) {
                                        throw IOException("clipboard image exceeds size limit")
                                    }
                                    output.write(buffer, 0, count)
                                }
                                output.fd.sync()
                                total
                            }
                        }
                    }.getOrElse { error ->
                        runCatching { staging.delete() }
                        val scheme = runCatching { Uri.parse(clip.uri).scheme }.getOrNull()
                        XposedBridge.log(
                            "$TAG: clipboard image capture failed (scheme=$scheme, " +
                                    "error=${error.javaClass.simpleName})"
                        )
                        onStored?.invoke(false)
                        return@execute
                    }
                    if (!ClipboardImageStore.canStore(stagedBytes)) {
                        runCatching { staging.delete() }
                        onStored?.invoke(false)
                        return@execute
                    }
                }
                val imageDir = target.parentFile?.absolutePath.orEmpty()
                val appFilesDir = target.parentFile?.parentFile?.absolutePath.orEmpty()
                val copySource = sourcePath ?: staging.absolutePath
                val sourceSizeCheck = if (sourcePath != null) {
                    "source_size=\$(stat -c %s ${shellQuote(sourcePath)}) && " +
                            "[ -f ${shellQuote(sourcePath)} ] && " +
                            "[ \"\$source_size\" -ge 1 ] && " +
                            "[ \"\$source_size\" -le ${ClipboardImageStore.MAX_BYTES} ] && "
                } else ""
                val command = sourceSizeCheck +
                        "app_uid=\$(stat -c %u ${shellQuote(appFilesDir)}) && " +
                        "mkdir -p ${shellQuote(imageDir)} && " +
                        "chown \$app_uid:1000 ${shellQuote(imageDir)} && " +
                        "chmod 2710 ${shellQuote(imageDir)} && " +
                        "cp -f ${shellQuote(copySource)} ${shellQuote(destination)} && " +
                        (if (sourcePath != null) {
                            "dest_size=\$(stat -c %s ${shellQuote(destination)}) && " +
                                    "[ \"\$dest_size\" = \"\$source_size\" ] && "
                        } else "") +
                        "chown \$app_uid:1000 ${shellQuote(destination)} && " +
                        "chmod 640 ${shellQuote(destination)}"
                ClipboardHook.runRootShell(context, command) { success, output ->
                    if (sourcePath == null) runCatching { staging.delete() }
                    val file = if (success) File(destination) else null
                    val stored = file?.takeIf {
                        it.isFile && ClipboardImageStore.canStore(it.length())
                    }
                    if (stored == null) {
                        runCatching { File(destination).delete() }
                        XposedBridge.log(
                            "$TAG: clipboard image store failed " +
                                    "(rootCopy=$success, error=${output.takeIf { it.isNotBlank() }?.take(120)})"
                        )
                        onStored?.invoke(false)
                        return@runRootShell
                    }
                    storeImageEntry(
                        path = stored.absolutePath,
                        uri = clip.uri,
                        mime = clip.mimeType ?: ClipboardImageStore.mimeFor(stored),
                        timestamp = timestamp,
                        sourceUid = sourceUid,
                        isScreenshot = isScreenshot,
                        sourcePath = sourcePath,
                        originalRelativePath = originalRelativePath,
                        originalDisplayName = originalDisplayName
                    )
                    handler.post { refreshIfShowing() }
                    onStored?.invoke(true)
                }
            }
        }
    }

    @Synchronized
    private fun storeImageEntry(
        path: String,
        uri: String,
        mime: String?,
        timestamp: Long,
        sourceUid: Int,
        isScreenshot: Boolean,
        sourcePath: String?,
        originalRelativePath: String?,
        originalDisplayName: String?
    ) {
        ensureHistoryLoadedLocked()
        val existing = history.indexOfFirst { it.type == ClipType.IMAGE && it.imageUri == uri }
        if (existing >= 0) {
            val entry = history.removeAt(existing)
            runCatching { File(entry.imagePath ?: "").delete() }
            history.add(
                0,
                entry.copy(
                    timestamp = timestamp,
                    imagePath = path,
                    mimeType = mime ?: entry.mimeType,
                    isScreenshot = isScreenshot,
                    sourcePath = sourcePath ?: entry.sourcePath,
                    originalRelativePath = originalRelativePath ?: entry.originalRelativePath,
                    originalDisplayName = originalDisplayName ?: entry.originalDisplayName
                )
            )
        } else {
            history.add(
                0,
                ClipEntry(
                    type = ClipType.IMAGE,
                    imagePath = path,
                    imageUri = uri,
                    mimeType = mime,
                    timestamp = timestamp,
                    sourceUid = sourceUid,
                    isScreenshot = isScreenshot,
                    sourcePath = sourcePath,
                    originalRelativePath = originalRelativePath,
                    originalDisplayName = originalDisplayName
                )
            )
        }
        trimLocked()
        persistLocked()
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
    // ── Coalesced background persistence ───────────────────────────────────────

    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EdgeX-ClipboardIO").apply { isDaemon = true }
    }
    private val rowExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EdgeX-ClipboardRows").apply { isDaemon = true }
    }
    private val pendingSnapshot = AtomicReference<ClipboardHistorySnapshot?>(null)
    private val persistScheduled = AtomicBoolean(false)

    /**
     * Queues a persist without touching the disk on the UI thread. Rapid
     * mutations coalesce to the newest snapshot, and the single writer thread
     * guarantees writes can never reorder.
     */
    private fun persistLocked() {
        pendingSnapshot.set(
            ClipboardHistorySnapshot(
                entries = history.toList(),
                groups = groups.toList(),
                hintDismissed = hintDismissed
            )
        )
        if (persistScheduled.compareAndSet(false, true)) {
            ioExecutor.execute { drainPersistence() }
        }
    }

    private fun drainPersistence() {
        while (true) {
            val snapshot = pendingSnapshot.getAndSet(null) ?: break
            if (!HookClipboardHistoryStore.writeForHook(snapshot)) {
                XposedBridge.log("$TAG: Clipboard history persist failed")
                handler.post { ui?.let { showInfoSnackbar(it, getString(R.string.clipboard_save_failed)) } }
            }
        }
        persistScheduled.set(false)
        // A mutation may have landed after the last drain but before the flag
        // was cleared; reschedule so the final state is never lost.
        if (pendingSnapshot.get() != null && persistScheduled.compareAndSet(false, true)) {
            ioExecutor.execute { drainPersistence() }
        }
    }

    /** Reads the history file off the UI thread (called before showing). */
    private fun loadHistoryBlocking() {
        synchronized(this) { ensureHistoryLoadedLocked() }
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
    private fun togglePin(id: String) {
        ensureHistoryLoadedLocked()
        val index = history.indexOfFirst { it.id == id }
        if (index < 0) return
        val entry = history[index]
        history[index] = entry.copy(pinned = !entry.pinned)
        persistLocked()
    }

    @Synchronized
    private fun removeEntry(id: String): Pair<ClipEntry, Int>? {
        ensureHistoryLoadedLocked()
        val index = history.indexOfFirst { it.id == id }
        if (index < 0) return null
        val removed = history.removeAt(index)
        persistLocked()
        return removed to index
    }

    @Synchronized
    private fun restoreEntry(entry: ClipEntry, index: Int) {
        ensureHistoryLoadedLocked()
        if (history.any { it.id == entry.id }) return
        history.add(index.coerceIn(0, history.size), entry)
        trimLocked()
        persistLocked()
    }

    /** Removes every unpinned clip; pinned clips are never touched here. */
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
        removed.forEach { scheduleImageCleanup(it.first) }
        return removed
    }

    /** Removes unpinned clips that belong to [group]; pinned members stay. */
    @Synchronized
    private fun clearGroup(group: String): List<Pair<ClipEntry, Int>> {
        ensureHistoryLoadedLocked()
        val removed = ArrayList<Pair<ClipEntry, Int>>()
        var index = 0
        while (index < history.size) {
            val entry = history[index]
            if (!entry.pinned && entry.group == group) {
                removed += history.removeAt(index) to index
            } else {
                index++
            }
        }
        persistLocked()
        removed.forEach { scheduleImageCleanup(it.first) }
        return removed
    }

    @Synchronized
    private fun clearAll() {
        ensureHistoryLoadedLocked()
        val removed = history.toList()
        history.clear()
        persistLocked()
        removed.forEach { scheduleImageCleanup(it) }
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
    private fun moveToGroup(id: String, group: String?) {
        ensureHistoryLoadedLocked()
        val index = history.indexOfFirst { it.id == id }
        if (index < 0) return
        history[index] = history[index].copy(group = group)
        persistLocked()
    }

    @Synchronized
    private fun updateEntryText(id: String, newText: String): Boolean {
        ensureHistoryLoadedLocked()
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return false
        val index0 = history.indexOfFirst { it.id == id }
        if (index0 < 0) return false
        val entry = history[index0]
        if (entry.type != ClipType.TEXT) return false
        var index = index0
        var cursor = 0
        while (cursor < history.size) {
            val other = history[cursor]
            if (cursor != index && other.type == ClipType.TEXT && other.text == trimmed) {
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

    /** Keeps clipboard capture available before the user first opens the sheet. */
    fun setSystemContext(context: Context) {
        lastContext = context
    }

    fun show(context: Context) {
        lastContext = context
        // Load the persisted history off the UI thread, then build the overlay
        // with the data already in memory (no disk IO while rendering).
        ioExecutor.execute {
            loadHistoryBlocking()
            handler.post {
                dismiss()
                try {
                    addOverlay(context)
                } catch (t: Throwable) {
                    // Never surface a raw module resource id to the framework:
                    // log the full stack so the failing view is identifiable.
                    XposedBridge.log("$TAG: ClipboardOverlay show failed: ${t.message}")
                    XposedBridge.log(t)
                }
            }
        }
    }

    fun dismiss() {
        autoDismissRunnable?.let { handler.removeCallbacks(it) }
        autoDismissRunnable = null
        searchRefreshRunnable?.let { handler.removeCallbacks(it) }
        searchRefreshRunnable = null
        rowBuildGeneration.incrementAndGet()
        undoRunnable?.let { handler.removeCallbacks(it) }
        undoRunnable = null
        selectedClipIds.clear()
        val current = ui ?: return
        dismissAnimating = false
        ui = null
        adapter = null
        if (!backupInProgress.get()) unbindShellService()
        try {
            val wm = current.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(current.root)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: ClipboardOverlay dismiss failed: ${t.message}")
        }
    }

    private fun dismissAnimated() {
        val current = ui ?: return
        if (dismissAnimating) return
        dismissAnimating = true
        current.sheet.animate().cancel()
        current.sheet.animate()
            .translationY(current.sheet.height.coerceAtLeast(current.dp(240)).toFloat())
            .alpha(0f)
            .setDuration(190L)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { dismiss() }
            .start()
    }

    /**
     * Recomputes the layout of the open overlay after rotation, fold/unfold or
     * an inset/navigation-bar change, so menus and cards can never be left
     * off-screen. Called from the overlay root's onConfigurationChanged.
     */
    private fun handleConfigurationChanged() {
        handler.post {
            val current = ui ?: return@post
            val metrics = current.context.resources.displayMetrics
            if (metrics.widthPixels != current.lastWidth ||
                metrics.heightPixels != current.lastHeight
            ) {
                current.lastWidth = metrics.widthPixels
                current.lastHeight = metrics.heightPixels
                clipUiCache.clear()
                closePopup(current)
                refreshRows(current, preserveScroll = false)
            }
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
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                super.onConfigurationChanged(newConfig)
                handleConfigurationChanged()
            }

            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.action == MotionEvent.ACTION_DOWN) {
                    resetAutoDismiss()
                    val current = ui
                    if (current != null && current.popup != null) {
                        return super.dispatchTouchEvent(ev)
                    }
                    if (current != null && current.sheetTop > 0 && ev.y < current.sheetTop) {
                        dismissAnimated()
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
                            val handler = current.onBackPressed
                            if (handler == null || !handler()) {
                                closePopup(current)
                            }
                        } else if (current.keyboardVisible) {
                            hideKeyboard(current)
                        } else if (selectedClipIds.isNotEmpty()) {
                            clearClipSelection(current)
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

        // Transient layer: always the highest child so internal menus/dialogs
        // stay above the sheet. Elevation must exceed the sheet's elevation.
        val transientLayer = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            outlineProvider = null
            elevation = dp(48).toFloat()
        }

        val sheetHolder = SheetLayout(context) { ui?.keyboardVisible == true }
        val built = try {
            buildSheet(context, sheetHolder, palette, dp, bottomSafeArea)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: ClipboardOverlay buildSheet failed: ${t.message}")
            XposedBridge.log(t)
            sheetHolder.addView(
                androidx.appcompat.widget.AppCompatTextView(context).apply {
                    text = ModuleRes.getString(R.string.clipboard_empty)
                    setTextColor(palette.textPrimary)
                    setPadding(dp(24), dp(24), dp(24), dp(24))
                }
            )
            SheetParts(
                titleHeader = View(context),
                search = EditText(context),
                searchClear = View(context),
                recycler = RecyclerView(ModuleRes.contextWithModuleResources(context)),
                countView = TextView(context),
                clearAll = TextView(context),
                selectionBar = LinearLayout(context),
                selectionCount = TextView(context),
                selectionPaste = TextView(context),
                selectionCancel = TextView(context),
                hintBar = null,
                bottomSpacer = View(context)
            )
        }

        root.addView(
            sheetHolder,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        )
        root.addView(
            transientLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val newUi = OverlayUi(
            context = context,
            palette = palette,
            root = root,
            sheet = sheetHolder,
            bottomSpacer = built.bottomSpacer,
            bottomSafeArea = bottomSafeArea,
            transientLayer = transientLayer,
            titleHeader = built.titleHeader,
            search = built.search,
            searchClear = built.searchClear,
            recycler = built.recycler,
            countView = built.countView,
            clearAll = built.clearAll,
            selectionBar = built.selectionBar,
            selectionCount = built.selectionCount,
            selectionPaste = built.selectionPaste,
            selectionCancel = built.selectionCancel,
            hintBar = built.hintBar
        )
        ui = newUi
        val newAdapter = ClipAdapter()
        adapter = newAdapter
        built.recycler.adapter = newAdapter
        built.selectionPaste.setOnClickListener { pasteSelectedClips(newUi) }
        built.selectionCancel.setOnClickListener { clearClipSelection(newUi) }
        ItemTouchHelper(buildSwipeCallback()).attachToRecyclerView(built.recycler)

        // The handle is a dedicated drag target, so list scrolling and item
        // swipes remain untouched. A short downward fling or a half-height
        // drag closes the sheet; smaller drags spring back into place.
        var dragStartY = 0f
        var dragStartTime = 0L
        val dragTarget = sheetHolder.getChildAt(0)
        dragTarget?.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.rawY
                    dragStartTime = event.eventTime
                    sheetHolder.animate().cancel()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    sheetHolder.translationY = (event.rawY - dragStartY).coerceAtLeast(0f)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val distance = sheetHolder.translationY
                    val elapsed = (event.eventTime - dragStartTime).coerceAtLeast(1L)
                    val velocity = distance * 1000f / elapsed
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        (distance >= sheetHolder.height * 0.22f || velocity >= dp(900).toFloat())
                    ) {
                        dismissAnimated()
                    } else {
                        sheetHolder.animate().translationY(0f).setDuration(170L)
                            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    }
                    true
                }
                else -> false
            }
        }

        root.setOnApplyWindowInsetsListener { view, insets ->
            applyImeInsets(newUi, insets)
            view.onApplyWindowInsets(insets)
        }
        root.setWindowInsetsAnimationCallback(
            object : WindowInsetsAnimation.Callback(
                WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onProgress(
                    insets: WindowInsets,
                    runningAnimations: List<WindowInsetsAnimation>
                ): WindowInsets {
                    applyImeInsets(newUi, insets)
                    return insets
                }
            }
        )

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
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        })
        root.requestApplyInsets()

        sheetHolder.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
            newUi.sheetTop = top
        }
        root.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, _ ->
            if (newUi.rootHeight == 0 && bottom > 0) {
                newUi.rootHeight = bottom
            }
            if (!newUi.imeInsetsKnown) {
                val shrunk = newUi.rootHeight - bottom
                val visible = shrunk > dp(100)
                if (visible != newUi.keyboardVisible) {
                    newUi.keyboardVisible = visible
                    sheetHolder.requestLayout()
                }
            }
        }

        // Entrance animation.
        sheetHolder.translationY = dp(56).toFloat()
        sheetHolder.alpha = 0f
        sheetHolder.animate().translationY(0f).alpha(1f).setDuration(240).start()

        refreshRows(newUi)
        resetAutoDismiss()
    }

    private fun applyImeInsets(current: OverlayUi, insets: WindowInsets) {
        current.imeInsetsKnown = true
        val visible = insets.isVisible(WindowInsets.Type.ime())
        val bottomInset = if (visible) {
            maxOf(
                insets.getInsets(WindowInsets.Type.ime()).bottom,
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            )
        } else {
            0
        }
        val wasVisible = current.keyboardVisible
        current.keyboardVisible = visible

        if (current.root.paddingBottom != bottomInset) {
            current.root.setPadding(
                current.root.paddingLeft,
                current.root.paddingTop,
                current.root.paddingRight,
                bottomInset
            )
        }
        val spacerHeight = if (visible) 0 else current.bottomSafeArea
        current.bottomSpacer.layoutParams?.let { params ->
            if (params.height != spacerHeight) {
                params.height = spacerHeight
                current.bottomSpacer.layoutParams = params
            }
        }

        if (visible != wasVisible) {
            if (visible) {
                animateTitleHeader(current, collapse = true)
            } else if (current.recycler.computeVerticalScrollOffset() <= current.dp(18)) {
                animateTitleHeader(current, collapse = false)
            }
            current.sheet.requestLayout()
        }
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
        val recycler: RecyclerView,
        val countView: TextView,
        val clearAll: TextView,
        val selectionBar: LinearLayout,
        val selectionCount: TextView,
        val selectionPaste: TextView,
        val selectionCancel: TextView,
        val hintBar: View?,
        val bottomSpacer: View
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

        val titleColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
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
        titleColumn.addView(
            countView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(1) }
        )
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
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46))
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

        // ── Search (sticky, outside the list) ──────────────────────────────────
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
                textCursorDrawable = ClipboardUiKit.rounded(palette.accent, dp(1).toFloat())
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
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(46))
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

        val selectionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(12), 0, dp(8), 0)
            background = ClipboardUiKit.rounded(
                palette.accentAlpha(0.10f), dp(12).toFloat(), dp(1),
                palette.accentAlpha(0.24f)
            )
            visibility = View.GONE
        }
        val selectionCount = TextView(context).apply {
            text = getString(R.string.clipboard_selected_count, 0)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textPrimary)
            maxLines = 1
        }
        selectionBar.addView(
            selectionCount,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val selectionCancel = TextView(context).apply {
            text = getString(R.string.clipboard_cancel)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textSecondary)
            gravity = Gravity.CENTER
            setPaddingRelative(dp(10), 0, dp(10), 0)
            background = ClipboardUiKit.ripple(rippleColor(palette))
        }
        selectionBar.addView(
            selectionCancel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
        )
        val selectionPaste = TextView(context).apply {
            text = getString(R.string.clipboard_paste)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
            setPaddingRelative(dp(12), 0, dp(12), 0)
            background = ClipboardUiKit.ripple(rippleColor(palette))
        }
        selectionBar.addView(
            selectionPaste,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
                .apply { marginStart = dp(4) }
        )
        sheet.addView(
            selectionBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                marginStart = dp(18)
                marginEnd = dp(18)
                bottomMargin = dp(6)
            }
        )

        // ── Recycled list ──────────────────────────────────────────────────────
        val recyclerContext = ModuleRes.contextWithModuleResources(context)
        val recycler = RecyclerView(recyclerContext).apply {
            layoutManager = LinearLayoutManager(recyclerContext)
            itemAnimator = DefaultItemAnimator().apply {
                supportsChangeAnimations = false
                addDuration = 120
                removeDuration = 120
                moveDuration = 140
                changeDuration = 0
            }
            setHasFixedSize(false)
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            isVerticalScrollBarEnabled = true
            setPaddingRelative(dp(16), dp(2), dp(16), dp(8))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val current = currentUi() ?: return
                    val shouldCollapse = rv.computeVerticalScrollOffset() > current.dp(18)
                    if (shouldCollapse != current.titleCollapsed) {
                        animateTitleHeader(current, shouldCollapse)
                    }
                }
            })
        }
        sheet.addView(
            recycler,
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

        val bottomSpacer = View(context)
        sheet.addView(
            bottomSpacer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bottomSafeArea)
        )

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                // Debounce fast key repeats; row filtering runs on the row worker.
                scheduleSearchRefresh()
                resetAutoDismiss()
            }
        })

        return SheetParts(
            titleHeader = titleHeader,
            search = search,
            searchClear = searchClear,
            recycler = recycler,
            countView = countView,
            clearAll = clearAll,
            selectionBar = selectionBar,
            selectionCount = selectionCount,
            selectionPaste = selectionPaste,
            selectionCancel = selectionCancel,
            hintBar = hintBar,
            bottomSpacer = bottomSpacer
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
            background = ClipboardUiKit.rounded(palette.raised, dp(12).toFloat())
            setPaddingRelative(dp(10), dp(6), dp(2), dp(6))
        }
        val info = ImageView(context).apply {
            setImageDrawable(ModuleRes.getDrawable(R.drawable.ic_info, palette.accent))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        bar.addView(info, LinearLayout.LayoutParams(dp(16), dp(16)))
        bar.addView(
            TextView(context).apply {
                text = getString(R.string.clipboard_hint)
                textSize = 12f
                setTextColor(palette.textSecondary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) }
        )
        val close = actionIcon(
            context, R.drawable.ic_close, palette, palette.textSecondary, dp(28), dp(28),
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

    // ── List model / refresh ───────────────────────────────────────────────────

    private fun currentUi(): OverlayUi? = ui

    private fun refreshIfShowing() {
        val current = ui ?: return
        // A clipboard change can refresh relative timestamps; drop the cached
        // metadata so the visible list picks them up. Search keys are rebuilt
        // lazily and cheaply.
        clipUiCache.clear()
        refreshRows(current, preserveScroll = true, animateTop = true)
    }

    // ── Cached per-clip UI data (main thread only) ─────────────────────────────

    private class ClipUi(
        val searchKey: String,
        val kind: ClipboardUiKit.ClipKind,
        val meta: String
    )

    private data class SourceApp(val packageName: String, val label: String)

    private val clipUiCache = java.util.Collections.synchronizedMap(HashMap<String, ClipUi>())
    @Volatile private var rowLabels: RowLabels? = null

    private fun labels(): RowLabels = rowLabels ?: RowLabels(
        pinned = getString(R.string.clipboard_pinned),
        today = getString(R.string.clipboard_today),
        yesterday = getString(R.string.clipboard_yesterday),
        earlier = getString(R.string.clipboard_earlier),
        history = getString(R.string.clipboard_history),
        groupEmpty = getString(R.string.clipboard_group_empty),
        empty = getString(R.string.clipboard_empty),
        emptyHint = getString(R.string.clipboard_empty_hint),
        noResults = getString(R.string.clipboard_no_results),
        historyEmpty = getString(R.string.clipboard_history_empty)
    ).also { rowLabels = it }

    /**
     * Cached classification/metadata for one clip. The expensive parts
     * (classification, DateFormat, PackageManager lookup) run once per clip,
     * never per keystroke and never per bind.
     */
    private fun clipUi(current: OverlayUi, entry: ClipEntry): ClipUi {
        val key = entry.id
        clipUiCache[key]?.let { return it }
        val kind = when (entry.type) {
            ClipType.IMAGE -> ClipboardUiKit.ClipKind.IMAGE
            ClipType.TEXT -> ClipboardUiKit.classify(entry.text)
        }
        return ClipUi(
            searchKey = ClipboardRowModel.searchKey(entry.searchSource),
            kind = kind,
            meta = metaLabel(current, entry)
        ).also { clipUiCache[key] = it }
    }

    private fun invalidateClipUi(id: String) {
        clipUiCache.remove(id)
    }

    private fun refreshRows(
        current: OverlayUi,
        preserveScroll: Boolean = true,
        animateTop: Boolean = false,
        requestId: Long = rowBuildGeneration.incrementAndGet()
    ) {
        val (entries, groupNames) = synchronized(this) {
            ensureHistoryLoadedLocked()
            history.toList() to groups.toList()
        }
        val query = current.search.text?.toString().orEmpty()
        val collapsed = collapsedSections.toSet()
        val rowLabels = labels()
        if (selectedClipIds.retainAll(entries.mapTo(HashSet()) { it.id })) {
            updateSelectionUi(current)
        }

        // Classification, source-app labels, date formatting and filtering can
        // all touch framework services. Keep that work off system_server's UI
        // thread so opening the sheet and typing stay responsive.
        rowExecutor.execute {
            val rows = ClipboardRowModel.build(
                entries = entries,
                groups = groupNames,
                query = query,
                collapsed = collapsed,
                labels = rowLabels,
                metaOf = { clipUi(current, it).meta },
                kindOf = { clipUi(current, it).kind },
                searchKeyOf = { clipUi(current, it).searchKey }
            )
            val hasUnpinned = entries.any { !it.pinned }
            handler.post {
                if (ui !== current || rowBuildGeneration.get() != requestId) return@post
                adapter?.submitList(rows)
                current.countView.text = getString(R.string.clipboard_count, entries.size)
                current.clearAll.isEnabled = hasUnpinned
                current.clearAll.alpha = if (hasUnpinned) 1f else 0.4f
                if (!preserveScroll) current.recycler.scrollToPosition(0)
                if (animateTop) fadeFirstRow(current)
            }
        }
    }

    private fun scheduleSearchRefresh() {
        searchRefreshRunnable?.let { handler.removeCallbacks(it) }
        val requestId = rowBuildGeneration.incrementAndGet()
        val runnable = Runnable {
            searchRefreshRunnable = null
            val current = ui ?: return@Runnable
            refreshRows(current, preserveScroll = false, requestId = requestId)
        }
        searchRefreshRunnable = runnable
        handler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
    }

    private fun kindIconRes(kind: ClipboardUiKit.ClipKind): Int = when (kind) {
        ClipboardUiKit.ClipKind.CODE -> R.drawable.ic_code
        ClipboardUiKit.ClipKind.LINK -> R.drawable.ic_link
        ClipboardUiKit.ClipKind.EMAIL -> R.drawable.ic_mail
        ClipboardUiKit.ClipKind.IMAGE -> R.drawable.ic_image
        ClipboardUiKit.ClipKind.NOTE -> R.drawable.ic_note
    }

    private fun metaLabel(current: OverlayUi, entry: ClipEntry): String {
        val parts = ArrayList<String>(3)
        ClipboardUiKit.timeLabel(current.context, entry.timestamp)
            .takeIf { it.isNotEmpty() }?.let(parts::add)
        sourceApp(current.context, entry.sourceUid)?.label?.let(parts::add)
        return parts.joinToString(" · ")
    }

    private fun sourceApp(context: Context, uid: Int): SourceApp? {
        if (uid < 10_000) return null
        synchronized(sourceAppCache) {
            if (sourceAppCache.containsKey(uid)) return sourceAppCache[uid]
        }
        val app = runCatching {
            val pm = context.packageManager
            val packageName = pm.getPackagesForUid(uid)?.firstOrNull() ?: return@runCatching null
            val appInfo = pm.getApplicationInfo(packageName, 0)
            SourceApp(packageName, pm.getApplicationLabel(appInfo).toString())
        }.getOrNull()
        synchronized(sourceAppCache) {
            if (!sourceAppCache.containsKey(uid)) sourceAppCache[uid] = app
            return sourceAppCache[uid]
        }
    }

    private fun fadeFirstRow(current: OverlayUi) {
        current.recycler.post {
            val holder = current.recycler.findViewHolderForAdapterPosition(0) ?: return@post
            holder.itemView.alpha = 0f
            holder.itemView.animate().alpha(1f).setDuration(160).start()
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    private fun onClearAllClicked() {
        val current = ui ?: return
        val removed = clearUnpinned()
        clipUiCache.clear()
        refreshRows(current)
        if (removed.isEmpty()) return
        showUndoSnackbar(current, getString(R.string.clipboard_cleared)) {
            for ((entry, index) in removed.asReversed()) {
                restoreEntry(entry, index)
            }
            clipUiCache.clear()
            refreshRows(current)
        }
    }

    private fun onClearGroupClicked(current: OverlayUi, name: String) {
        val removed = clearGroup(name)
        clipUiCache.clear()
        refreshRows(current)
        if (removed.isEmpty()) return
        showUndoSnackbar(current, getString(R.string.clipboard_cleared)) {
            for ((entry, index) in removed.asReversed()) {
                restoreEntry(entry, index)
            }
            clipUiCache.clear()
            refreshRows(current)
        }
    }

    private fun toggleClipSelection(current: OverlayUi, row: DisplayRow.Clip) {
        val id = row.entry.id
        if (!selectedClipIds.add(id)) selectedClipIds.remove(id)
        updateSelectionUi(current)
        notifyClipRowChanged(id)
        resetAutoDismiss()
    }

    private fun clearClipSelection(current: OverlayUi) {
        val previous = selectedClipIds.toList()
        selectedClipIds.clear()
        updateSelectionUi(current)
        previous.forEach(::notifyClipRowChanged)
    }

    private fun notifyClipRowChanged(id: String) {
        val position = adapter?.currentList?.indexOfFirst {
            it is DisplayRow.Clip && it.entry.id == id
        } ?: -1
        if (position >= 0) adapter?.notifyItemChanged(position)
    }

    private fun updateSelectionUi(current: OverlayUi) {
        val count = selectedClipIds.size
        current.selectionBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        current.selectionCount.text = getString(R.string.clipboard_selected_count, count)
        current.selectionPaste.isEnabled = count > 0
        current.selectionPaste.alpha = if (count > 0) 1f else 0.45f
    }

    private fun pasteSelectedClips(current: OverlayUi) {
        val selected = historySnapshot().filter { it.id in selectedClipIds }
        if (selected.isEmpty()) {
            clearClipSelection(current)
            return
        }
        if (selected.size == 1 && selected[0].type == ClipType.IMAGE) {
            pasteImageEntry(selected[0])
            return
        }
        if (selected.any { it.type == ClipType.IMAGE }) {
            showInfoSnackbar(current, getString(R.string.clipboard_multi_paste_text_only))
            return
        }

        val text = selected.joinToString("\n") { it.text }
        current.selectionCount.text = getString(R.string.clipboard_pasted_count, selected.size)
        current.selectionCancel.visibility = View.GONE
        current.selectionPaste.visibility = View.GONE
        current.selectionBar.background = ClipboardUiKit.rounded(
            current.palette.accentAlpha(0.22f), current.dp(12).toFloat(),
            current.dp(1), current.palette.accentAlpha(0.48f)
        )
        pasteEntry(
            text,
            feedbackView = current.selectionBar,
            successToast = getString(R.string.clipboard_pasted_count, selected.size)
        )
    }

    private fun pasteEntry(
        text: String,
        feedbackView: View? = null,
        successToast: String? = null
    ) {
        val current = ui ?: return
        val context = current.context
        if (feedbackView != null) {
            feedbackView.background = ClipboardUiKit.cardRipple(
                current.palette.accentAlpha(0.24f), current.dp(14).toFloat(),
                current.dp(1), current.palette.accentAlpha(0.56f),
                rippleColor(current.palette)
            )
        }
        val beginPaste = {
            hideKeyboard(current)
            dismiss()
            handler.postDelayed({
                pasteText(context, text) { pasted ->
                    if (pasted) {
                        Toast.makeText(
                            context,
                            successToast ?: getString(R.string.clipboard_pasted),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(context, getString(R.string.clipboard_paste_failed), Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }, PASTE_DELAY_MS)
            Unit
        }
        if (feedbackView == null) beginPaste() else handler.postDelayed(beginPaste, 150L)
    }

    /** Paste an image URI into an editor that accepts it; text terminals get a file path. */
    private fun pasteImageEntry(entry: ClipEntry) {
        val current = ui ?: return
        val context = current.context
        dismiss()
        handler.postDelayed({
            UniversalCopyManager.deliverImageIntoFocusedField(
                context,
                commitImage = { packageName, ready ->
                    commitImageToIme(context, entry, packageName, ready)
                },
                onComplete = { packageName, hasFocusedField, pasted ->
                    when {
                        packageName == "com.termux" && hasFocusedField ->
                            pasteImagePath(context, entry)
                        pasted -> Toast.makeText(
                            context, getString(R.string.clipboard_image_pasted), Toast.LENGTH_SHORT
                        ).show()
                        else -> copyImageToClipboard(context, entry) { copied ->
                            Toast.makeText(
                                context,
                                getString(
                                    if (copied) R.string.clipboard_image_paste_manual
                                    else R.string.clipboard_image_missing
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
        }, PASTE_DELAY_MS)
    }

    /** The active IME commits rich image content; the bridge also leaves it on clipboard. */
    private fun commitImageToIme(
        context: Context,
        entry: ClipEntry,
        targetPackage: String?,
        onComplete: (Boolean) -> Unit
    ) {
        val uri = ClipboardImageStore.shareUri(context, entry.imagePath)
        if (uri == null || targetPackage.isNullOrBlank()) {
            onComplete(false)
            return
        }
        markSkipImage(uri.toString())
        withImageBridge(context, { bridge ->
            bridge.commitImageToEditor(
                uri.toString(),
                entry.mimeType ?: ClipboardImageStore.mimeFor(
                    ClipboardImageStore.fileFor(context, entry.imagePath) ?: return@withImageBridge false
                ),
                ClipboardImageStore.displayLabel(context, entry),
                targetPackage
            )
        }, onComplete)
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

    private fun copyImageEntry(current: OverlayUi, entry: ClipEntry) {
        copyImageToClipboard(current.context, entry) { ok ->
            Toast.makeText(
                current.context,
                getString(if (ok) R.string.clipboard_image_saved else R.string.clipboard_image_missing),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Writes the image back as content:// so Android's clipboard service can
     * grant the active reader access. The URI is added to the self-copy skip
     * set so re-copying never creates a duplicate entry.
     */
    private fun copyImageToClipboard(
        context: Context,
        entry: ClipEntry,
        targetPackage: String? = null,
        onComplete: (Boolean) -> Unit
    ) {
        val uri = ClipboardImageStore.shareUri(context, entry.imagePath)
        if (uri == null) {
            onComplete(false)
            return
        }
        withImageBridge(context, { bridge ->
            markSkipImage(uri.toString())
            bridge.setImageClip(
                uri.toString(),
                ClipboardImageStore.displayLabel(context, entry),
                targetPackage
            )
        }, onComplete)
    }

    private fun withImageBridge(
        context: Context,
        operation: (IClipboardImageBridge) -> Boolean,
        onComplete: (Boolean) -> Unit
    ) {
        val intent = Intent().apply {
            component = ComponentName(
                BuildConfig.APPLICATION_ID,
                "${BuildConfig.APPLICATION_ID}.config.ClipboardImageBridgeService"
            )
        }
        val connection = object : ServiceConnection {
            private var finished = false
            private var timeout: Runnable? = null

            fun scheduleTimeout() {
                timeout = Runnable { finish(false) }
                handler.postDelayed(timeout!!, IMAGE_BRIDGE_TIMEOUT_MS)
            }

            private fun finish(ok: Boolean) {
                if (finished) return
                finished = true
                timeout?.let(handler::removeCallbacks)
                runCatching { context.unbindService(this) }
                onComplete(ok)
            }

            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val bridge = IClipboardImageBridge.Stub.asInterface(binder)
                if (bridge == null) {
                    finish(false)
                    return
                }
                ioExecutor.execute {
                    val ok = runCatching { operation(bridge) }
                        .onFailure { error ->
                            XposedBridge.log(
                                "$TAG: image bridge failed: ${error.javaClass.simpleName}"
                            )
                        }
                        .getOrDefault(false)
                    handler.post { finish(ok) }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) = finish(false)
        }
        val bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (bound) {
            connection.scheduleTimeout()
        } else {
            onComplete(false)
        }
    }

    private fun pasteImagePath(context: Context, entry: ClipEntry) {
        withSharedImagePath(context, entry) { path ->
            if (path == null) {
                Toast.makeText(context, getString(R.string.clipboard_image_missing), Toast.LENGTH_SHORT)
                    .show()
            } else {
                pasteText(context, path) { pasted ->
                    Toast.makeText(
                        context,
                        getString(
                            if (pasted) R.string.clipboard_image_path_pasted
                            else R.string.clipboard_paste_failed
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /** Gives terminals and file managers a stable, user-readable shared path. */
    private fun withSharedImagePath(context: Context, entry: ClipEntry, onPath: (String?) -> Unit) {
        val original = entry.sourcePath
            ?.takeIf(ScreenshotMediaFilter::isSafeSourcePath)
            ?.takeIf { File(it).isFile }
        if (original != null) {
            onPath(original)
            return
        }

        val source = ClipboardImageStore.fileFor(context, entry.imagePath)
        if (source == null) {
            onPath(null)
            return
        }

        val outputName = sharedImageExportName(entry)
        val sharedDirectory = "/data/media/0/Download/EdgeXClipboard"
        val sharedPath = sharedImageExportPath(entry)
        val destination = "$sharedDirectory/$outputName"
        val temporary = "$destination.tmp"
        val command = "mkdir -p ${shellQuote(sharedDirectory)} && " +
                "chown 1023:1023 ${shellQuote(sharedDirectory)} && " +
                "chmod 2775 ${shellQuote(sharedDirectory)} && " +
                "cp -f ${shellQuote(source.absolutePath)} ${shellQuote(temporary)} && " +
                "chown 1023:1023 ${shellQuote(temporary)} && " +
                "chmod 0644 ${shellQuote(temporary)} && " +
                "mv -f ${shellQuote(temporary)} ${shellQuote(destination)}"
        ensureShellService(context)
        runRoot(command) { success, _ -> onPath(sharedPath.takeIf { success }) }
    }

    /** A document-picker location is offered only while the original MediaStore item still exists. */
    private fun originalDocumentUri(context: Context, entry: ClipEntry): Uri? {
        if (!entry.isScreenshot) return null
        val mediaUri = runCatching { Uri.parse(entry.originalUri ?: return null) }.getOrNull()
            ?: return null
        if (mediaUri.scheme != "content" || mediaUri.authority != "media") return null
        return runCatching {
            // Confirm the original row still resolves; never substitute EdgeX's private copy here.
            context.contentResolver.openFileDescriptor(mediaUri, "r")?.use { } ?: return null
            MediaStore.getDocumentUri(context, mediaUri)
        }.getOrNull()
    }

    /** Uses the MediaStore file-path metadata when an older history item lacks its cached path. */
    private fun originalSharedFilePath(context: Context, entry: ClipEntry, mediaUri: Uri): String? {
        val cached = entry.sourcePath
            ?.takeIf(ScreenshotMediaFilter::isSafeSourcePath)
        if (cached != null) return cached

        val providerPath = runCatching {
            context.contentResolver.query(
                mediaUri,
                arrayOf(MediaStore.Images.Media.DATA),
                null,
                null,
                null
            )?.use { cursor ->
                val column = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
        return providerPath
            ?.takeIf(ScreenshotMediaFilter::isSafeSourcePath)
    }

    private fun openImageFolder(context: Context, entry: ClipEntry) {
        val mediaUri = runCatching { Uri.parse(entry.originalUri ?: return openPrivateImage(context, entry)) }
            .getOrNull()
        val sourcePath = mediaUri?.let { originalSharedFilePath(context, entry, it) }
        if (RealmeFileRevealCompat.reveal(context, sourcePath, entry.originalDisplayName)) return
        XposedBridge.log("$TAG: ColorOS file reveal unavailable; using the Android document picker")

        val documentUri = originalDocumentUri(context, entry)
        if (documentUri == null) {
            if (mediaUri != null && tryOpenImage(context, mediaUri, entry.mimeType)) return
            openPrivateImage(context, entry)
            return
        }

        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = entry.mimeType?.takeIf { it.startsWith("image/") } ?: "image/*"
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, documentUri)
                clipData = ClipData.newUri(context.contentResolver, entry.originalDisplayName ?: "Image", documentUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: open screenshot location failed: ${t.javaClass.simpleName}")
            if (mediaUri != null && tryOpenImage(context, mediaUri, entry.mimeType)) return
            openPrivateImage(context, entry)
        }
    }

    /** Reveals the original file in MT Manager's exported two-pane browser. */
    private fun isMtManagerImageViewerAvailable(context: Context): Boolean = runCatching {
        val component = ComponentName("bin.mt.plus", "bin.mt.plus.Main")
        val activity = context.packageManager.getActivityInfo(component, 0)
        activity.enabled && activity.exported
    }.getOrDefault(false)

    private fun openOriginalImageInMtManager(context: Context, entry: ClipEntry): Boolean =
        runCatching {
            val mediaUri = Uri.parse(entry.originalUri ?: return false)
            if (mediaUri.scheme != "content" || mediaUri.authority != "media") return false
            context.contentResolver.openFileDescriptor(mediaUri, "r")?.use { } ?: return false
            val path = originalSharedFilePath(context, entry, mediaUri) ?: return false
            val mtComponent = ComponentName("bin.mt.plus", "bin.mt.plus.Main")
            val activity = context.packageManager.getActivityInfo(mtComponent, 0)
            if (!activity.enabled || !activity.exported) return false
            val intent = Intent(Intent.ACTION_VIEW).apply {
                component = mtComponent
                setDataAndType(Uri.fromFile(File(path)), entry.mimeType ?: "image/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.onFailure { error ->
            XposedBridge.log("$TAG: MT Manager image open failed: ${error.javaClass.simpleName}")
        }.getOrDefault(false)

    private fun tryOpenImage(context: Context, uri: Uri, mimeType: String?): Boolean = runCatching {
        if (uri.authority == "media") {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { } ?: return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType?.takeIf { it.startsWith("image/") } ?: "image/*")
            clipData = ClipData.newUri(context.contentResolver, "Image", uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        true
    }.onFailure { error ->
        XposedBridge.log("$TAG: image viewer launch failed: ${error.javaClass.simpleName}")
    }.getOrDefault(false)

    /** Opens the image itself, preferring the stable original MediaStore URI. */
    private fun openImageEntry(context: Context, entry: ClipEntry) {
        val original = runCatching { Uri.parse(entry.originalUri ?: "") }.getOrNull()
        if (original != null && original.scheme == "content" &&
            tryOpenImage(context, original, entry.mimeType)
        ) return
        openPrivateImage(context, entry)
    }

    private fun openPrivateImage(context: Context, entry: ClipEntry) {
        val uri = ClipboardImageStore.shareUri(context, entry.imagePath)
        if (uri == null) {
            Toast.makeText(context, getString(R.string.clipboard_image_missing), Toast.LENGTH_SHORT).show()
            return
        }
        withImageBridge(context, { bridge ->
            bridge.openImage(
                uri.toString(),
                entry.mimeType ?: ClipboardImageStore.mimeFor(
                    ClipboardImageStore.fileFor(context, entry.imagePath) ?: return@withImageBridge false
                ),
                ClipboardImageStore.displayLabel(context, entry)
            )
        }) { opened ->
            if (opened && tryOpenImage(context, uri, entry.mimeType)) return@withImageBridge
            Toast.makeText(
                context, getString(R.string.clipboard_image_missing), Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun shareEntry(context: Context, entry: ClipEntry) {
        if (entry.type == ClipType.TEXT) {
            runCatching {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, entry.text)
                }
                context.startActivity(
                    Intent.createChooser(send, getString(R.string.clipboard_share)).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                )
            }.onFailure { error ->
                XposedBridge.log("$TAG: text share failed: ${error.javaClass.simpleName}")
            }
            return
        }

        val uri = ClipboardImageStore.shareUri(context, entry.imagePath)
        if (uri == null) {
            Toast.makeText(context, getString(R.string.clipboard_image_missing), Toast.LENGTH_SHORT)
                .show()
            return
        }
        withImageBridge(context, { bridge ->
            bridge.shareImage(uri.toString(), entry.mimeType, getString(R.string.clipboard_share))
        }) { shared ->
            if (shared) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = entry.mimeType?.takeIf { it.startsWith("image/") } ?: "image/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(context.contentResolver, "Image", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, getString(R.string.clipboard_share))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (runCatching { context.startActivity(chooser) }.isSuccess) return@withImageBridge
            }
            Toast.makeText(
                context, getString(R.string.clipboard_image_share_failed), Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun markSkipNextClip(text: String) {
        skipNextClipText = text
        handler.postDelayed({ if (skipNextClipText == text) skipNextClipText = null }, 1_500L)
    }

    private fun markSkipImage(uri: String) {
        skipNextImageUri = uri
        handler.postDelayed({ if (skipNextImageUri == uri) skipNextImageUri = null }, 1_500L)
    }

    private fun togglePinAndRefresh(current: OverlayUi, entry: ClipEntry) {
        togglePin(entry.id)
        refreshRows(current)
        // The pinned card is recreated in its new section; DiffUtil animates the
        // move, so no manual per-row fade is needed.
    }

    private fun deleteEntry(current: OverlayUi, entry: ClipEntry) {
        val removed = removeEntry(entry.id) ?: return
        invalidateClipUi(entry.id)
        refreshRows(current)
        // The owned image file is kept during the undo window and only removed
        // (asynchronously) once undo can no longer restore the entry.
        showUndoSnackbar(current, getString(R.string.clipboard_deleted)) {
            restoreEntry(removed.first, removed.second)
            invalidateClipUi(removed.first.id)
            refreshRows(current)
        }
        scheduleImageCleanup(removed.first)
    }

    /** Deletes an owned image file only after the undo window has expired. */
    private fun scheduleImageCleanup(entry: ClipEntry) {
        if (entry.type != ClipType.IMAGE) return
        val context = lastContext ?: ui?.context ?: return
        handler.postDelayed({
            synchronized(this) {
                val stillPresent = history.any { it.id == entry.id }
                if (!stillPresent) {
                    ioExecutor.execute {
                        val privatePath = ClipboardImageStore.fileFor(context, entry.imagePath)?.absolutePath
                        val exportedPath = sharedImageExportStoragePath(entry)
                        val paths = listOfNotNull(privatePath, exportedPath).distinct()
                        if (paths.isNotEmpty()) {
                            val command = paths.joinToString(" ") { shellQuote(it) }
                                .let { "rm -f $it" }
                            ClipboardHook.runRootShell(context, command) { success, _ ->
                                if (!success) {
                                    XposedBridge.log("$TAG: expired image cleanup failed")
                                }
                            }
                        }
                    }
                }
            }
        }, UNDO_MS + 500L)
    }

    private fun sharedImageExportName(entry: ClipEntry): String =
        ClipboardImageStore.fileNameFor(
            "export-${entry.timestamp}-${Integer.toHexString(entry.id.hashCode())}",
            entry.mimeType
        )

    private fun sharedImageExportPath(entry: ClipEntry): String =
        "/storage/emulated/0/Download/EdgeXClipboard/${sharedImageExportName(entry)}"

    private fun sharedImageExportStoragePath(entry: ClipEntry): String =
        "/data/media/0/Download/EdgeXClipboard/${sharedImageExportName(entry)}"

    // ── Swipe actions ──────────────────────────────────────────────────────────

    private val swipePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swipeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /** Right swipe = delete, left swipe = pin/unpin. */
    private fun buildSwipeCallback(): ItemTouchHelper.SimpleCallback {
        return object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val rows = adapter?.currentList ?: return 0
                val position = viewHolder.adapterPosition
                if (position !in rows.indices) return 0
                return if (rows[position] is DisplayRow.Clip) {
                    makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
                } else {
                    0
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val current = currentUi() ?: return
                val rows = adapter?.currentList ?: return
                val position = viewHolder.adapterPosition
                if (position !in rows.indices) return
                val row = rows[position] as? DisplayRow.Clip ?: return
                when (direction) {
                    ItemTouchHelper.RIGHT -> deleteEntry(current, row.entry)
                    ItemTouchHelper.LEFT -> togglePinAndRefresh(current, row.entry)
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val current = currentUi()
                    val item = viewHolder.itemView
                    if (current != null && dX > 0f) {
                        swipePaint.color = current.palette.danger
                        c.drawRect(
                            item.left.toFloat(), item.top.toFloat(),
                            item.left + dX, item.bottom.toFloat(), swipePaint
                        )
                        drawSwipeContent(
                            c, current, R.drawable.ic_delete,
                            getString(R.string.clipboard_delete),
                            item.left + current.dp(18), item.top, item.bottom, alignEnd = false
                        )
                    } else if (current != null && dX < 0f) {
                        swipePaint.color = current.palette.accent
                        c.drawRect(
                            item.right + dX, item.top.toFloat(),
                            item.right.toFloat(), item.bottom.toFloat(), swipePaint
                        )
                        drawSwipeContent(
                            c, current, R.drawable.ic_pin_filled,
                            getString(R.string.clipboard_pin),
                            item.right - current.dp(18), item.top, item.bottom, alignEnd = true
                        )
                    }
                }
                super.onChildDraw(
                    c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive
                )
            }
        }
    }

    private fun drawSwipeContent(
        canvas: Canvas,
        current: OverlayUi,
        @DrawableRes iconRes: Int,
        label: String,
        anchorX: Int,
        top: Int,
        bottom: Int,
        alignEnd: Boolean
    ) {
        val icon = ModuleRes.getDrawable(iconRes, Color.WHITE) ?: return
        val size = current.dp(20)
        val gap = current.dp(8)
        swipeTextPaint.textSize = 13f * current.density
        val textWidth = swipeTextPaint.measureText(label).toInt()
        val totalWidth = size + gap + textWidth
        val startX = if (alignEnd) anchorX - totalWidth else anchorX
        val centerY = (top + bottom) / 2
        icon.setBounds(startX, centerY - size / 2, startX + size, centerY + size / 2)
        icon.draw(canvas)
        val baseline = centerY - (swipeTextPaint.descent() + swipeTextPaint.ascent()) / 2f
        canvas.drawText(label, (startX + size + gap).toFloat(), baseline, swipeTextPaint)
    }

    // ── Snackbar ───────────────────────────────────────────────────────────────

    private fun showUndoSnackbar(current: OverlayUi, message: String, onUndo: () -> Unit) {
        showSnackbar(current, message, getString(R.string.clipboard_undo), onUndo)
    }

    private fun showInfoSnackbar(current: OverlayUi, message: String) {
        showSnackbar(current, message, null, null)
    }

    private fun showSnackbar(
        current: OverlayUi,
        message: String,
        actionLabel: String?,
        onAction: (() -> Unit)?
    ) {
        current.snackbar?.let { current.transientLayer.removeView(it) }
        undoRunnable?.let { handler.removeCallbacks(it) }

        val bar = LinearLayout(current.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ClipboardUiKit.rounded(current.palette.snackbar, current.dp(14).toFloat())
            setPaddingRelative(current.dp(16), 0, current.dp(8), 0)
        }
        bar.addView(
            TextView(current.context).apply {
                text = message
                textSize = 14f
                setTextColor(current.palette.textPrimary)
                maxLines = 2
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (actionLabel != null && onAction != null) {
            bar.addView(
                TextView(current.context).apply {
                    text = actionLabel
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(current.palette.accent)
                    gravity = Gravity.CENTER
                    setPaddingRelative(current.dp(12), 0, current.dp(12), 0)
                    background = ClipboardUiKit.ripple(rippleColor(current.palette))
                    setOnClickListener {
                        undoRunnable?.let { handler.removeCallbacks(it) }
                        undoRunnable = null
                        current.transientLayer.removeView(bar)
                        current.snackbar = null
                        onAction()
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, current.dp(44))
            )
        }

        val sheetHeight = current.sheet.height.takeIf { it > 0 } ?: current.dp(320)
        current.transientLayer.addView(
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

        val duration = if (actionLabel != null) UNDO_MS else 3_000L
        val runnable = Runnable {
            undoRunnable = null
            current.snackbar?.let { view ->
                view.animate().alpha(0f).setDuration(160).withEndAction {
                    current.transientLayer.removeView(view)
                }.start()
            }
            current.snackbar = null
        }
        undoRunnable = runnable
        handler.postDelayed(runnable, duration)
    }

    // ── Backup / restore ───────────────────────────────────────────────────────

    private val backupDir = "/sdcard/Download/EdgeX"
    private val internalBackupDir =
        "/data/user_de/0/${BuildConfig.APPLICATION_ID}/files/clipboard_backups"
    private val legacyBackupDir = "/data/system/edgex/backups"
    private val restoreTempFile = "/data/system/edgex/restore_import.json"
    private val restoreTempDir = "/data/system/edgex/restore_import"

    private data class RestoreImageCopy(val source: File, val destination: File)
    private data class RestorePlan(
        val snapshot: ClipboardHistorySnapshot,
        val imageCopies: List<RestoreImageCopy>
    )

    private fun onBackupClicked(current: OverlayUi) {
        if (!backupInProgress.compareAndSet(false, true)) {
            showInfoSnackbar(current, getString(R.string.clipboard_backup_in_progress))
            return
        }
        // Bind before background work: the transient overlay can be dismissed
        // while the ZIP is being written, but that must not cancel the export.
        ensureShellService(current.context)
        val snapshot = ClipboardHistorySnapshot(
            entries = historySnapshot().filter { entry ->
                entry.type != ClipType.IMAGE ||
                    ClipboardImageStore.fileFor(current.context, entry.imagePath) != null
            },
            groups = groupsSnapshot(),
            hintDismissed = isHintDismissed()
        )
        if (snapshot.entries.isEmpty() && snapshot.groups.isEmpty()) {
            backupInProgress.set(false)
            showInfoSnackbar(current, getString(R.string.clipboard_backup_empty))
            return
        }
        val json = snapshotToJson(snapshot)
        val imagePaths = imageFilesFor(snapshot).values.map(File::getAbsolutePath)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val name = "edgex-clipboard-$stamp.zip"
        createBackupInApp(json, imagePaths, name) { created, internalPath ->
            if (!created || internalPath.isBlank()) {
                backupInProgress.set(false)
                if (currentUi() == null) unbindShellService()
                XposedBridge.log(
                    "$TAG: backup archive creation failed in app process" +
                        (internalPath.takeIf { it.isNotBlank() }?.let { ": ${it.take(160)}" } ?: "")
                )
                currentUi()?.let {
                    showInfoSnackbar(it, getString(R.string.clipboard_backup_failed))
                }
                return@createBackupInApp
            }
                val destination = "$backupDir/$name"
                runRoot(
                    "mkdir -p ${shellQuote(backupDir)} && " +
                        "cp -f ${shellQuote(internalPath)} ${shellQuote(destination)} && " +
                        "chmod 644 ${shellQuote(destination)} && test -s ${shellQuote(destination)}"
                ) { success, output ->
                    backupInProgress.set(false)
                    if (currentUi() == null) unbindShellService()
                    if (!success) {
                        XposedBridge.log(
                            "$TAG: backup Download export failed" +
                                (output.takeIf { it.isNotBlank() }?.let { ": ${it.take(160)}" } ?: "")
                        )
                    }
                    val visible = if (success) "Download/EdgeX/$name" else "internal storage"
                    currentUi()?.let {
                        showInfoSnackbar(it, getString(R.string.clipboard_backup_done, visible))
                    }
                }
        }
    }

    private fun onRestoreClicked(current: OverlayUi) {
        runRoot(
            "for d in ${shellQuote(backupDir)} ${shellQuote(internalBackupDir)} " +
                    "${shellQuote(legacyBackupDir)}; do " +
                    "[ -d \"\$d\" ] || continue; " +
                    "for f in \"\$d\"/edgex-clipboard-*.zip \"\$d\"/edgex-clipboard-*.json; do " +
                    "[ -f \"\$f\" ] && printf '%s\\t%s\\n' " +
                    "\"\$(stat -c %Y \"\$f\" 2>/dev/null || echo 0)\" \"\$f\"; " +
                    "done; done; true"
        ) { success, output ->
            val files = output.lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf('\t')
                    if (separator <= 0) return@mapNotNull null
                    val modified = line.substring(0, separator).toLongOrNull() ?: 0L
                    val path = line.substring(separator + 1).trim()
                    if (!path.endsWith(".zip") && !path.endsWith(".json")) null
                    else modified to path
                }
                .sortedByDescending { it.first }
                // Internal + Download copies share a name. Show only the newest
                // instance so the picker does not contain misleading duplicates.
                .distinctBy { it.second.substringAfterLast('/') }
                .map { it.second }
                .toList()
            val target = currentUi() ?: return@runRoot
            if (!success) {
                if (output.isNotBlank()) XposedBridge.log("$TAG: backup discovery failed: $output")
                showInfoSnackbar(target, getString(R.string.clipboard_restore_failed))
                return@runRoot
            }
            if (files.isEmpty()) {
                showInfoSnackbar(target, getString(R.string.clipboard_no_backups))
            } else {
                showRestorePicker(target, files)
            }
        }
    }

    private fun showRestorePicker(current: OverlayUi, files: List<String>) {
        val content = LinearLayout(current.context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(dialogTitle(current, getString(R.string.clipboard_restore_title)))
        content.addView(
            dialogMessage(current, getString(R.string.clipboard_restore_hint)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = current.dp(6) }
        )
        val list = LinearLayout(current.context).apply { orientation = LinearLayout.VERTICAL }
        files.take(40).forEach { path ->
            val name = path.substringAfterLast('/')
            val row = LinearLayout(current.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(current.dp(2), 0, current.dp(2), 0)
                background = ClipboardUiKit.ripple(rippleColor(current.palette))
                isClickable = true
                setOnClickListener {
                    closePopup(current)
                    confirmRestore(current, path)
                }
            }
            val icon = ImageView(current.context).apply {
                setImageDrawable(ModuleRes.getDrawable(R.drawable.ic_restart_alt, current.palette.accent))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            row.addView(icon, LinearLayout.LayoutParams(current.dp(18), current.dp(18)))
            row.addView(
                TextView(current.context).apply {
                    text = name
                    textSize = 14f
                    setTextColor(current.palette.textPrimary)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = current.dp(10) }
            )
            list.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, current.dp(44))
            )
        }
        val scroll = ScrollView(current.context).apply { addView(list) }
        content.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, current.dp(220)
            ).apply { topMargin = current.dp(8) }
        )
        content.addView(
            dialogButtons(current, getString(R.string.clipboard_done), current.palette.accent) { },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = current.dp(10) }
        )
        showDialogShell(current, content)
    }

    private fun confirmRestore(current: OverlayUi, path: String) {
        showConfirmDialog(
            current,
            getString(R.string.clipboard_restore_title),
            getString(R.string.clipboard_restore_confirm),
            getString(R.string.clipboard_restore),
            destructive = true
        ) {
            if (!restoreInProgress.compareAndSet(false, true)) {
                showInfoSnackbar(current, getString(R.string.clipboard_restore_in_progress))
                return@showConfirmDialog
            }
            val isZip = path.endsWith(".zip")
            val command = if (isZip) {
                "mkdir -p ${shellQuote(File(restoreTempFile).parent.orEmpty())} && " +
                        "rm -rf ${shellQuote(restoreTempDir)} && " +
                        "mkdir -p ${shellQuote(restoreTempDir)} && " +
                        "chown system:system ${shellQuote(restoreTempDir)} && " +
                        "chmod 700 ${shellQuote(restoreTempDir)} && " +
                        "cp -f ${shellQuote(path)} ${shellQuote(restoreTempFile)} && " +
                        "chmod 644 ${shellQuote(restoreTempFile)}"
            } else {
                "cp -f ${shellQuote(path)} ${shellQuote(restoreTempFile)} && " +
                        "chmod 644 ${shellQuote(restoreTempFile)}"
            }
            runRoot(command) { success, output ->
                if (!success) {
                    restoreInProgress.set(false)
                    if (!success) {
                        XposedBridge.log(
                            "$TAG: restore staging failed (zip=$isZip, " +
                                    "error=${output.takeIf { it.isNotBlank() }?.take(160)})"
                        )
                    }
                    currentUi()?.let { showInfoSnackbar(it, getString(R.string.clipboard_restore_failed)) }
                    if (currentUi() == null) unbindShellService()
                    return@runRoot
                }
                ioExecutor.execute {
                    if (isZip) {
                        val plan = readZipBackupPlan(current.context)
                        if (plan == null) {
                            restoreInProgress.set(false)
                            handler.post {
                                currentUi()?.let {
                                    showInfoSnackbar(it, getString(R.string.clipboard_restore_failed))
                                }
                                if (currentUi() == null) unbindShellService()
                            }
                            return@execute
                        }
                        val copyCommand = restoreImageCopyCommand(
                            plan, ClipboardImageStore.directory(current.context)
                        )
                        if (copyCommand.isEmpty()) {
                            handler.post { finishRestore(plan.snapshot) }
                        } else {
                            runRoot(copyCommand) { copied, output ->
                                if (copied) finishRestore(plan.snapshot) else {
                                    restoreInProgress.set(false)
                                    XposedBridge.log(
                                        "$TAG: restore image copy failed " +
                                                "(error=${output.takeIf { it.isNotBlank() }?.take(160)})"
                                    )
                                    currentUi()?.let {
                                        showInfoSnackbar(it, getString(R.string.clipboard_restore_failed))
                                    }
                                    if (currentUi() == null) unbindShellService()
                                }
                            }
                        }
                    } else {
                        val restored =
                        runCatching { File(restoreTempFile).readText() }.getOrNull()
                            ?.let { jsonToSnapshot(it) }
                        handler.post {
                            if (restored == null) {
                                restoreInProgress.set(false)
                                currentUi()?.let {
                                    showInfoSnackbar(it, getString(R.string.clipboard_restore_failed))
                                }
                                if (currentUi() == null) unbindShellService()
                            } else {
                                finishRestore(restored)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun finishRestore(snapshot: ClipboardHistorySnapshot) {
        ioExecutor.execute {
            val existing = historySnapshot()
            val imageDigests = HashMap<String, String>()
            (existing + snapshot.entries)
                .asSequence()
                .filter { it.type == ClipType.IMAGE }
                .mapNotNull { it.imagePath }
                .distinct()
                .forEach { path -> imageFingerprint(path)?.let { imageDigests[path] = it } }

            handler.post {
                val mergeResult = synchronized(this) {
                    ensureHistoryLoadedLocked()
                    val current = ClipboardHistorySnapshot(
                        entries = history.toList(),
                        groups = groups.toList(),
                        hintDismissed = hintDismissed
                    )
                    val result = ClipboardHistoryMerge.merge(current, snapshot) { entry ->
                        entry.imagePath?.let(imageDigests::get)
                    }
                    history.clear()
                    history.addAll(result.snapshot.entries)
                    groups.clear()
                    groups.addAll(result.snapshot.groups)
                    hintDismissed = result.snapshot.hintDismissed
                    trimLocked()
                    persistLocked()
                    clipUiCache.clear()
                    result
                }
                val retainedImagePaths = synchronized(this) {
                    history.asSequence()
                        .filter { it.type == ClipType.IMAGE }
                        .mapNotNull { it.imagePath }
                        .toHashSet()
                }
                snapshot.entries.asSequence()
                    .filter { it.type == ClipType.IMAGE }
                    .mapNotNull { it.imagePath }
                    .filter { it !in retainedImagePaths && File(it).name.startsWith("clip-restore-") }
                    .forEach { path -> runCatching { File(path).delete() } }

                restoreInProgress.set(false)
                val current = currentUi()
                if (current == null) {
                    unbindShellService()
                    return@post
                }
                refreshRows(current)
                showInfoSnackbar(
                    current,
                    getString(
                        R.string.clipboard_restore_done,
                        mergeResult.addedCount,
                        mergeResult.duplicateCount
                    )
                )
            }
        }
    }

    private fun imageFingerprint(path: String): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }.getOrNull()

    private fun restoreImageCopyCommand(plan: RestorePlan, destinationDirectory: File): String {
        if (plan.imageCopies.isEmpty()) return ""
        val commands = ArrayList<String>(plan.imageCopies.size * 5 + 2)
        val appFilesDir = destinationDirectory.parentFile?.absolutePath.orEmpty()
        commands += "app_uid=\$(stat -c %u ${shellQuote(appFilesDir)})"
        commands += "mkdir -p ${shellQuote(destinationDirectory.absolutePath)}"
        commands += "chown \$app_uid:1000 ${shellQuote(destinationDirectory.absolutePath)}"
        commands += "chmod 2710 ${shellQuote(destinationDirectory.absolutePath)}"
        plan.imageCopies.forEach { copy ->
            val temporary = File(copy.destination.parentFile, copy.destination.name + ".restore")
            commands += "cp -f ${shellQuote(copy.source.absolutePath)} ${shellQuote(temporary.absolutePath)}"
            commands += "chown \$app_uid:1000 ${shellQuote(temporary.absolutePath)}"
            commands += "chmod 640 ${shellQuote(temporary.absolutePath)}"
            commands += "mv -f ${shellQuote(temporary.absolutePath)} ${shellQuote(copy.destination.absolutePath)}"
        }
        return commands.joinToString(" && ")
    }

    /**
     * Extracts only known files into a system-owned staging folder. Entry names
     * and expanded byte counts are checked before a destination file is made.
     */
    private fun readZipBackupPlan(context: Context): RestorePlan? = runCatching {
        val archive = File(restoreTempFile)
        if (!archive.isFile) return@runCatching null
        val stagingDir = File(restoreTempDir)
        if (stagingDir.exists() && !stagingDir.deleteRecursively()) return@runCatching null
        if (!stagingDir.mkdirs()) return@runCatching null

        val manifestFile = File(stagingDir, "manifest.json")
        val imageDir = File(stagingDir, "images")
        val seenNames = HashSet<String>()
        val imageNamePattern = Regex("images/[A-Za-z0-9_-]{1,80}\\.[A-Za-z0-9]{1,8}")
        var entryCount = 0
        var totalBytes = 0L

        archive.inputStream().buffered().use { raw ->
            java.util.zip.ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > 64 || !seenNames.add(entry.name)) {
                        throw IOException("invalid backup entry count or duplicate")
                    }
                    if (entry.isDirectory) {
                        if (entry.name != "images/") throw IOException("unexpected backup directory")
                        zip.closeEntry()
                        continue
                    }

                    val outputFile: File
                    val entryLimit: Long
                    when {
                        entry.name == "manifest.json" -> {
                            outputFile = manifestFile
                            entryLimit = 16L * 1024 * 1024
                        }
                        imageNamePattern.matches(entry.name) -> {
                            outputFile = File(imageDir, entry.name.substringAfter("images/"))
                            entryLimit = ClipboardImageStore.MAX_BYTES.toLong()
                        }
                        else -> throw IOException("unexpected backup file")
                    }
                    outputFile.parentFile?.mkdirs()
                    var entryBytes = 0L
                    outputFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            entryBytes += count
                            totalBytes += count
                            if (entryBytes > entryLimit || totalBytes > 512L * 1024 * 1024) {
                                throw IOException("backup expanded size exceeds limit")
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                    if (entryBytes == 0L) {
                        outputFile.delete()
                        if (entry.name == "manifest.json") {
                            throw IOException("empty backup manifest")
                        }
                        XposedBridge.log("$TAG: skipped empty backup image ${entry.name}")
                    }
                    zip.closeEntry()
                }
            }
        }
        if (!manifestFile.isFile) return@runCatching null

        val snapshot = jsonToSnapshot(manifestFile.readText()) ?: return@runCatching null
        val destinationDir = ClipboardImageStore.directory(context)
        val retained = ArrayList<ClipEntry>(snapshot.entries.size)
        val copies = ArrayList<RestoreImageCopy>()
        val imageBasenamePattern = Regex("[A-Za-z0-9_-]{1,80}\\.[A-Za-z0-9]{1,8}")
        snapshot.entries.forEachIndexed { index, entry ->
            if (entry.type != ClipType.IMAGE) {
                retained += entry
                return@forEachIndexed
            }
            val name = entry.imagePath?.takeIf(imageBasenamePattern::matches)
                ?: return@forEachIndexed
            val source = File(imageDir, name)
            if (!source.isFile || !ClipboardImageStore.canStore(source.length()) ||
                !ClipboardImageStore.isSupported(entry.mimeType)
            ) return@forEachIndexed

            val target = File(
                destinationDir,
                ClipboardImageStore.fileNameFor("restore-${entry.timestamp}-$index", entry.mimeType)
            )
            copies += RestoreImageCopy(source, target)
            retained += entry.copy(imagePath = target.absolutePath, imageUri = null)
        }
        RestorePlan(snapshot.copy(entries = retained), copies)
    }.onFailure { error ->
        XposedBridge.log(
            "$TAG: backup parse failed: ${error.javaClass.simpleName}" +
                    (error.message?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        )
    }.getOrNull()

    private fun snapshotToJson(snapshot: ClipboardHistorySnapshot): String {
        val root = JSONObject()
        root.put("version", BACKUP_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("hintDismissed", snapshot.hintDismissed)
        val entries = JSONArray()
        snapshot.entries.forEach { entry ->
            val item = JSONObject()
            item.put("type", if (entry.type == ClipType.IMAGE) "image" else "text")
            if (entry.type == ClipType.IMAGE) {
                // Only metadata + owned file name travel in the manifest; the
                // image bytes live as separate archive entries.
                entry.imagePath?.let { path ->
                    item.put("imageFile", File(path).name)
                }
                entry.mimeType?.let { item.put("mime", it) }
                if (entry.isScreenshot) item.put("screenshot", true)
                if (entry.width > 0) item.put("width", entry.width)
                if (entry.height > 0) item.put("height", entry.height)
            } else {
                item.put("text", entry.text)
            }
            item.put("timestamp", entry.timestamp)
            item.put("pinned", entry.pinned)
            entry.group?.let { item.put("group", it) }
            item.put("sourceUid", entry.sourceUid)
            entries.put(item)
        }
        root.put("entries", entries)
        root.put("groups", JSONArray(snapshot.groups))
        return root.toString()
    }

    /** Owned image files referenced by a snapshot, keyed by archive entry name. */
    private fun imageFilesFor(snapshot: ClipboardHistorySnapshot): Map<String, File> {
        val files = LinkedHashMap<String, File>()
        snapshot.entries.forEach { entry ->
            if (entry.type != ClipType.IMAGE) return@forEach
            val file = File(entry.imagePath ?: return@forEach)
            if (file.isFile && file.length() in 1..ClipboardImageStore.MAX_BYTES.toLong()) {
                files[file.name] = file
            }
        }
        return files
    }

    /**
     * Parses and validates a backup before it is allowed to replace anything.
     * Rejects unsupported versions, truncates oversized payloads, caps total
     * entries and deduplicates by text. Video-length clip text is deliberately
     * rejected so a corrupt or hostile file cannot freeze system_server.
     */
    private fun jsonToSnapshot(json: String): ClipboardHistorySnapshot? = runCatching {
        if (json.length > MAX_BACKUP_CHARS) return@runCatching null
        val root = JSONObject(json)
        val version = root.optInt("version", 0)
        if (version != BACKUP_VERSION) return@runCatching null

        val entries = ArrayList<ClipEntry>()
        val seen = HashSet<String>()
        val array = root.optJSONArray("entries") ?: JSONArray()
        for (index in 0 until array.length()) {
            if (entries.size >= MAX_PINNED + MAX_HISTORY) break
            val item = array.optJSONObject(index) ?: continue
            val isImage = item.optString("type", "text") == "image"
            val entry: ClipEntry? = if (isImage) {
                val file = item.optString("imageFile", "")
                    .takeIf { it.isNotEmpty() && !it.contains("..") && !it.contains('/') }
                val mime = item.optString("mime", "").takeIf { it.isNotEmpty() }
                if (file == null || mime == null || !ClipboardImageStore.isSupported(mime)) {
                    null
                } else {
                    ClipEntry(
                        type = ClipType.IMAGE,
                        imagePath = file,
                        mimeType = mime,
                        width = item.optInt("width", 0),
                        height = item.optInt("height", 0),
                        isScreenshot = item.optBoolean("screenshot", false)
                    )
                }
            } else {
                val text = item.optString("text", "")
                    .take(MAX_CLIP_CHARS)
                    .takeIf { it.isNotEmpty() } ?: continue
                ClipEntry(text = text)
            }
            if (entry == null) continue
            val identity = if (isImage) "i:${item.optString("imageFile")}" else "t:${entry.text}"
            if (!seen.add(identity)) continue
            entries += entry.copy(
                timestamp = item.optLong("timestamp", 0L),
                pinned = item.optBoolean("pinned", false),
                group = item.optString("group", "").take(MAX_GROUP_NAME_CHARS)
                    .takeIf { it.isNotEmpty() },
                sourceUid = item.optInt("sourceUid", -1)
            )
        }
        val knownGroups = entries.mapNotNull { it.group }.toHashSet()
        val groups = ArrayList<String>()
        root.optJSONArray("groups")?.let { array ->
            for (index in 0 until array.length()) {
                if (groups.size >= MAX_GROUPS) break
                val name = array.optString(index).take(MAX_GROUP_NAME_CHARS)
                    .takeIf { it.isNotEmpty() } ?: continue
                if (groups.any { it.equals(name, ignoreCase = true) }) continue
                groups += name
            }
        }
        // Clips referencing a group that was not imported return to history.
        for (i in entries.indices) {
            val group = entries[i].group
            if (group != null && group !in groups && group !in knownGroups) {
                entries[i] = entries[i].copy(group = null)
            }
        }
        ClipboardHistorySnapshot(
            entries = entries,
            groups = groups,
            hintDismissed = root.optBoolean("hintDismissed", false)
        )
    }.getOrNull()

    private const val BACKUP_VERSION = 2
    private const val MAX_BACKUP_CHARS = 4_000_000
    private const val MAX_CLIP_CHARS = 100_000
    private const val MAX_GROUPS = 100
    private const val MAX_GROUP_NAME_CHARS = 80

    private fun ensureShellService(context: Context) {
        if (shellBound) return
        shellContext = context
        val intent = Intent().apply {
            component = ComponentName(
                BuildConfig.APPLICATION_ID,
                "${BuildConfig.APPLICATION_ID}.config.ShellExecutorService"
            )
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        shellBound = runCatching {
            context.bindService(intent, shellConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!shellBound) failPendingShellCalls()
    }

    private fun unbindShellService() {
        val context = shellContext ?: return
        if (pendingShellCalls.isNotEmpty() || activeShellCalls > 0 ||
            backupInProgress.get() || restoreInProgress.get()
        ) return
        if (shellBound) {
            runCatching { context.unbindService(shellConnection) }
        }
        shellExecutor = null
        shellBound = false
        shellContext = null
        shellBindTimeout?.let(handler::removeCallbacks)
        shellBindTimeout = null
    }

    private fun withShellExecutor(
        context: Context,
        unavailable: () -> Unit,
        operation: (IShellExecutor) -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { withShellExecutor(context, unavailable, operation) }
            return
        }
        val ready = shellExecutor
        if (ready != null) {
            operation(ready)
            return
        }
        pendingShellCalls.addLast(PendingShellCall(operation, unavailable))
        ensureShellService(context)
        if (shellBindTimeout == null && pendingShellCalls.isNotEmpty()) {
            shellBindTimeout = Runnable {
                shellBindTimeout = null
                failPendingShellCalls()
                unbindShellService()
            }.also { handler.postDelayed(it, 12_000L) }
        }
    }

    private fun failPendingShellCalls() {
        while (pendingShellCalls.isNotEmpty()) pendingShellCalls.removeFirst().unavailable()
    }

    private fun finishShellCall() {
        activeShellCalls = (activeShellCalls - 1).coerceAtLeast(0)
        if (ui == null) unbindShellService()
    }

    /** Builds the archive under the app UID, which owns the private image files. */
    private fun createBackupInApp(
        manifestJson: String,
        imagePaths: List<String>,
        archiveName: String,
        onDone: (Boolean, String) -> Unit
    ) {
        val context = shellContext ?: ui?.context
        if (context == null) {
            onDone(false, "service context unavailable")
            return
        }
        withShellExecutor(context, { onDone(false, "backup service unavailable") }) { executor ->
            activeShellCalls++
            val finished = AtomicBoolean(false)
            lateinit var timeout: Runnable
            fun finish(success: Boolean, output: String) {
                if (!finished.compareAndSet(false, true)) return
                handler.removeCallbacks(timeout)
                finishShellCall()
                onDone(success, output)
            }
            timeout = Runnable { finish(false, "backup service timed out") }
            handler.postDelayed(timeout, 30_000L)
            try {
            executor.createClipboardBackup(
                manifestJson,
                imagePaths,
                archiveName,
                object : IShellCallback.Stub() {
                    override fun onResult(success: Boolean, output: String?) {
                        handler.post { finish(success, output.orEmpty()) }
                    }
                }
            )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: backup service call failed: ${t.javaClass.simpleName}")
                finish(false, t.message.orEmpty())
            }
        }
    }

    /** Runs a root shell command through the app's ShellExecutorService. */
    private fun runRoot(command: String, onDone: (Boolean, String) -> Unit) {
        val context = shellContext ?: ui?.context
        if (context == null) {
            onDone(false, "service context unavailable")
            return
        }
        withShellExecutor(context, { onDone(false, "service unavailable") }) { executor ->
            activeShellCalls++
            val finished = AtomicBoolean(false)
            lateinit var timeout: Runnable
            fun finish(success: Boolean, output: String) {
                if (!finished.compareAndSet(false, true)) return
                handler.removeCallbacks(timeout)
                finishShellCall()
                onDone(success, output)
            }
            timeout = Runnable { finish(false, "root command timed out") }
            handler.postDelayed(timeout, 30_000L)
            try {
            executor.execute(command, true, object : IShellCallback.Stub() {
                override fun onResult(success: Boolean, output: String?) {
                    handler.post { finish(success, output.orEmpty()) }
                }
            })
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: root shell failed: ${t.message}")
                finish(false, t.message.orEmpty())
            }
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    // ── Menus ──────────────────────────────────────────────────────────────────

    private class MenuEntry(
        val label: String,
        @DrawableRes val iconRes: Int? = null,
        val tint: Int? = null,
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
                    dividerAfter = true
                ) {
                    showConfirmDialog(
                        current,
                        getString(R.string.clipboard_clear_everything),
                        getString(R.string.clipboard_clear_everything_message),
                        getString(R.string.clipboard_clear_everything),
                        destructive = true
                    ) {
                        clearAll()
                        clipUiCache.clear()
                        refreshRows(current)
                    }
                },
                MenuEntry(
                    label = getString(R.string.clipboard_backup),
                    iconRes = R.drawable.ic_save
                ) { onBackupClicked(current) },
                MenuEntry(
                    label = getString(R.string.clipboard_restore),
                    iconRes = R.drawable.ic_restart_alt
                ) { onRestoreClicked(current) }
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
                    label = getString(R.string.clipboard_clear_group),
                    iconRes = R.drawable.ic_clear_recent,
                    tint = current.palette.danger,
                    dividerAfter = true
                ) { onClearGroupClicked(current, name) },
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
                        refreshRows(current)
                    }
                }
            )
        )
    }

    private fun showItemMenu(current: OverlayUi, anchor: View, entry: ClipEntry) {
        val isImage = entry.type == ClipType.IMAGE
        val items = ArrayList<MenuEntry>(9)
        val hasOriginalLocation = isImage && originalDocumentUri(current.context, entry) != null
        items += MenuEntry(
            label = getString(R.string.clipboard_paste),
            iconRes = R.drawable.ic_paste
        ) { if (isImage) pasteImageEntry(entry) else pasteEntry(entry.text) }
        items += MenuEntry(
            label = getString(R.string.clipboard_copy),
            iconRes = R.drawable.ic_content_copy
        ) { if (isImage) copyImageEntry(current, entry) else copyEntry(current, entry.text) }
        items += MenuEntry(
            label = getString(
                if (entry.pinned) R.string.clipboard_unpin else R.string.clipboard_pin
            ),
            iconRes = R.drawable.ic_pin_filled,
            tint = if (entry.pinned) current.palette.accent else null
        ) { togglePinAndRefresh(current, entry) }
        items += MenuEntry(
            label = getString(R.string.clipboard_move_to_group),
            iconRes = R.drawable.ic_folder
        ) { showGroupPicker(current, anchor, entry) }
        if (isImage) {
            items += MenuEntry(
                label = getString(R.string.clipboard_open_image),
                iconRes = R.drawable.ic_image
            ) { openImageEntry(current.context, entry) }
            if (hasOriginalLocation) {
                items += MenuEntry(
                    label = getString(R.string.clipboard_open_folder),
                    iconRes = R.drawable.ic_folder
                ) { openImageFolder(current.context, entry) }
            }
            if (hasOriginalLocation && isMtManagerImageViewerAvailable(current.context)) {
                items += MenuEntry(
                    label = getString(R.string.clipboard_open_in_mt_manager),
                    iconRes = R.drawable.ic_image
                ) {
                    if (!openOriginalImageInMtManager(current.context, entry)) {
                        Toast.makeText(
                            current.context,
                            getString(R.string.clipboard_image_missing),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        // Images have no editable text body, so Edit applies to text clips only.
        if (!isImage) {
            items += MenuEntry(
                label = getString(R.string.clipboard_edit),
                iconRes = R.drawable.ic_edit,
                dividerAfter = true
            ) { showClipEditor(current, entry) }
        }
        items += MenuEntry(
            label = getString(R.string.clipboard_share),
            iconRes = R.drawable.ic_share,
            dividerAfter = true
        ) { shareEntry(current.context, entry) }
        items += MenuEntry(
            label = getString(R.string.clipboard_delete),
            iconRes = R.drawable.ic_delete,
            tint = current.palette.danger,
            dividerAfter = false
        ) { deleteEntry(current, entry) }
        showMenu(current, anchor, items)
    }

    /** Internal group picker: same transient layer, no popup window. */
    private fun showGroupPicker(current: OverlayUi, anchor: View, entry: ClipEntry) {
        val items = ArrayList<MenuEntry>()
        if (entry.group != null) {
            items += MenuEntry(
                label = getString(R.string.clipboard_remove_from_group),
                iconRes = R.drawable.ic_close,
                tint = current.palette.danger
            ) {
                moveToGroup(entry.id, null)
                refreshRows(current)
            }
        }
        groupsSnapshot().forEach { name ->
            val isCurrent = entry.group == name
            items += MenuEntry(
                label = name,
                iconRes = if (isCurrent) R.drawable.ic_check else R.drawable.ic_folder,
                tint = if (isCurrent) current.palette.accent else null
            ) {
                moveToGroup(entry.id, name)
                refreshRows(current)
            }
        }
        items += MenuEntry(
            label = getString(R.string.clipboard_new_group),
            iconRes = R.drawable.ic_add
        ) { showGroupNameDialog(current, null, moveEntryText = entry.id) }
        showMenu(current, anchor, items, title = getString(R.string.clipboard_move_to_group))
    }

    private fun showMenu(
        current: OverlayUi,
        anchor: View,
        items: List<MenuEntry>,
        title: String? = null
    ) {
        closePopup(current)
        val context = current.context

        val wrapper = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { closePopup(current) }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ClipboardUiKit.rounded(
                current.palette.raised, current.dp(14).toFloat(),
                current.dp(1), current.palette.border
            )
            setPaddingRelative(current.dp(6), current.dp(6), current.dp(6), current.dp(6))
            elevation = current.dp(8).toFloat()
        }
        if (title != null) {
            card.addView(
                TextView(context).apply {
                    text = title
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(current.palette.textSecondary)
                    setPaddingRelative(current.dp(12), current.dp(8), current.dp(12), current.dp(4))
                }
            )
        }
        for (entry in items) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(current.dp(10), 0, current.dp(10), 0)
                background = ClipboardUiKit.ripple(rippleColor(current.palette))
                isClickable = true
                setOnClickListener {
                    closePopup(current)
                    entry.action?.invoke()
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
            card.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, current.dp(48))
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
        val minY = (current.sheetTop + current.dp(8)).coerceAtLeast(current.dp(8))
        val maxY = (rootHeight - menuHeight - current.dp(8)).coerceAtLeast(minY)
        val below = location[1] + anchor.height + current.dp(4)
        val preferredY = if (below + menuHeight <= rootHeight - current.dp(8)) {
            below
        } else {
            location[1] - menuHeight - current.dp(4)
        }
        val y = preferredY.coerceIn(minY, maxY)
        wrapper.addView(
            card,
            FrameLayout.LayoutParams(menuWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = x
                topMargin = y
            }
        )
        current.transientLayer.addView(
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
        current.onBackPressed = null
        current.editorLayoutListener?.let { listener ->
            runCatching { current.root.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
        }
        current.editorLayoutListener = null
        current.transientLayer.removeView(popup)
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
        current.transientLayer.addView(
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
        cancelLabel: String? = null,
        onCancel: (() -> Unit)? = null,
        onConfirm: () -> Unit
    ): View {
        val row = LinearLayout(current.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(
            TextView(current.context).apply {
                text = cancelLabel ?: getString(R.string.clipboard_cancel)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(current.palette.textSecondary)
                gravity = Gravity.CENTER
                setPaddingRelative(current.dp(16), 0, current.dp(16), 0)
                background = ClipboardUiKit.ripple(rippleColor(current.palette))
                setOnClickListener {
                    closePopup(current)
                    onCancel?.invoke()
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, current.dp(44))
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
        cancelLabel: String? = null,
        onCancel: (() -> Unit)? = null,
        onConfirm: () -> Unit
    ) {
        val content = LinearLayout(current.context).apply { orientation = LinearLayout.VERTICAL }
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
                cancelLabel,
                onCancel,
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
        val content = LinearLayout(current.context).apply { orientation = LinearLayout.VERTICAL }
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
                    val ok = if (existingName == null) {
                        createGroup(name)
                    } else {
                        renameGroup(existingName, name)
                    }
                    if (ok) {
                        if (moveEntryText != null) moveToGroup(moveEntryText, name)
                        refreshRows(current)
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

    /**
     * Long-press surface: a large, selectable, editable view of one clip.
     * Lives in the transient layer so it renders above the sheet, and offers
     * explicit Cut/Copy/Select-all/Save because the system text action mode is
     * a separate window and is unreliable inside a system overlay.
     */
    /**
     * Internal focused editor for one clip. Lives in the transient layer (never
     * a Dialog/PopupWindow/Activity), keeps the clipboard dimmed behind it, and
     * adapts its height so Save/Cancel stay visible when the IME is open.
     */
    private fun showClipEditor(
        current: OverlayUi,
        entry: ClipEntry,
        initialText: String = entry.text
    ) {
        closePopup(current)
        val context = current.context
        val kind = ClipboardUiKit.classify(initialText)

        val wrapper = FrameLayout(context).apply {
            setBackgroundColor(current.palette.scrim)
            isClickable = true
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ClipboardUiKit.rounded(
                current.palette.raised, current.dp(18).toFloat(),
                current.dp(1), current.palette.border
            )
            setPaddingRelative(current.dp(16), current.dp(14), current.dp(16), current.dp(12))
            isClickable = true
            // Tapping the panel (not the field) hides the IME but keeps the
            // editor open and preserves the draft.
            setOnClickListener { hideKeyboard(current) }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(context).apply {
                text = getString(R.string.clipboard_edit_title)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(current.palette.textPrimary)
                maxLines = 1
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val expand = TextView(context).apply {
            text = getString(R.string.clipboard_expand)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(current.palette.accent)
            gravity = Gravity.CENTER
            setPaddingRelative(current.dp(10), 0, current.dp(10), 0)
            background = ClipboardUiKit.ripple(rippleColor(current.palette))
        }
        header.addView(expand, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, current.dp(36)
        ))
        val close = actionIcon(
            context, R.drawable.ic_close, current.palette, current.palette.iconMuted,
            current.dp(36), current.dp(36), getString(R.string.clipboard_cancel)
        ) { }
        header.addView(close)
        card.addView(header)

        val input = EditText(context).apply {
            setText(initialText)
            setSelection(text.length)
            setTextColor(current.palette.textPrimary)
            textSize = 14.5f
            if (kind == ClipboardUiKit.ClipKind.CODE) typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = null
            setPaddingRelative(current.dp(12), current.dp(12), current.dp(12), current.dp(12))
            setTextIsSelectable(true)
            isFocusable = true
            isFocusableInTouchMode = true
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                textCursorDrawable = ClipboardUiKit.rounded(
                    current.palette.accent, current.dp(1).toFloat()
                )
            }
        }
        val field = FrameLayout(context).apply {
            background = ClipboardUiKit.rounded(
                current.palette.card, current.dp(12).toFloat(),
                current.dp(1), current.palette.border
            )
        }
        val scroll = ScrollView(context).apply {
            addView(
                input,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        field.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        card.addView(
            field,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, current.dp(180)
            ).apply { topMargin = current.dp(10) }
        )

        val count = TextView(context).apply {
            textSize = 11.5f
            setTextColor(current.palette.textSecondary)
            setPaddingRelative(current.dp(2), current.dp(6), 0, 0)
        }
        card.addView(count, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun actionButton(label: String, tint: Int, onClick: () -> Unit): TextView =
            TextView(context).apply {
                text = label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(tint)
                gravity = Gravity.CENTER
                setPaddingRelative(current.dp(16), 0, current.dp(16), 0)
                background = ClipboardUiKit.ripple(rippleColor(current.palette))
                setOnClickListener { onClick() }
            }
        val cancel = actionButton(
            getString(R.string.clipboard_cancel), current.palette.textSecondary
        ) { requestEditorDismiss(current, entry, input.text.toString()) }
        val save = actionButton(
            getString(R.string.clipboard_save), current.palette.accent
        ) {
            val newText = input.text.toString()
            if (newText.isBlank() || newText == entry.text) return@actionButton
            if (updateEntryText(entry.id, newText)) {
                invalidateClipUi(entry.id)
                closePopup(current)
                refreshRows(current)
            }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, current.dp(44)
        ))
        actions.addView(save, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, current.dp(44)
        ).apply { marginStart = current.dp(8) })
        card.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = current.dp(10) }
        )
        wrapper.addView(card)

        var expanded = false
        fun applySizes() {
            val rootWidth = current.root.width.takeIf { it > 0 }
                ?: current.context.resources.displayMetrics.widthPixels
            val available = current.root.height.takeIf { it > 0 }
                ?: current.context.resources.displayMetrics.heightPixels
            val cardWidth = (rootWidth * 0.88f).toInt().coerceAtLeast(current.dp(240))
            val normalField = minOf((available * 0.42f).toInt(), current.dp(300))
            val expandedField = (available - current.dp(200)).coerceAtLeast(current.dp(160))
            val fieldHeight = (if (expanded) expandedField else normalField)
                .coerceAtLeast(current.dp(120))
            (card.layoutParams as FrameLayout.LayoutParams).apply {
                width = cardWidth
                height = FrameLayout.LayoutParams.WRAP_CONTENT
                gravity = Gravity.CENTER
            }
            card.requestLayout()
            (field.layoutParams as LinearLayout.LayoutParams).height = fieldHeight
            field.requestLayout()
        }

        val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { applySizes() }
        current.editorLayoutListener = layoutListener
        current.root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)

        expand.setOnClickListener {
            expanded = !expanded
            expand.text = getString(
                if (expanded) R.string.clipboard_collapse else R.string.clipboard_expand
            )
            applySizes()
        }
        close.setOnClickListener { requestEditorDismiss(current, entry, input.text.toString()) }
        wrapper.setOnClickListener { requestEditorDismiss(current, entry, input.text.toString()) }

        fun refreshState() {
            val text = input.text?.toString().orEmpty()
            count.text = getString(R.string.clipboard_chars, text.length)
            val canSave = text.isNotBlank() && text != entry.text
            save.isEnabled = canSave
            save.alpha = if (canSave) 1f else 0.4f
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshState()
        })
        refreshState()

        current.transientLayer.addView(
            wrapper,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        current.popup = wrapper
        current.onBackPressed = {
            if (current.keyboardVisible) {
                hideKeyboard(current)
            } else {
                requestEditorDismiss(current, entry, input.text.toString())
            }
            true
        }
        applySizes()

        card.alpha = 0f
        card.scaleX = 0.97f
        card.scaleY = 0.97f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
    }

    /** Prompts before discarding unsaved editor changes. */
    private fun requestEditorDismiss(current: OverlayUi, entry: ClipEntry, currentText: String) {
        if (currentText == entry.text) {
            closePopup(current)
            return
        }
        showConfirmDialog(
            current,
            getString(R.string.clipboard_discard_title),
            getString(R.string.clipboard_discard_message),
            getString(R.string.clipboard_discard),
            destructive = true,
            cancelLabel = getString(R.string.clipboard_keep_editing),
            onCancel = { showClipEditor(current, entry, currentText) }
        ) {
            // Discard: the confirm dialog already closed the editor popup.
        }
    }


    private fun showManageGroupsDialog(current: OverlayUi) {
        val content = LinearLayout(current.context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(dialogTitle(current, getString(R.string.clipboard_manage_groups)))
        val list = LinearLayout(current.context).apply { orientation = LinearLayout.VERTICAL }
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
                        refreshRows(current)
                    }
                }
                row.addView(delete)
                list.addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, current.dp(46))
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
     * Prefer cursor-aware accessibility insertion. Use clipboard paste only
     * when the focused editor rejects that action, then key injection as a
     * fallback for editors missing from the accessibility tree.
     */
    private fun pasteText(context: Context, text: String, onComplete: (Boolean) -> Unit) {
        UniversalCopyManager.injectIntoFocusedField(
            context,
            text,
            prepareClipboardFallback = { setTextClipboardForPaste(context, text) }
        ) { inserted ->
            if (inserted) {
                onComplete(true)
            } else {
                handler.post {
                    val typed = injectText(context, text)
                    onComplete(typed || pasteTextViaClipboardKey(context, text))
                }
            }
        }
    }

    private fun setTextClipboardForPaste(context: Context, text: String): Boolean = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        markSkipNextClip(text)
        clipboard.setPrimaryClip(ClipData.newPlainText("EdgeX", text))
        true
    }.getOrDefault(false)

    /** Last-resort path for editors absent from the accessibility tree. */
    private fun pasteTextViaClipboardKey(context: Context, text: String): Boolean {
        if (!setTextClipboardForPaste(context, text)) return false
        return try {
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) ?: return false
            val injectMethod = inputManager.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            val now = android.os.SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PASTE, 0)
            val up = KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PASTE, 0)
            val downAccepted = injectMethod.invoke(inputManager, down, 0) as? Boolean ?: false
            val upAccepted = injectMethod.invoke(inputManager, up, 0) as? Boolean ?: false
            downAccepted && upAccepted
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: clipboard paste key fallback failed: ${t.javaClass.simpleName}")
            false
        }
    }

    /**
     * Inject [text] directly into the focused input field via key events,
     * mirroring XPE's approach (y0.i0 / KeyCharacterMap.getEvents).
     */
    private fun injectText(context: Context, text: String): Boolean {
        if (text.isEmpty()) return false
        try {
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) ?: return false
            val injectMethod = inputManager.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )

            val charMap = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            val events = charMap.getEvents(text.toCharArray())

            if (events != null) {
                var acceptedAny = false
                var acceptedAll = true
                for (event in events) {
                    val timed = android.view.KeyEvent.changeTimeRepeat(
                        event, android.os.SystemClock.uptimeMillis(), 0
                    )
                    val accepted = injectMethod.invoke(inputManager, timed, 0) as? Boolean ?: false
                    acceptedAny = acceptedAny || accepted
                    acceptedAll = acceptedAll && accepted
                }
                return acceptedAll || acceptedAny
            } else {
                @Suppress("DEPRECATION")
                val charEvent = android.view.KeyEvent(
                    android.os.SystemClock.uptimeMillis(),
                    text,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0
                )
                return injectMethod.invoke(inputManager, charEvent, 0) as? Boolean ?: false
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: injectText failed: ${t.javaClass.simpleName}")
            return false
        }
    }
}
