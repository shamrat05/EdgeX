package com.fan.edgex.hook

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.content.res.Resources
import android.content.res.XModuleResources
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.fan.edgex.R

object ModuleRes {
    private var res: XModuleResources? = null

    fun init(modulePath: String) {
        res = XModuleResources.createInstance(modulePath, null)
    }

    /**
     * AndroidX widgets created inside system_server still resolve their own
     * library dimensions through Context.getResources(). Give those widgets a
     * context backed by this module APK while keeping system services delegated
     * to the original system context.
     */
    fun contextWithModuleResources(base: Context): Context {
        val moduleResources = res ?: return base
        return object : ContextWrapper(base) {
            private val moduleTheme: Resources.Theme = moduleResources.newTheme().apply {
                applyStyle(R.style.Theme_EdgeX, true)
            }

            override fun getResources(): Resources = moduleResources

            override fun getTheme(): Resources.Theme = moduleTheme
        }
    }

    fun getString(@StringRes id: Int, vararg args: Any?): String {
        val r = res ?: return ""
        val raw = r.getString(id)
        return if (args.isEmpty()) raw else String.format(raw, *args)
    }

    @Suppress("DEPRECATION")
    fun getDrawable(@DrawableRes id: Int, tint: Int = Color.WHITE): Drawable? {
        val r = res ?: return null
        return try {
            r.getDrawable(id).also { it.setTint(tint) }
        } catch (_: Exception) { null }
    }
}
