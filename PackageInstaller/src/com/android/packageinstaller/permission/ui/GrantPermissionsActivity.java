/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.packageinstaller.permission.ui;

import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.android.packageinstaller.DeviceUtils;
import com.android.packageinstaller.R;
import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.AppPermissions;
import com.android.packageinstaller.permission.model.Permission;
import com.android.packageinstaller.permission.ui.auto.GrantPermissionsAutoViewHandler;
import com.android.packageinstaller.permission.ui.handheld.GrantPermissionsViewHandlerImpl;
import com.android.packageinstaller.permission.utils.ArrayUtils;
import com.android.packageinstaller.permission.utils.EventLogger;
import com.android.packageinstaller.permission.utils.SafetyNetLogger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/// M: CTA requirement - permission control @{
import android.os.UserHandle;
import com.android.packageinstaller.permission.model.PermissionState;
import com.android.packageinstaller.permission.utils.Utils;
///@}

public class GrantPermissionsActivity extends OverlayTouchActivity
        implements GrantPermissionsViewHandler.ResultListener {

    private static final String LOG_TAG = "GrantPermissionsActivity";

    private String[] mRequestedPermissions;
    private int[] mGrantResults;

    private LinkedHashMap<String, GroupState> mRequestGrantPermissionGroups = new LinkedHashMap<>();

    private GrantPermissionsViewHandler mViewHandler;
    private AppPermissions mAppPermissions;

    boolean mResultSet;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setFinishOnTouchOutside(false);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        setTitle(R.string.permission_request_title);

        if (DeviceUtils.isTelevision(this)) {
            mViewHandler = new com.android.packageinstaller.permission.ui.television
                    .GrantPermissionsViewHandlerImpl(this,
                    getCallingPackage()).setResultListener(this);
        } else if (DeviceUtils.isWear(this)) {
            mViewHandler = new GrantPermissionsWatchViewHandler(this).setResultListener(this);
        } else if (DeviceUtils.isAuto(this)) {
            mViewHandler = new GrantPermissionsAutoViewHandler(this, getCallingPackage())
                    .setResultListener(this);
        } else {
            mViewHandler = new com.android.packageinstaller.permission.ui.handheld
                    .GrantPermissionsViewHandlerImpl(this, getCallingPackage())
                    .setResultListener(this);
        }

        mRequestedPermissions = getIntent().getStringArrayExtra(
                PackageManager.EXTRA_REQUEST_PERMISSIONS_NAMES);
        if (mRequestedPermissions == null) {
            mRequestedPermissions = new String[0];
        }

        final int requestedPermCount = mRequestedPermissions.length;
        mGrantResults = new int[requestedPermCount];
        Arrays.fill(mGrantResults, PackageManager.PERMISSION_DENIED);

        if (requestedPermCount == 0) {
            setResultAndFinish();
            return;
        }

        PackageInfo callingPackageInfo = getCallingPackageInfo();

        if (callingPackageInfo == null || callingPackageInfo.requestedPermissions == null
                || callingPackageInfo.requestedPermissions.length <= 0) {
            setResultAndFinish();
            return;
        }

        // Don't allow legacy apps to request runtime permissions.
        if (callingPackageInfo.applicationInfo.targetSdkVersion < Build.VERSION_CODES.M) {
            // Returning empty arrays means a cancellation.
            mRequestedPermissions = new String[0];
            mGrantResults = new int[0];
            setResultAndFinish();
            return;
        }

        DevicePolicyManager devicePolicyManager = getSystemService(DevicePolicyManager.class);
        final int permissionPolicy = devicePolicyManager.getPermissionPolicy(null);

        // If calling package is null we default to deny all.
        updateDefaultResults(callingPackageInfo, permissionPolicy);

        mAppPermissions = new AppPermissions(this, callingPackageInfo, null, false,
                new Runnable() {
                    @Override
                    public void run() {
                        setResultAndFinish();
                    }
                });

        for (String requestedPermission : mRequestedPermissions) {
            AppPermissionGroup group = null;

            ///M: CTA requirement - permission control.
            Permission permission = null;
            for (AppPermissionGroup nextGroup : mAppPermissions.getPermissionGroups()) {
                if (nextGroup.hasPermission(requestedPermission)) {

                    ///M: CTA requirement - permission control.
                    permission = nextGroup.getPermission(requestedPermission);
                    group = nextGroup;
                    break;
                }
            }
            if (group == null) {
                continue;
            }
            if (!group.isGrantingAllowed()) {
                // Skip showing groups that we know cannot be granted.
                continue;
            }
            // We allow the user to choose only non-fixed permissions. A permission
            // is fixed either by device policy or the user denying with prejudice.
            if (!group.isUserFixed() && !group.isPolicyFixed()
                /**M.@{**/&& !Utils.CTA_SUPPORT/**@}**/) {
                switch (permissionPolicy) {
                    case DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT: {
                        if (!group.areRuntimePermissionsGranted()) {
                            group.grantRuntimePermissions(false, computeAffectedPermissions(
                                    callingPackageInfo, requestedPermission));
                        }
                        group.setPolicyFixed();
                    } break;

                    case DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY: {
                        if (group.areRuntimePermissionsGranted()) {
                            group.revokeRuntimePermissions(false, computeAffectedPermissions(
                                    callingPackageInfo, requestedPermission));
                        }
                        group.setPolicyFixed();
                    } break;

                    default: {
                        if (!group.areRuntimePermissionsGranted()) {
                            GroupState state = mRequestGrantPermissionGroups.get(group.getName());
                            if (state == null) {
                                state = new GroupState(group);
                                mRequestGrantPermissionGroups.put(group.getName(), state);
                            }
                            String[] affectedPermissions = computeAffectedPermissions(
                                    callingPackageInfo, requestedPermission);
                            if (affectedPermissions != null) {
                                for (String affectedPermission : affectedPermissions) {
                                    state.affectedPermissions = ArrayUtils.appendString(
                                            state.affectedPermissions, affectedPermission);
                                }
                            }
                        } else {
                            group.grantRuntimePermissions(false, computeAffectedPermissions(
                                    callingPackageInfo, requestedPermission));
                            updateGrantResults(group);
                        }
                    } break;
                }
            } else if (!Utils.CTA_SUPPORT) {
                // if the permission is fixed, ensure that we return the right request result
                updateGrantResults(group);
            }

            /// M: CTA requirement - permission control  @{
            if (Utils.CTA_SUPPORT) {
                if (permission == null) {
                    continue;
                }
                dealWithPermissionPolicyforCta(group, permission, permissionPolicy,
                        callingPackageInfo, requestedPermission);
            }
            // /@}
        }

        setContentView(mViewHandler.createView());

        Window window = getWindow();
        WindowManager.LayoutParams layoutParams = window.getAttributes();
        mViewHandler.updateWindowAttributes(layoutParams);
        window.setAttributes(layoutParams);

        if (!showNextPermissionGroupGrantRequest()) {
            setResultAndFinish();
        } else if (icicle == null) {
            int numRequestedPermissions = mRequestedPermissions.length;
            for (int permissionNum = 0; permissionNum < numRequestedPermissions; permissionNum++) {
                String permission = mRequestedPermissions[permissionNum];

                EventLogger.logPermissionRequested(this, permission,
                        mAppPermissions.getPackageInfo().packageName);
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // We need to relayout the window as dialog width may be
        // different in landscape vs portrait which affect the min
        // window height needed to show all content. We have to
        // re-add the window to force it to be resized if needed.
        View decor = getWindow().getDecorView();
        if (decor.getParent() != null) {
            getWindowManager().removeViewImmediate(decor);
            getWindowManager().addView(decor, decor.getLayoutParams());
            if (mViewHandler instanceof GrantPermissionsViewHandlerImpl) {
                ((GrantPermissionsViewHandlerImpl) mViewHandler).onConfigurationChanged();
            }
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        View rootView = getWindow().getDecorView();
        if (rootView.getTop() != 0) {
            // We are animating the top view, need to compensate for that in motion events.
            ev.setLocation(ev.getX(), ev.getY() - rootView.getTop());
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mViewHandler.saveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mViewHandler.loadInstanceState(savedInstanceState);
    }

    /// M: CTA requirement - permission control  @{
    private boolean showNextPermissionGroupGrantRequest() {
        if (Utils.CTA_SUPPORT) {
            return showNextPermissionGroupGrantRequestforCta();
        } else {
            return showNextPermissionGroupGrantRequestforAosp();
        }
    }
    ///@}

    /// M: CTA requirement - permission control  @{
    private boolean showNextPermissionGroupGrantRequestforAosp() {
    ///@}
        final int groupCount = mRequestGrantPermissionGroups.size();

        int currentIndex = 0;
        for (GroupState groupState : mRequestGrantPermissionGroups.values()) {
            if (groupState.mState == GroupState.STATE_UNKNOWN) {
                CharSequence appLabel = mAppPermissions.getAppLabel();
                Spanned message = Html.fromHtml(getString(R.string.permission_warning_template,
                        appLabel, groupState.mGroup.getDescription()), 0);
                // Set the permission message as the title so it can be announced.
                setTitle(message);

                // Set the new grant view
                // TODO: Use a real message for the action. We need group action APIs
                Resources resources;
                try {
                    resources = getPackageManager().getResourcesForApplication(
                            groupState.mGroup.getIconPkg());
                } catch (NameNotFoundException e) {
                    // Fallback to system.
                    resources = Resources.getSystem();
                }
                int icon = groupState.mGroup.getIconResId();

                mViewHandler.updateUi(groupState.mGroup.getName(), groupCount, currentIndex,
                        Icon.createWithResource(resources, icon), message,
                        groupState.mGroup.isUserSet());
                return true;
            }
            currentIndex++;

        }

        return false;
    }

    @Override
    public void onPermissionGrantResult(String name, boolean granted, boolean doNotAskAgain) {
        GroupState groupState = mRequestGrantPermissionGroups.get(name);
        /// M: CTA requirement - permission control.
        if (!Utils.CTA_SUPPORT && groupState.mGroup != null) {
            if (granted) {
                groupState.mGroup.grantRuntimePermissions(doNotAskAgain,
                        groupState.affectedPermissions);
                groupState.mState = GroupState.STATE_ALLOWED;
            } else {
                groupState.mGroup.revokeRuntimePermissions(doNotAskAgain,
                        groupState.affectedPermissions);
                groupState.mState = GroupState.STATE_DENIED;

                int numRequestedPermissions = mRequestedPermissions.length;
                for (int i = 0; i < numRequestedPermissions; i++) {
                    String permission = mRequestedPermissions[i];

                    if (groupState.mGroup.hasPermission(permission)) {
                        EventLogger.logPermissionDenied(this, permission,
                                mAppPermissions.getPackageInfo().packageName);
                    }
                }
            }
            updateGrantResults(groupState.mGroup);
        }

        /// M: CTA requirement - permission control @{
        if (Utils.CTA_SUPPORT) {
            permissionGrantResultforCta(name, granted, doNotAskAgain);
        }
        ///@}

        if (!showNextPermissionGroupGrantRequest()) {
            setResultAndFinish();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)  {
        // We do not allow backing out.
        return keyCode == KeyEvent.KEYCODE_BACK;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event)  {
        // We do not allow backing out.
        return keyCode == KeyEvent.KEYCODE_BACK;
    }

    @Override
    public void finish() {
        setResultIfNeeded(RESULT_CANCELED);
        super.finish();
    }

    private int computePermissionGrantState(PackageInfo callingPackageInfo,
            String permission, int permissionPolicy) {
        boolean permissionRequested = false;

        for (int i = 0; i < callingPackageInfo.requestedPermissions.length; i++) {
            if (permission.equals(callingPackageInfo.requestedPermissions[i])) {
                permissionRequested = true;
                if ((callingPackageInfo.requestedPermissionsFlags[i]
                        & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                    return PERMISSION_GRANTED;
                }
                break;
            }
        }

        if (!permissionRequested) {
            return PERMISSION_DENIED;
        }

        try {
            PermissionInfo pInfo = getPackageManager().getPermissionInfo(permission, 0);
            if ((pInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                    != PermissionInfo.PROTECTION_DANGEROUS) {
                return PERMISSION_DENIED;
            }
            if ((pInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_INSTANT) == 0
                    && callingPackageInfo.applicationInfo.isInstantApp()) {
                return PERMISSION_DENIED;
            }
            if ((pInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_RUNTIME_ONLY) != 0
                    && callingPackageInfo.applicationInfo.targetSdkVersion
                    < Build.VERSION_CODES.M) {
                return PERMISSION_DENIED;
            }
        } catch (NameNotFoundException e) {
            return PERMISSION_DENIED;
        }

        switch (permissionPolicy) {
            case DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT: {
                return PERMISSION_GRANTED;
            }
            default: {
                return PERMISSION_DENIED;
            }
        }
    }

    private PackageInfo getCallingPackageInfo() {
        try {
            return getPackageManager().getPackageInfo(getCallingPackage(),
                    PackageManager.GET_PERMISSIONS);
        } catch (NameNotFoundException e) {
            Log.i(LOG_TAG, "No package: " + getCallingPackage(), e);
            return null;
        }
    }

    private void updateDefaultResults(PackageInfo callingPackageInfo, int permissionPolicy) {
        final int requestedPermCount = mRequestedPermissions.length;
        for (int i = 0; i < requestedPermCount; i++) {
            String permission = mRequestedPermissions[i];
            mGrantResults[i] = callingPackageInfo != null
                    ? computePermissionGrantState(callingPackageInfo, permission, permissionPolicy)
                    : PERMISSION_DENIED;
        }
    }

    private void setResultIfNeeded(int resultCode) {
        if (!mResultSet) {
            mResultSet = true;
            logRequestedPermissionGroups();
            Intent result = new Intent(PackageManager.ACTION_REQUEST_PERMISSIONS);
            result.putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_NAMES, mRequestedPermissions);
            result.putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_RESULTS, mGrantResults);
            setResult(resultCode, result);
        }
    }

    private void setResultAndFinish() {
        setResultIfNeeded(RESULT_OK);
        finish();
    }

    private void logRequestedPermissionGroups() {
        if (mRequestGrantPermissionGroups.isEmpty()) {
            return;
        }

        final int groupCount = mRequestGrantPermissionGroups.size();
        List<AppPermissionGroup> groups = new ArrayList<>(groupCount);
        for (GroupState groupState : mRequestGrantPermissionGroups.values()) {
            groups.add(groupState.mGroup);
        }

        SafetyNetLogger.logPermissionsRequested(mAppPermissions.getPackageInfo(), groups);
    }

    private static String[] computeAffectedPermissions(PackageInfo callingPkg,
            String permission) {
        // For <= N_MR1 apps all permissions are affected.
        if (callingPkg.applicationInfo.targetSdkVersion <= Build.VERSION_CODES.N_MR1
                && !Utils.CTA_SUPPORT) {
            return null;
        }

        // For N_MR1+ apps only the requested permission is affected with addition
        // to splits of this permission applicable to apps targeting N_MR1.
        String[] permissions = new String[] {permission};
        for (PackageParser.SplitPermissionInfo splitPerm : PackageParser.SPLIT_PERMISSIONS) {
            if (splitPerm.targetSdk <= Build.VERSION_CODES.N_MR1
                    || callingPkg.applicationInfo.targetSdkVersion >= splitPerm.targetSdk
                    || !permission.equals(splitPerm.rootPerm)) {
                continue;
            }
            for (int i = 0; i < splitPerm.newPerms.length; i++) {
                final String newPerm = splitPerm.newPerms[i];
                permissions = ArrayUtils.appendString(permissions, newPerm);
            }
        }

        return permissions;
    }

    private static final class GroupState {
        static final int STATE_UNKNOWN = 0;
        static final int STATE_ALLOWED = 1;
        static final int STATE_DENIED = 2;

        final AppPermissionGroup mGroup;
        int mState = STATE_UNKNOWN;
        String[] affectedPermissions;

        GroupState(AppPermissionGroup group) {
            mGroup = group;
        }
    }

    /// M: CTA requirement - permission control  @{
    private LinkedHashMap<String, PermissionState> mRequestGrantPermissions = new LinkedHashMap<>();

    private void updateGrantResults(AppPermissionGroup group) {
        for (Permission permission : group.getPermissions()) {
            updateGrantResults(permission);
        }
    }

    private void updateGrantResults(Permission permission) {
        final int index = ArrayUtils.indexOf(
                mRequestedPermissions, permission.getName());
        if (index >= 0) {
            mGrantResults[index] = permission.isGranted() ? PackageManager.PERMISSION_GRANTED
                    : PackageManager.PERMISSION_DENIED;
        }
    }

    private boolean showNextPermissionGroupGrantRequestforCta() {
        int currentIndex = 0;
        final int permCount = mRequestGrantPermissions.size();
        for (PermissionState permState : mRequestGrantPermissions.values()) {
            if (permState.getState() == PermissionState.STATE_UNKNOWN) {
                CharSequence appLabel = mAppPermissions.getAppLabel();
                PermissionInfo permInfo;
                try {
                    permInfo = getPackageManager().getPermissionInfo(
                               permState.getPermission().getName(),
                               0);
                } catch (NameNotFoundException e) {
                    continue;
                }
                Spanned message = Html.fromHtml(
                        getString(R.string.permission_warning_template,
                                  appLabel,
                                  permInfo.loadLabel(getPackageManager())),
                                  0);
                // Set the permission message as the title so it can be announced.
                setTitle(message);

                Resources resources;
                try {
                    resources = getPackageManager().getResourcesForApplication(
                                permState.getAppPermissionGroup().getIconPkg());
                } catch (NameNotFoundException e) {
                    // Fallback to system.
                    resources = Resources.getSystem();
                }
                int icon = permState.getAppPermissionGroup().getIconResId();

                mViewHandler.updateUi(permState.getPermission().getName(),
                        permCount, currentIndex,
                        Icon.createWithResource(resources, icon),
                        message,
                        permState.getPermission().isUserSet());
                return true;
            }
            currentIndex++;
        }
        return false;
    }

    private void permissionGrantResultforCta(String name, boolean granted, boolean doNotAskAgain) {
        PermissionState permState = mRequestGrantPermissions.get(name);
        if (permState.getPermission() != null && permState.getAppPermissionGroup() != null) {
            if (granted) {
                permState.getAppPermissionGroup().grantRuntimePermissions(
                                doNotAskAgain,
                                new String[] { permState.getPermission().getName() });
                permState.setState(PermissionState.STATE_ALLOWED);
            } else {
                permState.getAppPermissionGroup().revokeRuntimePermissions(
                                doNotAskAgain,
                                new String[] { permState.getPermission().getName() });
                permState.setState(PermissionState.STATE_DENIED);
            }
            updateGrantResults(permState.getPermission());
        }
    }

    private void dealWithPermissionPolicyforCta(AppPermissionGroup group, Permission permission,
            int permissionPolicy, PackageInfo callingPackageInfo, String requestedPermission) {
        if (!permission.isUserFixed() && !permission.isPolicyFixed()) {
            switch (permissionPolicy) {
                case DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT: {
                    if (!group.areRuntimePermissionsGranted()) {
                        group.grantRuntimePermissions(false, new String[] { requestedPermission });
                        permission.setPolicyFixed(true);
                        getPackageManager().updatePermissionFlags(
                                    permission.getName(),
                                    callingPackageInfo.packageName,
                                    PackageManager.FLAG_PERMISSION_POLICY_FIXED,
                                    PackageManager.FLAG_PERMISSION_POLICY_FIXED,
                                    new UserHandle(group.getUserId()));
                    }
                } break;

                case DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY: {
                    if (group.areRuntimePermissionsGranted()) {
                        group.revokeRuntimePermissions(false,
                            new String[] { requestedPermission });
                        permission.setPolicyFixed(true);
                        getPackageManager().updatePermissionFlags(
                                    permission.getName(),
                                    callingPackageInfo.packageName,
                                    PackageManager.FLAG_PERMISSION_POLICY_FIXED,
                                    PackageManager.FLAG_PERMISSION_POLICY_FIXED,
                                    new UserHandle(group.getUserId()));
                    }
                } break;

                default: {
                    if (!group.areRuntimePermissionsGranted(new String[] { requestedPermission })) {
                        mRequestGrantPermissions.put(requestedPermission,
                        new PermissionState(group, permission));
                    } else {
                        group.grantRuntimePermissions(false, new String[] { requestedPermission });
                        updateGrantResults(permission);
                    }
                } break;
            }
        } else {
            updateGrantResults(permission);
        }
    }
    ///@}
}
