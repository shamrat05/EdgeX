package com.fan.edgex;

import android.graphics.Bitmap;

interface IClipboardImageBridge {
    // Keep the third argument for compatibility with system_server hooks that
    // may still be loaded from the previously installed API 82 module APK.
    boolean setImageClip(String uri, String label, String targetPackage);
    boolean commitImageToEditor(String uri, String mimeType, String label, String targetPackage);
    boolean registerImeBridge(IBinder imeBridge);
    void unregisterImeBridge(IBinder imeBridge);
    boolean openImage(String uri, String mimeType, String label);
    boolean shareImage(String uri, String mimeType, String chooserTitle);
    Bitmap loadImageThumbnail(String uri, int targetPx);
}
