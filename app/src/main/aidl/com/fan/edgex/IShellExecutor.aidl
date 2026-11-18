package com.fan.edgex;

import com.fan.edgex.IShellCallback;
import android.os.ParcelFileDescriptor;

oneway interface IShellExecutor {
    void execute(String command, boolean runAsRoot, IShellCallback callback);
    void savePngToGallery(in ParcelFileDescriptor png, String displayName, IShellCallback callback);
    void createClipboardBackup(String manifestJson, in List<String> imagePaths,
        String archiveName, IShellCallback callback);
    void createClipboardBackupFromStream(in ParcelFileDescriptor manifest,
        in List<String> imagePaths, String archiveName, boolean toDownloads, IShellCallback callback);
}
