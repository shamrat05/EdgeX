package com.fan.edgex.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardImagePastePolicyTest {

    @Test
    fun `Termux receives a path even when its terminal view is not marked editable`() {
        assertEquals(
            UniversalCopyManager.ImagePasteMode.PATH,
            UniversalCopyManager.imagePasteMode(
                packageName = "com.termux",
                className = "com.termux.view.TerminalView",
                editable = false,
                password = false
            )
        )
    }

    @Test
    fun `stock text editors use the active IME rich-content path`() {
        assertEquals(
            UniversalCopyManager.ImagePasteMode.DIRECT,
            UniversalCopyManager.imagePasteMode(
                packageName = "example.editor",
                className = "android.widget.EditText",
                editable = true,
                password = false
            )
        )
    }

    @Test
    fun `custom rich editors can receive an image clip`() {
        assertEquals(
            UniversalCopyManager.ImagePasteMode.DIRECT,
            UniversalCopyManager.imagePasteMode(
                packageName = "example.chat",
                className = "example.chat.RichMessageEditor",
                editable = true,
                password = false
            )
        )
    }

    @Test
    fun `password fields never receive an image path`() {
        assertEquals(
            UniversalCopyManager.ImagePasteMode.MANUAL,
            UniversalCopyManager.imagePasteMode(
                packageName = "com.termux",
                className = "android.widget.EditText",
                editable = true,
                password = true
            )
        )
    }
}
