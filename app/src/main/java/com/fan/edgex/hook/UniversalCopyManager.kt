package com.fan.edgex.hook

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object UniversalCopyManager {
    private const val TAG = "EdgeX"
    private const val STATE_ENABLED = 1
    private const val RETRY_COUNT = 3
    private const val RETRY_DELAY_MS = 150L

    internal enum class ImagePasteMode { DIRECT, PATH, MANUAL }

    /** Only terminal fields receive a path; other editable fields use IME rich content. */
    internal fun imagePasteMode(
        packageName: String?,
        @Suppress("UNUSED_PARAMETER") className: String?,
        editable: Boolean,
        password: Boolean
    ): ImagePasteMode {
        if (password) return ImagePasteMode.MANUAL
        if (packageName == "com.termux") return ImagePasteMode.PATH
        if (!editable) return ImagePasteMode.MANUAL
        return ImagePasteMode.DIRECT
    }

    enum class CollectStatus {
        FOUND,
        NO_TEXT,
        UNAVAILABLE
    }

    data class TextBlock(
        val text: String,
        val bounds: Rect
    )

    data class CollectResult(
        val status: CollectStatus,
        val blocks: List<TextBlock> = emptyList()
    )

    @Volatile
    private var hooksInstalled = false

    @Volatile
    private var fakeAccessibilityEnabled = false

    @Volatile
    private var service: BridgeAccessibilityService? = null

    fun installHooks(classLoader: ClassLoader) {
        if (hooksInstalled) return
        synchronized(this) {
            if (hooksInstalled) return
            hookClientState(classLoader, "com.android.server.accessibility.AccessibilityUserState", "getClientStateLocked")
            hookClientState(classLoader, "com.android.server.accessibility.AccessibilityManagerService\$UserState", "getClientState")
            hooksInstalled = true
        }
    }

    fun collectAllTexts(context: Context, onResult: (CollectResult) -> Unit) {
        val bridge = getOrCreateService(context)
        if (bridge == null) {
            onResult(CollectResult(CollectStatus.UNAVAILABLE))
            return
        }
        bridge.collectAll(onResult)
    }

    /**
     * Insert [text] at the cursor of the currently focused input field via
     * AccessibilityNodeInfo ACTION_SET_TEXT. Used for Unicode (e.g. Chinese)
     * where KeyCharacterMap-based key-event injection is not viable.
     */
    fun injectIntoFocusedField(
        context: Context,
        text: String,
        prepareClipboardFallback: () -> Boolean,
        onComplete: (Boolean) -> Unit
    ) {
        val bridge = getOrCreateService(context)
        if (bridge == null) {
            onComplete(false)
            return
        }
        bridge.injectIntoFocused(text, prepareClipboardFallback, onComplete)
    }

    /**
     * Deliver image content through the active IME's InputConnection. The IME
     * checks the focused editor's MIME types and uses Android's URI-token path.
     */
    fun deliverImageIntoFocusedField(
        context: Context,
        commitImage: (String?, (Boolean) -> Unit) -> Unit,
        onComplete: (packageName: String?, hasFocusedField: Boolean, pasted: Boolean) -> Unit
    ) {
        val bridge = getOrCreateService(context)
        if (bridge == null) {
            onComplete(null, false, false)
            return
        }
        bridge.deliverImageIntoFocused(commitImage, onComplete)
    }

    private fun getOrCreateService(context: Context): BridgeAccessibilityService? {
        service?.let { return it }
        return synchronized(this) {
            service?.let { return@synchronized it }
            try {
                BridgeAccessibilityService(context.applicationContext).also {
                    service = it
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: Universal copy init failed: ${t.message}")
                null
            }
        }
    }

    private fun hookClientState(classLoader: ClassLoader, className: String, methodName: String) {
        try {
            val targetClass = XposedHelpers.findClass(className, classLoader)
            val hookedAny = targetClass.declaredMethods
                .filter { it.name == methodName }
                .map { method ->
                    runCatching {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!fakeAccessibilityEnabled) return
                                for (index in param.args.indices) {
                                    if (param.args[index] is Boolean) {
                                        param.args[index] = true
                                        break
                                    }
                                }
                            }

                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!fakeAccessibilityEnabled) return
                                val result = param.result as? Int ?: return
                                if ((result and STATE_ENABLED) == 0) {
                                    param.result = result or STATE_ENABLED
                                }
                            }
                        })
                    }.isSuccess
                }
                .any { it }

            if (hookedAny) {
                XposedBridge.log("$TAG: Universal copy hooked $className#$methodName")
            }
        } catch (_: Throwable) {
        }
    }

    private class BridgeAccessibilityService(context: Context) : AccessibilityService() {
        private val handler = Handler(Looper.getMainLooper())
        private val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        private val managerService = resolveManagerService(accessibilityManager)

        init {
            val connectionId = resolveConnectionId(managerService)
            XposedHelpers.setIntField(this, "mConnectionId", connectionId)
            attachBaseContext(context)
        }

        fun collectAll(onResult: (CollectResult) -> Unit) {
            try {
                if (!accessibilityManager.isEnabled) {
                    setAccessibilityEnabled(true)
                    retryCollect(RETRY_COUNT, true, onResult)
                    return
                }
                finishCollect(queryAllTexts(), false, onResult)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: Universal copy failed: ${t.message}")
                onResult(CollectResult(CollectStatus.UNAVAILABLE))
            }
        }

        fun injectIntoFocused(
            text: String,
            prepareClipboardFallback: () -> Boolean,
            onComplete: (Boolean) -> Unit
        ) {
            try {
                if (!accessibilityManager.isEnabled) {
                    setAccessibilityEnabled(true)
                    handler.postDelayed({
                        runInjection(text, disableAfter = true, prepareClipboardFallback, onComplete)
                    }, RETRY_DELAY_MS)
                    return
                }
                runInjection(text, disableAfter = false, prepareClipboardFallback, onComplete)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: text injection failed: ${t.message}")
                onComplete(false)
            }
        }

        fun deliverImageIntoFocused(
            commitImage: (String?, (Boolean) -> Unit) -> Unit,
            onComplete: (String?, Boolean, Boolean) -> Unit
        ) {
            try {
                if (!accessibilityManager.isEnabled) {
                    setAccessibilityEnabled(true)
                    handler.postDelayed({
                        runImageDelivery(true, commitImage, onComplete)
                    }, RETRY_DELAY_MS)
                    return
                }
                runImageDelivery(disableAfter = false, commitImage, onComplete)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: image paste setup failed: ${t.javaClass.simpleName}")
                onComplete(null, false, false)
            }
        }

        private fun runImageDelivery(
            disableAfter: Boolean,
            commitImage: (String?, (Boolean) -> Unit) -> Unit,
            onComplete: (String?, Boolean, Boolean) -> Unit
        ) {
            var packageName: String? = null
            var hasFocusedField = false
            var pasted = false
            var completed = false
            var timeout: Runnable? = null
            fun complete() {
                if (completed) return
                completed = true
                timeout?.let(handler::removeCallbacks)
                if (disableAfter) setAccessibilityEnabled(false)
                onComplete(packageName, hasFocusedField, pasted)
            }
            try {
                val root = try { getRootInActiveWindow() } catch (_: Throwable) { null }
                val focused = try {
                    root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                } catch (_: Throwable) { null }
                if (focused != null) {
                    packageName = focused.packageName?.toString()
                        ?: root?.packageName?.toString()
                    when (
                        imagePasteMode(
                            packageName,
                            focused.className?.toString(),
                            focused.isEditable,
                            focused.isPassword
                        )
                    ) {
                        ImagePasteMode.PATH -> {
                            hasFocusedField = true
                            complete()
                            return
                        }
                        ImagePasteMode.MANUAL -> {
                            complete()
                            return
                        }
                        ImagePasteMode.DIRECT -> hasFocusedField = true
                    }
                    commitImage(packageName) { accepted ->
                        handler.post {
                            if (completed) return@post
                            pasted = accepted
                            complete()
                        }
                    }
                    timeout = Runnable { complete() }
                    handler.postDelayed(timeout!!, 2_500L)
                    return
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: image paste action failed: ${t.javaClass.simpleName}")
            }
            complete()
        }

        private fun runInjection(
            text: String,
            disableAfter: Boolean,
            prepareClipboardFallback: () -> Boolean,
            onComplete: (Boolean) -> Unit
        ) {
            var success = false
            try {
                val root = try { getRootInActiveWindow() } catch (_: Throwable) { null }
                val focused = try {
                    root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                } catch (_: Throwable) { null }
                if (focused?.isEditable == true) {
                    success = insertAtSelection(focused, text)
                    if (!success && prepareClipboardFallback()) {
                        success = runCatching {
                            focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        }.getOrDefault(false)
                    }
                }
            } finally {
                if (disableAfter) setAccessibilityEnabled(false)
                onComplete(success)
            }
        }

        private fun insertAtSelection(node: AccessibilityNodeInfo, text: String): Boolean {
            return try {
                // Password nodes hide their current value. ACTION_SET_TEXT would
                // otherwise rebuild from an empty snapshot and erase it.
                if (node.isPassword) return false
                val nodeText = node.text?.toString().orEmpty()
                val hintText = node.hintText?.toString().orEmpty()
                val current = if (node.isShowingHintText || (hintText.isNotEmpty() && nodeText == hintText)) {
                    ""
                } else {
                    nodeText
                }
                var selStart = node.textSelectionStart
                var selEnd   = node.textSelectionEnd
                if (selStart < 0) selStart = current.length
                if (selEnd   < selStart) selEnd = selStart
                selStart = selStart.coerceAtMost(current.length)
                selEnd   = selEnd.coerceAtMost(current.length)

                val newText = current.substring(0, selStart) + text + current.substring(selEnd)
                val args = android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText
                    )
                }
                if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false

                // Move cursor to end of inserted text
                val newCursor = selStart + text.length
                val selArgs = android.os.Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                true
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: insertAtSelection failed: ${t.message}")
                false
            }
        }

        private fun retryCollect(
            attemptsLeft: Int,
            disableAfter: Boolean,
            onResult: (CollectResult) -> Unit
        ) {
            val result = queryAllTexts()
            if (result.rootAvailable || attemptsLeft <= 1) {
                finishCollect(result, disableAfter, onResult)
                return
            }
            handler.postDelayed(
                { retryCollect(attemptsLeft - 1, disableAfter, onResult) },
                RETRY_DELAY_MS
            )
        }

        private fun finishCollect(
            result: QueryResult,
            disableAfter: Boolean,
            onResult: (CollectResult) -> Unit
        ) {
            try {
                val callbackResult = when {
                    !result.rootAvailable -> CollectResult(CollectStatus.UNAVAILABLE)
                    result.items.isEmpty() -> CollectResult(CollectStatus.NO_TEXT)
                    else -> CollectResult(CollectStatus.FOUND, result.items)
                }
                XposedBridge.log("$TAG: Universal copy collected ${result.items.size} text blocks, status=${callbackResult.status}")
                onResult(callbackResult)
            } finally {
                if (disableAfter) {
                    setAccessibilityEnabled(false)
                }
            }
        }

        private fun queryAllTexts(): QueryResult {
            val root = try {
                getRootInActiveWindow()
            } catch (_: Throwable) {
                null
            } ?: return QueryResult(rootAvailable = false, items = emptyList())

            return QueryResult(
                rootAvailable = true,
                items = PageTextCollector.collectAll(root)
            )
        }

        private fun setAccessibilityEnabled(enabled: Boolean) {
            fakeAccessibilityEnabled = enabled
            updateUiAutomationFlags(enabled)
            try {
                val currentUserState = XposedHelpers.callMethod(managerService, "getCurrentUserState")
                XposedHelpers.callMethod(managerService, "scheduleUpdateClientsIfNeeded", currentUserState)
            } catch (_: Throwable) {
                try {
                    val lock = XposedHelpers.getObjectField(managerService, "mLock")
                    synchronized(lock) {
                        val currentUserState =
                            XposedHelpers.callMethod(managerService, "getCurrentUserStateLocked")
                        XposedHelpers.callMethod(
                            managerService,
                            "scheduleUpdateClientsIfNeededLocked",
                            currentUserState
                        )
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: Universal copy state update failed: ${t.message}")
                }
            }
        }

        private fun updateUiAutomationFlags(enabled: Boolean) {
            try {
                val uiAutomationManager = XposedHelpers.getObjectField(managerService, "mUiAutomationManager")
                val flagsField = XposedHelpers.findField(uiAutomationManager.javaClass, "mUiAutomationFlags")
                val currentFlags = flagsField.getInt(uiAutomationManager)
                val updatedFlags = if (enabled) {
                    currentFlags or 0x2
                } else {
                    currentFlags and 0x2.inv()
                }
                flagsField.setInt(uiAutomationManager, updatedFlags)
            } catch (_: Throwable) {
            }
        }

        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

        override fun onInterrupt() = Unit

        companion object {
            private fun resolveManagerService(accessibilityManager: AccessibilityManager): Any {
                return try {
                    XposedHelpers.getObjectField(accessibilityManager, "mService")
                } catch (_: Throwable) {
                    val lock = XposedHelpers.getObjectField(accessibilityManager, "mLock")
                    synchronized(lock) {
                        XposedHelpers.callMethod(accessibilityManager, "getServiceLocked")
                    }
                }
            }

            private fun resolveConnectionId(managerService: Any): Int {
                val bridge = try {
                    XposedHelpers.callMethod(managerService, "getInteractionBridge")
                } catch (_: Throwable) {
                    val lock = XposedHelpers.getObjectField(managerService, "mLock")
                    synchronized(lock) {
                        XposedHelpers.callMethod(managerService, "getInteractionBridgeLocked")
                    }
                }
                return XposedHelpers.getIntField(bridge, "mConnectionId")
            }
        }
    }

    private data class QueryResult(
        val rootAvailable: Boolean,
        val items: List<TextBlock>
    )

    /**
     * Traverses the entire accessibility tree and collects all visible text,
     * ordered top-to-bottom by screen position.
     */
    private object PageTextCollector {
        private val tempRect = Rect()

        fun collectAll(root: AccessibilityNodeInfo): List<TextBlock> {
            val items = mutableListOf<TextItem>()
            traverse(root, items)
            items.sortWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            return deduplicate(items).map { TextBlock(it.text, it.bounds) }
        }

        private fun traverse(node: AccessibilityNodeInfo, items: MutableList<TextItem>) {
            if (!node.isVisibleToUser) return

            val className = node.className?.toString().orEmpty()
            if (className.contains("Image", ignoreCase = true)) return

            // Try children first — prefer leaf-level text
            var childrenHadText = false
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val sizeBefore = items.size
                traverse(child, items)
                if (items.size > sizeBefore) childrenHadText = true
            }

            // Only add this node's text if no children contributed text
            if (childrenHadText) return

            val text = normalizeText(node.text) ?: normalizeText(node.contentDescription) ?: return
            node.getBoundsInScreen(tempRect)
            items += TextItem(text, Rect(tempRect))
        }

        private fun deduplicate(items: List<TextItem>): List<TextItem> {
            if (items.size <= 1) return items
            val seen = LinkedHashSet<String>()
            val result = mutableListOf<TextItem>()
            for (item in items) {
                if (seen.add(item.text)) {
                    result += item
                }
            }
            return result
        }

        private fun normalizeText(text: CharSequence?): String? {
            if (text.isNullOrEmpty()) return null
            val str = if (text.length > 65536) text.subSequence(0, 65536).toString() else text.toString()
            val trimmed = str.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.length == 1) {
                val c = trimmed[0]
                if (!((c > ' ' && c <= '~') || c.code > 160)) return null
            }
            return trimmed
        }

        private data class TextItem(val text: String, val bounds: Rect)
    }
}
