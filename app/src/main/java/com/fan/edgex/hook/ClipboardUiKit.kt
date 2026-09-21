package com.fan.edgex.hook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.format.DateFormat
import androidx.core.graphics.toColorInt
import com.fan.edgex.config.AppConfig
import com.fan.edgex.config.HookConfigSnapshot
import com.fan.edgex.config.ThemeColorResolver
import java.util.Calendar

/**
 * Visual tokens and content heuristics for the clipboard overlay.
 *
 * Colors are resolved from the EdgeX theme configuration so the sheet follows
 * the user's accent instead of hard-coding a brand color. Dark mode is the
 * primary target; light mode gets a readable equivalent palette.
 */
internal object ClipboardUiKit {

    // ── Palette ────────────────────────────────────────────────────────────

    class Palette(
        val dark: Boolean,
        val sheet: Int,
        val card: Int,
        val raised: Int,
        val border: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val iconMuted: Int,
        val accent: Int,
        val danger: Int,
        val scrim: Int,
        val snackbar: Int
    ) {
        fun accentAlpha(fraction: Float): Int {
            val alpha = (Color.alpha(accent) * fraction).toInt().coerceIn(0, 255)
            return Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent))
        }
    }

    private const val FALLBACK_ACCENT = 0xFF37E0C2.toInt()

    fun isDark(context: Context): Boolean {
        val setting = HookConfigSnapshot.readFromHookFile()[AppConfig.UI_DARK_MODE] ?: "system"
        return when (setting) {
            "dark", "true" -> true
            "light", "false" -> false
            "system", "" -> (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            else -> setting.toBooleanStrictOrNull() ?: (
                    (context.resources.configuration.uiMode and
                            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                    )
        }
    }

    fun accentColor(): Int {
        val snapshot = HookConfigSnapshot.readFromHookFile()
        val configured = ThemeColorResolver.resolveThemeColor { key -> snapshot[key] ?: "" }
        return if (configured == 0) FALLBACK_ACCENT else configured
    }

    fun palette(context: Context): Palette {
        val dark = isDark(context)
        val accent = accentColor()
        return if (dark) {
            Palette(
                dark = true,
                sheet = "#12131C".toColorInt(),
                card = "#191B26".toColorInt(),
                raised = "#20222E".toColorInt(),
                border = "#2A2D39".toColorInt(),
                textPrimary = "#F4F4F7".toColorInt(),
                textSecondary = "#A6A8B7".toColorInt(),
                iconMuted = "#AEB2C5".toColorInt(),
                accent = accent,
                danger = "#FF6B6B".toColorInt(),
                scrim = 0xB3000000.toInt(),
                snackbar = "#20222E".toColorInt()
            )
        } else {
            Palette(
                dark = false,
                sheet = "#F7F7FA".toColorInt(),
                card = "#FFFFFF".toColorInt(),
                raised = "#EFEFF4".toColorInt(),
                border = "#E2E2EA".toColorInt(),
                textPrimary = "#1B1C22".toColorInt(),
                textSecondary = "#6A6C7A".toColorInt(),
                iconMuted = "#7A7D8C".toColorInt(),
                accent = accent,
                danger = "#D64545".toColorInt(),
                scrim = 0x8A000000.toInt(),
                snackbar = "#FFFFFF".toColorInt()
            )
        }
    }

    // ── Drawables ──────────────────────────────────────────────────────────

    fun rounded(
        color: Int,
        radiusPx: Float,
        strokePx: Int = 0,
        strokeColor: Int = 0
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

    fun roundedTop(color: Int, radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadii = floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
    }

    fun ripple(color: Int): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(color), null, null)

    /** Rounded card with border whose ripple is clipped to the rounded shape. */
    fun cardRipple(
        color: Int,
        radiusPx: Float,
        strokePx: Int,
        strokeColor: Int,
        rippleColor: Int
    ): RippleDrawable {
        val content = rounded(color, radiusPx, strokePx, strokeColor)
        val mask = rounded(Color.WHITE, radiusPx)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    // ── Content classification ─────────────────────────────────────────────

    enum class ClipKind { NOTE, CODE, LINK, EMAIL }

    private val urlRegex = Regex("^(https?://\\S+|www\\.\\S+|[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+/\\S*)$")
    private val bareDomainRegex = Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+$")
    private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    private val shellTokens = setOf(
        "git", "gh", "adb", "npm", "yarn", "pnpm", "pip", "pip3", "python", "python3",
        "curl", "wget", "sudo", "su", "docker", "kubectl", "gradle", "./gradlew", "ssh",
        "scp", "cd", "ls", "make", "cargo", "go", "node", "npx", "composer", "apt",
        "apt-get", "pkg", "echo", "export", "source", "brew", "chmod", "chown", "mkdir",
        "rm", "cp", "mv", "cat", "grep", "sed", "awk", "tar", "unzip", "kill", "systemctl",
        "service", "setprop", "getprop", "pm", "am", "input", "dumpsys", "fastboot"
    )

    private val codeSignals = listOf(
        "{\n", "}\n", "();", "=>", "->", "def ", "function ", "class ", "import ",
        "fun ", "val ", "var ", "const ", "let ", "public ", "private ", "return ",
        "</", "/>", "elif ", "<?php", "SELECT ", "INSERT ", "console.log", "print(",
        "#include", "std::", "package ", "namespace "
    )

    fun looksLikeUrl(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty() || value.contains('\n') || value.contains(' ')) return false
        return urlRegex.matches(value) || bareDomainRegex.matches(value)
    }

    fun looksLikeEmail(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty() || value.contains('\n') || value.contains(' ')) return false
        return emailRegex.matches(value)
    }

    fun looksLikeCode(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        val firstLine = value.lineSequence().first().trim()
        if (firstLine.startsWith("$ ") || firstLine.startsWith("> ") ||
            firstLine.startsWith("#!") || firstLine.startsWith("./")
        ) {
            return true
        }
        val firstToken = firstLine.substringBefore(' ').lowercase()
        if (firstToken in shellTokens) return true

        val lines = value.lines()
        if (lines.size >= 2) {
            if (codeSignals.any { value.contains(it) }) return true
            val terminated = lines.count { line ->
                val trimmed = line.trimEnd()
                trimmed.endsWith(";") || trimmed.endsWith("{") || trimmed.endsWith("}")
            }
            if (terminated >= 2) return true
        }
        return false
    }

    fun classify(text: String): ClipKind = when {
        looksLikeUrl(text) -> ClipKind.LINK
        looksLikeEmail(text) -> ClipKind.EMAIL
        looksLikeCode(text) -> ClipKind.CODE
        else -> ClipKind.NOTE
    }

    // ── Time labels / sections ─────────────────────────────────────────────

    enum class DaySection { TODAY, YESTERDAY, EARLIER }

    fun daySection(timestamp: Long, now: Long = System.currentTimeMillis()): DaySection {
        if (timestamp <= 0L) return DaySection.EARLIER
        val today = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (timestamp >= today.timeInMillis) return DaySection.TODAY
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return if (timestamp >= yesterday.timeInMillis) DaySection.YESTERDAY else DaySection.EARLIER
    }

    /** Relative label for recent clips, calendar date for older ones. */
    fun timeLabel(context: Context, timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val elapsed = System.currentTimeMillis() - timestamp
        return when {
            elapsed < 45_000L -> ModuleRes.getString(com.fan.edgex.R.string.clipboard_just_now)
            elapsed < 60 * 60_000L -> ModuleRes.getString(
                com.fan.edgex.R.string.clipboard_minutes_ago, (elapsed / 60_000L).toInt()
            )
            elapsed < 24 * 60 * 60_000L -> ModuleRes.getString(
                com.fan.edgex.R.string.clipboard_hours_ago, (elapsed / 3_600_000L).toInt()
            )
            else -> {
                val formatter = DateFormat.getMediumDateFormat(context)
                formatter.format(java.util.Date(timestamp))
            }
        }
    }
}
