package com.fan.edgex;

/** Callback implemented inside the active input-method process. */
interface IClipboardImeBridge {
    boolean commitImage(String uri, String mimeType, String label, String targetPackage);
}
