/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.util.Log;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.AppPermissions;
import com.android.packageinstaller.permission.model.Permission;
import com.android.packageinstaller.permission.utils.ArrayUtils;
import com.android.packageinstaller.permission.utils.Utils;
import java.util.List;

import java.io.File;
import java.io.IOException;

/**
 * Manages files of the package installer and resets state during boot.
 */
public class TemporaryFileManager extends BroadcastReceiver {
    private static final String LOG_TAG = TemporaryFileManager.class.getSimpleName();

    /**
     * Create a new file to hold a staged file.
     *
     * @param context The context of the caller
     *
     * @return A new file
     */
    @NonNull
    public static File getStagedFile(@NonNull Context context) throws IOException {
        return File.createTempFile("package", ".apk", context.getNoBackupFilesDir());
    }

    /**
     * Get the file used to store the results of installs.
     *
     * @param context The context of the caller
     *
     * @return the file used to store the results of installs
     */
    @NonNull
    public static File getInstallStateFile(@NonNull Context context) {
        return new File(context.getNoBackupFilesDir(), "install_results.xml");
    }

    /**
     * Get the file used to store the results of uninstalls.
     *
     * @param context The context of the caller
     *
     * @return the file used to store the results of uninstalls
     */
    @NonNull
    public static File getUninstallStateFile(@NonNull Context context) {
        return new File(context.getNoBackupFilesDir(), "uninstall_results.xml");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.e("permission", "action==="+ intent.getAction());

        context.startService(new Intent(context, PackageChangedService.class));


        PermissionGrantHelper.slientGrantRuntimePermission(context, "com.android.dialer");//for system
        PermissionGrantHelper.slientGrantRuntimePermission(context, "cn.kaer.kecontacts");//for kaer
        PermissionGrantHelper.slientGrantRuntimePermission(context, "cn.kaer.sipphone");//for kaer
        PermissionGrantHelper.slientGrantRuntimePermission(context, "com.android.soundrecorder");//for kaer
        PermissionGrantHelper.slientGrantRuntimePermission(context, "com.kaer.weatherwidget");//for kaer

        long systemBootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime();

        File[] filesOnBoot = context.getNoBackupFilesDir().listFiles();

        if (filesOnBoot == null) {
            return;
        }

        for (int i = 0; i < filesOnBoot.length; i++) {
            File fileOnBoot = filesOnBoot[i];

            if (systemBootTime > fileOnBoot.lastModified()) {
                boolean wasDeleted = fileOnBoot.delete();
                if (!wasDeleted) {
                    Log.w(LOG_TAG, "Could not delete " + fileOnBoot.getName() + " onBoot");
                }
            } else {
                Log.w(LOG_TAG, fileOnBoot.getName() + " was created before onBoot broadcast was "
                        + "received");
            }
        }
    }
}
