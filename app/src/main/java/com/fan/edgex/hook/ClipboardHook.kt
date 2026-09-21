package com.fan.edgex.hook

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentUris
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.ContentObserver
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import com.fan.edgex.BuildConfig
import com.fan.edgex.config.ClipboardImageStore
import com.fan.edgex.config.ScreenshotMediaFilter
import com.fan.edgex.IShellCallback
import com.fan.edgex.IShellExecutor
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.Executors

/**
 * Captures clipboard content inside ClipboardService.
 *
 * Text is cached exactly as before. Image clips are never copied, decoded or
 * opened in system_server: only the metadata needed to identify them is taken
 * here, and the bytes are materialised later through the app's root shell
 * bridge (the same IShellExecutor path backups already use).
 */
object ClipboardHook {

    private const val TAG = "EdgeX"

    /** Detected image payload handed to [ClipboardOverlay]. */
    data class ImageClip(
        val uri: String,
        val mimeType: String?,
        val width: Int,
        val height: Int
    )

    fun installHook(classLoader: ClassLoader) {
        // Try internal storage methods first (more reliable across API levels)
        val hookedInternal = tryHookByName(classLoader, "com.android.server.clipboard.ClipboardService",
            "setPrimaryClipInternal",     // Android 12-15
            "setPrimaryClipInternalLocked" // older fallback
        )

        // Always also hook the public entry point as a belt-and-suspenders fallback
        val hookedPublic = tryHookByName(classLoader, "com.android.server.clipboard.ClipboardService",
            "setPrimaryClip"
        )

        if (!hookedInternal && !hookedPublic) {
            XposedBridge.log("$TAG: ClipboardHook — no hook point found")
        }
    }

    private fun tryHookByName(classLoader: ClassLoader, className: String, vararg methodNames: String): Boolean {
        return try {
            val cls = XposedHelpersCompat.findClass(className, classLoader)
            var hooked = false
            for (name in methodNames) {
                try {
                    val count = de.robv.android.xposed.XposedBridge.hookAllMethods(cls, name, clipHook).size
                    if (count > 0) {
                        XposedBridge.log("$TAG: ClipboardHook hooked $className#$name ($count overloads)")
                        hooked = true
                    }
                } catch (_: Throwable) { }
            }
            hooked
        } catch (_: Throwable) {
            false
        }
    }

    private val clipHook = object : de.robv.android.xposed.XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val clip = param.args.firstOrNull { it is ClipData } as? ClipData ?: return
            runCatching {
                XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
            }.getOrNull()?.let(::setSystemContext)
            val sourceUid = try {
                Binder.getCallingUid()
            } catch (_: Throwable) {
                -1
            }
            val timestamp = System.currentTimeMillis()

            val image = extractImage(clip)
            if (image != null) {
                ClipboardOverlay.onClipboardImage(image, timestamp, sourceUid)
                extractText(clip)?.let {
                    ClipboardOverlay.onClipboardChanged(it, timestamp, sourceUid)
                }
                return
            }

            extractText(clip)?.let { ClipboardOverlay.onClipboardChanged(it, timestamp, sourceUid) }
        }
    }

    private fun extractText(clip: ClipData): String? {
        if (clip.itemCount == 0) return null
        // Prefer getText() — no context needed, no URI resolution
        val text = clip.getItemAt(0).text?.toString()?.trim()
        return if (text.isNullOrEmpty()) null else text
    }

    /** Reads the URI + declared MIME only; no stream is opened here. */
    private fun extractImage(clip: ClipData): ImageClip? {
        if (clip.itemCount == 0) return null
        val description = clip.description ?: return null
        val mime = pickImageMime(description) ?: return null
        val uri = (0 until clip.itemCount)
            .mapNotNull { index -> clip.getItemAt(index).uri?.toString() }
            .firstOrNull() ?: return null
        return ImageClip(uri = uri, mimeType = mime, width = 0, height = 0)
    }

    private fun pickImageMime(description: ClipDescription): String? {
        for (index in 0 until description.mimeTypeCount) {
            val mime = description.getMimeType(index) ?: continue
            if (mime.startsWith("image/")) return mime
        }
        return null
    }

    // ── Root shell bridge (image bytes only) ───────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var executor: IShellExecutor? = null
    @Volatile private var bound = false
    private var context: Context? = null
    private val shellConnectionLock = Any()
    private var shellIdleUnbind: Runnable? = null

    private val screenshotLock = Any()
    private val screenshotSeen = LinkedHashSet<Long>()
    private val screenshotPending = HashSet<Long>()
    private val screenshotRetries = HashMap<Long, Int>()
    private val screenshotReadyRetries = HashMap<Long, Int>()
    private val screenshotRequestedUris = LinkedHashSet<String>()
    @Volatile private var screenshotObserver: ContentObserver? = null
    @Volatile private var contextInitialized = false
    @Volatile private var observerRetryScheduled = false
    @Volatile private var observerRegistrationAttempts = 0
    private var screenshotScanRunnable: Runnable? = null
    private var screenshotCollectionScanPending = false
    private var lastTargetedChangeElapsed = 0L
    private val screenshotExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EdgeX-ScreenshotScan").apply { isDaemon = true }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(shellConnectionLock) {
                executor = IShellExecutor.Stub.asInterface(binder)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(shellConnectionLock) {
                executor = null
            }
        }
    }

    /** Starts the system screenshot watcher once a system_server Context is available. */
    fun setSystemContext(context: Context) {
        if (contextInitialized && (
                screenshotObserver != null || observerRetryScheduled ||
                    observerRegistrationAttempts >= MAX_OBSERVER_REGISTRATION_ATTEMPTS
            )) return
        synchronized(screenshotLock) {
            if (!contextInitialized) {
                ClipboardOverlay.setSystemContext(context)
                contextInitialized = true
            }
            if (screenshotObserver != null || observerRetryScheduled ||
                observerRegistrationAttempts >= MAX_OBSERVER_REGISTRATION_ATTEMPTS
            ) return
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    scheduleScreenshotScan(context, null)
                }

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    scheduleScreenshotScan(context, uri)
                }
            }
            try {
                context.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    observer
                )
                screenshotObserver = observer
                observerRegistrationAttempts = 0
                XposedBridge.log("$TAG: screenshot MediaStore watcher active")
                // Covers a screenshot saved just before the first system input registered us.
                scheduleScreenshotScan(context, null)
            } catch (t: Throwable) {
                observerRegistrationAttempts++
                XposedBridge.log(
                    "$TAG: screenshot watcher registration failed " +
                        "(${observerRegistrationAttempts}/$MAX_OBSERVER_REGISTRATION_ATTEMPTS): " +
                        t.javaClass.simpleName
                )
                if (observerRegistrationAttempts < MAX_OBSERVER_REGISTRATION_ATTEMPTS) {
                    observerRetryScheduled = true
                    val delay = if (observerRegistrationAttempts == 1) 1_000L else 5_000L
                    handler.postDelayed({
                        synchronized(screenshotLock) { observerRetryScheduled = false }
                        setSystemContext(context)
                    }, delay)
                }
            }
        }
    }

    /** Finds only recent MediaStore images in screenshot folders or named as screenshots. */
    private fun scanRecentScreenshots(context: Context, changedUri: Uri? = null) {
        val columns = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATA,
            MediaStore.MediaColumns.IS_PENDING
        )
        val cutoffSeconds = System.currentTimeMillis() / 1000L - SCREENSHOT_LOOKBACK_SECONDS
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ? AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0"
        val args = arrayOf(cutoffSeconds.toString())
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        try {
            val resolver = context.contentResolver
            val cursor = if (changedUri != null) {
                resolver.query(changedUri, columns, null, null, null)
            } else try {
                resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    columns,
                    selection,
                    args,
                    sort
                )
            } catch (primary: IllegalArgumentException) {
                XposedBridge.log(
                    "$TAG: screenshot query fallback after ${primary.javaClass.simpleName}"
                )
                // Some OEM MediaStore providers reject IS_PENDING from system_server.
                resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    columns,
                    "${MediaStore.Images.Media.DATE_ADDED} >= ?",
                    args,
                    sort
                )
            }
            if (cursor == null) {
                XposedBridge.log("$TAG: screenshot scan returned no MediaStore cursor")
                return
            }
            cursor.use {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val timeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val relativePathColumn = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                val pathColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                val pendingColumn = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                var checked = 0
                val rowLimit = if (changedUri == null) MAX_SCREENSHOT_SCAN_ROWS else 1
                while (cursor.moveToNext() && checked++ < rowLimit) {
                    val id = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    if (pendingColumn >= 0 && cursor.getInt(pendingColumn) != 0) {
                        val retry = synchronized(screenshotLock) {
                            val count = (screenshotReadyRetries[id] ?: 0) + 1
                            if (count <= MAX_SCREENSHOT_READY_RETRIES) {
                                screenshotReadyRetries[id] = count
                                count
                            } else {
                                screenshotReadyRetries.remove(id)
                                null
                            }
                        }
                        retry?.let { attempt ->
                            handler.postDelayed(
                                { scheduleScreenshotScan(context, uri) },
                                SCREENSHOT_READY_RETRY_DELAY_MS shl (attempt - 1)
                            )
                        }
                        continue
                    }
                    synchronized(screenshotLock) { screenshotReadyRetries.remove(id) }
                    val name = cursor.getString(nameColumn)
                    val relativePath = if (relativePathColumn >= 0) {
                        cursor.getString(relativePathColumn).orEmpty()
                    } else ""
                    val sourcePath = if (pathColumn >= 0) cursor.getString(pathColumn) else null
                    val safeSourcePath = sourcePath?.takeIf(ScreenshotMediaFilter::isSafeSourcePath)
                    if (!ScreenshotMediaFilter.matches(relativePath, name)) continue

                    val mime = cursor.getString(mimeColumn)
                    if (!ClipboardImageStore.isSupported(mime)) continue
                    val timestamp = cursor.getLong(timeColumn) * 1000L
                    if (ClipboardOverlay.attachScreenshotSource(
                            uri.toString(), safeSourcePath, relativePath, name, timestamp
                        )) {
                        synchronized(screenshotLock) { markScreenshotSeenLocked(id) }
                        continue
                    }
                    val shouldProcess = synchronized(screenshotLock) {
                        id !in screenshotSeen && screenshotPending.add(id)
                    }
                    if (!shouldProcess) continue
                    ClipboardOverlay.onScreenshotFile(
                        ImageClip(uri.toString(), mime, 0, 0),
                        safeSourcePath,
                        relativePath,
                        name,
                        timestamp,
                        -1
                    ) { stored ->
                        val retry = synchronized(screenshotLock) {
                            screenshotPending.remove(id)
                            if (stored) {
                                markScreenshotSeenLocked(id)
                                screenshotRetries.remove(id)
                                null
                            } else {
                                val count = (screenshotRetries[id] ?: 0) + 1
                                if (count <= MAX_SCREENSHOT_RETRIES) {
                                    screenshotRetries[id] = count
                                    count
                                } else {
                                    screenshotRetries.remove(id)
                                    null
                                }
                            }
                        }
                        XposedBridge.log(
                            if (stored) "$TAG: screenshot saved to clipboard history"
                            else if (retry != null) "$TAG: screenshot copy failed; retry scheduled"
                            else "$TAG: detected screenshot could not be saved after retries"
                        )
                        retry?.let { attempt ->
                            val delay = SCREENSHOT_RETRY_DELAY_MS shl (attempt - 1)
                            handler.postDelayed({ scheduleScreenshotScan(context, uri) }, delay)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            val detail = t.message?.replace('\n', ' ')?.take(160)
            XposedBridge.log(
                "$TAG: screenshot scan failed: ${t.javaClass.simpleName}" +
                        (detail?.let { ": $it" } ?: "")
            )
            XposedBridge.log(t)
        }
    }

    private fun scheduleScreenshotScan(context: Context, changedUri: Uri?) {
        val itemUri = changedUri?.let(::mediaStoreItemUri)
        val task = object : Runnable {
            override fun run() {
                val work = synchronized(screenshotLock) {
                    if (screenshotScanRunnable !== this) {
                        null
                    } else {
                        screenshotScanRunnable = null
                        val requested = screenshotRequestedUris.toList()
                        screenshotRequestedUris.clear()
                        val scanCollection = screenshotCollectionScanPending
                        screenshotCollectionScanPending = false
                        requested to scanCollection
                    }
                }
                if (work != null) screenshotExecutor.execute {
                    val (requested, scanCollection) = work
                    requested.forEach { scanRecentScreenshots(context, Uri.parse(it)) }
                    if (requested.isEmpty() && scanCollection) {
                        scanRecentScreenshots(context)
                    }
                }
            }
        }
        synchronized(screenshotLock) {
            if (itemUri != null) {
                screenshotRequestedUris.add(itemUri.toString())
                lastTargetedChangeElapsed = SystemClock.elapsedRealtime()
            } else {
                // Providers may send both collection and exact-item events.
                // The exact row is cheaper and prevents old screenshots from
                // becoming a copy backlog behind the newest one.
                if (SystemClock.elapsedRealtime() - lastTargetedChangeElapsed <
                    SCREENSHOT_COLLECTION_SUPPRESS_MS
                ) return
                screenshotCollectionScanPending = true
            }
            if (screenshotScanRunnable != null) return
            screenshotScanRunnable = task
            handler.postDelayed(
                task,
                if (itemUri == null) SCREENSHOT_COLLECTION_DELAY_MS else 0L
            )
        }
    }

    private fun mediaStoreItemUri(uri: Uri): Uri? {
        if (uri.authority != MediaStore.AUTHORITY) return null
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    }

    private fun markScreenshotSeenLocked(id: Long) {
        screenshotSeen.add(id)
        while (screenshotSeen.size > MAX_SCREENSHOT_SEEN_IDS) {
            val iterator = screenshotSeen.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    /**
     * Runs a root command through the app's service. Returns false when the
     * bridge is unavailable so the caller can degrade gracefully.
     */
    fun runRootShell(ctx: Context, command: String, onDone: (Boolean, String) -> Unit) {
        runRootShellAttempt(ctx, command, SHELL_BIND_ATTEMPTS, onDone)
    }

    private fun runRootShellAttempt(
        ctx: Context,
        command: String,
        attemptsLeft: Int,
        onDone: (Boolean, String) -> Unit
    ) {
        cancelShellIdleUnbind()
        val ready = executor
        if (ready == null) {
            ensureBound(ctx)
            if (attemptsLeft > 0) {
                handler.postDelayed({
                    runRootShellAttempt(ctx, command, attemptsLeft - 1, onDone)
                }, SHELL_BIND_RETRY_MS)
            } else {
                scheduleShellIdleUnbind()
                onDone(false, "")
            }
            return
        }
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                scheduleShellIdleUnbind()
                onDone(false, "")
            }
        }
        handler.postDelayed(timeout, SHELL_COMMAND_TIMEOUT_MS)
        try {
            ready.execute(command, true, object : IShellCallback.Stub() {
                override fun onResult(success: Boolean, output: String?) {
                    handler.post {
                        if (!completed.compareAndSet(false, true)) return@post
                        handler.removeCallbacks(timeout)
                        scheduleShellIdleUnbind()
                        onDone(success, output.orEmpty())
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: image copy shell failed: ${t.javaClass.simpleName}")
            handler.post {
                if (!completed.compareAndSet(false, true)) return@post
                handler.removeCallbacks(timeout)
                scheduleShellIdleUnbind()
                onDone(false, "")
            }
        }
    }

    private fun ensureBound(ctx: Context) {
        synchronized(shellConnectionLock) {
            if (bound) return
            context = ctx
            val intent = Intent().apply {
                component = ComponentName(
                    BuildConfig.APPLICATION_ID,
                    "${BuildConfig.APPLICATION_ID}.config.ShellExecutorService"
                )
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            bound = runCatching {
                ctx.bindServiceAsUser(
                    intent,
                    connection,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
                    android.os.Process.myUserHandle()
                )
            }.getOrDefault(false)
            if (!bound) context = null
        }
    }

    private fun cancelShellIdleUnbind() {
        synchronized(shellConnectionLock) {
            shellIdleUnbind?.let(handler::removeCallbacks)
            shellIdleUnbind = null
        }
    }

    private fun scheduleShellIdleUnbind() {
        synchronized(shellConnectionLock) {
            shellIdleUnbind?.let(handler::removeCallbacks)
            val task = Runnable {
                val boundContext = synchronized(shellConnectionLock) {
                    shellIdleUnbind = null
                    if (!bound) {
                        executor = null
                        context = null
                        return@synchronized null
                    }
                    val current = context
                    executor = null
                    bound = false
                    context = null
                    current
                }
                boundContext?.let { runCatching { it.unbindService(connection) } }
            }
            shellIdleUnbind = task
            handler.postDelayed(task, SHELL_IDLE_UNBIND_MS)
        }
    }

    private object XposedHelpersCompat {
        fun findClass(name: String, classLoader: ClassLoader): Class<*> =
            de.robv.android.xposed.XposedHelpers.findClass(name, classLoader)
    }

    private const val SCREENSHOT_LOOKBACK_SECONDS = 15L
    private const val MAX_SCREENSHOT_SCAN_ROWS = 4
    private const val MAX_SCREENSHOT_SEEN_IDS = 256
    private const val MAX_SCREENSHOT_RETRIES = 3
    private const val MAX_SCREENSHOT_READY_RETRIES = 3
    private const val SCREENSHOT_RETRY_DELAY_MS = 800L
    private const val SCREENSHOT_READY_RETRY_DELAY_MS = 80L
    private const val SCREENSHOT_COLLECTION_DELAY_MS = 40L
    private const val SCREENSHOT_COLLECTION_SUPPRESS_MS = 500L
    private const val MAX_OBSERVER_REGISTRATION_ATTEMPTS = 3
    private const val SHELL_BIND_ATTEMPTS = 40
    private const val SHELL_BIND_RETRY_MS = 50L
    private const val SHELL_IDLE_UNBIND_MS = 15_000L
    private const val SHELL_COMMAND_TIMEOUT_MS = 30_000L
}
