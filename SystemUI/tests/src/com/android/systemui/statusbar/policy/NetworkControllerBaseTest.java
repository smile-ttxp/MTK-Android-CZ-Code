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

package com.android.systemui.statusbar.policy;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Looper;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.ims.ImsManager;
import com.android.ims.ImsConnectionStateListener;
import com.android.ims.ImsServiceClass;

import com.android.internal.telephony.cdma.EriInfo;
import com.android.settingslib.net.DataUsageController;
import com.android.systemui.statusbar.phone.SignalDrawable;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;
import com.android.systemui.statusbar.policy.NetworkControllerImpl.Config;
import com.android.systemui.statusbar.policy.NetworkControllerImpl.SubscriptionDefaults;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static junit.framework.Assert.assertEquals;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class NetworkControllerBaseTest extends SysuiTestCase {
    private static final String TAG = "NetworkControllerBaseTest";
    protected static final int DEFAULT_LEVEL = 2;
    protected static final int DEFAULT_SIGNAL_STRENGTH = DEFAULT_LEVEL;
    protected static final int DEFAULT_QS_SIGNAL_STRENGTH = DEFAULT_LEVEL;
    protected static final int DEFAULT_ICON = TelephonyIcons.ICON_3G;
    protected static final int DEFAULT_QS_ICON = TelephonyIcons.QS_DATA_3G;

    protected NetworkControllerImpl mNetworkController;
    protected MobileSignalController mMobileSignalController;
    protected PhoneStateListener mPhoneStateListener;
    protected SignalStrength mSignalStrength;
    protected ServiceState mServiceState;
    protected ConnectivityManager mMockCm;
    protected WifiManager mMockWm;
    protected SubscriptionManager mMockSm;
    protected TelephonyManager mMockTm;
    protected Config mConfig;
    protected CallbackHandler mCallbackHandler;
    protected SubscriptionDefaults mMockSubDefaults;
    protected DeviceProvisionedController mMockProvisionController;
    protected DeviceProvisionedListener mUserCallback;

    protected int mSubId;

    private NetworkCapabilities mNetCapabilities;
    /// M: for volte icon @{
    //Indices map to ImsConfig.FeatureConstants
    private int[] imsFeatureEnabledVolte = {FEATURE_TYPE_VOICE_OVER_LTE, -1, -1, -1, -1, -1};
    private int[] imsFeatureEnabledVoWifi= {-1, -1, FEATURE_TYPE_VOICE_OVER_WIFI, -1, -1, -1};
    //{"VoLTE", "ViLTE", "VoWiFi", "ViWiFi","UTLTE", "UTWiFi"};
    private ImsConnectionStateListener mImsConnectionStateListener;
    public static final int FEATURE_TYPE_VOICE_OVER_LTE = 0;
    public static final int FEATURE_TYPE_VOICE_OVER_WIFI = 2;
    /// @}

    @Rule
    public TestWatcher failWatcher = new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
            // Print out mNetworkController state if the test fails.
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            mNetworkController.dump(null, pw, null);
            pw.flush();
            Log.d(TAG, sw.toString());
        }
    };

    @Before
    public void setUp() throws Exception {
        Settings.Global.putInt(mContext.getContentResolver(), Global.AIRPLANE_MODE_ON, 0);
        mMockWm = mock(WifiManager.class);
        mMockTm = mock(TelephonyManager.class);
        mMockSm = mock(SubscriptionManager.class);
        mMockCm = mock(ConnectivityManager.class);
        mMockSubDefaults = mock(SubscriptionDefaults.class);
        mNetCapabilities = new NetworkCapabilities();
        when(mMockCm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)).thenReturn(true);
        when(mMockCm.getDefaultNetworkCapabilitiesForUser(0)).thenReturn(
                new NetworkCapabilities[] { mNetCapabilities });

        mSignalStrength = mock(SignalStrength.class);
        mServiceState = mock(ServiceState.class);

        mConfig = new Config();
        mConfig.hspaDataDistinguishable = true;
        mCallbackHandler = mock(CallbackHandler.class);

        mMockProvisionController = mock(DeviceProvisionedController.class);
        when(mMockProvisionController.isUserSetup(anyInt())).thenReturn(true);
        doAnswer(invocation -> {
            mUserCallback = (DeviceProvisionedListener) invocation.getArguments()[0];
            mUserCallback.onUserSetupChanged();
            mUserCallback.onDeviceProvisionedChanged();
            return null;
        }).when(mMockProvisionController).addCallback(any());

        mNetworkController = new NetworkControllerImpl(mContext, mMockCm, mMockTm, mMockWm, mMockSm,
                mConfig, Looper.getMainLooper(), mCallbackHandler,
                mock(AccessPointControllerImpl.class), mock(DataUsageController.class),
                mMockSubDefaults, mMockProvisionController);
        setupNetworkController();

        // Trigger blank callbacks to always get the current state (some tests don't trigger
        // changes from default state).
        mNetworkController.addCallback(mock(SignalCallback.class));
        mNetworkController.addEmergencyListener(null);
    }

    protected void setupNetworkController() {
        // For now just pretend to be the data sim, so we can test that too.
        mSubId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
        when(mMockTm.getDataEnabled(mSubId)).thenReturn(true);
        setDefaultSubId(mSubId);
        setSubscriptions(mSubId);
        mMobileSignalController = mNetworkController.mMobileSignalControllers.get(mSubId);
        mPhoneStateListener = mMobileSignalController.mPhoneStateListener;
        mImsConnectionStateListener = mMobileSignalController.mImsConnectionStateListener;
    }

    protected void setDefaultSubId(int subId) {
        when(mMockSubDefaults.getDefaultDataSubId()).thenReturn(subId);
        when(mMockSubDefaults.getDefaultVoiceSubId()).thenReturn(subId);
    }

    protected void setSubscriptions(int... subIds) {
        List<SubscriptionInfo> subs = new ArrayList<SubscriptionInfo>();
        for (int subId : subIds) {
            SubscriptionInfo subscription = mock(SubscriptionInfo.class);
            when(subscription.getSubscriptionId()).thenReturn(subId);
            subs.add(subscription);
            /// M: support volte test.
            when(subscription.getSimSlotIndex()).thenReturn(subId);
        }
        when(mMockSm.getActiveSubscriptionInfoList()).thenReturn(subs);
        mNetworkController.doUpdateMobileControllers();
    }

    protected NetworkControllerImpl setUpNoMobileData() {
      when(mMockCm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)).thenReturn(false);
      NetworkControllerImpl networkControllerNoMobile
              = new NetworkControllerImpl(mContext, mMockCm, mMockTm, mMockWm, mMockSm,
                        mConfig, mContext.getMainLooper(), mCallbackHandler,
                        mock(AccessPointControllerImpl.class),
                        mock(DataUsageController.class), mMockSubDefaults,
                        mock(DeviceProvisionedController.class));

      setupNetworkController();

      return networkControllerNoMobile;

    }

    // 2 Bars 3G GSM.
    public void setupDefaultSignal() {
        setIsGsm(true);
        setVoiceRegState(ServiceState.STATE_IN_SERVICE);
        setGsmRoaming(false);
        setLevel(DEFAULT_LEVEL);
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_UMTS);
        setConnectivity(NetworkCapabilities.TRANSPORT_CELLULAR, true, true);
    }

    public void setConnectivity(int networkType, boolean inetCondition, boolean isConnected) {
        Intent i = new Intent(ConnectivityManager.INET_CONDITION_ACTION);
        // TODO: Separate out into several NetworkCapabilities.
        if (isConnected) {
            mNetCapabilities.addTransportType(networkType);
        } else {
            mNetCapabilities.removeTransportType(networkType);
        }
        if (inetCondition) {
            mNetCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } else {
            mNetCapabilities.removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }

        mNetworkController.onReceive(mContext, i);
    }

    public void setGsmRoaming(boolean isRoaming) {
        when(mServiceState.getRoaming()).thenReturn(isRoaming);
        updateServiceState();
    }

    public void setCdmaRoaming(boolean isRoaming) {
        when(mServiceState.getCdmaEriIconIndex()).thenReturn(isRoaming ?
                EriInfo.ROAMING_INDICATOR_ON : EriInfo.ROAMING_INDICATOR_OFF);
        when(mServiceState.getCdmaEriIconMode()).thenReturn(isRoaming ?
                EriInfo.ROAMING_ICON_MODE_NORMAL : -1);
        updateServiceState();
    }

    public void setVoiceRegState(int voiceRegState) {
        when(mServiceState.getVoiceRegState()).thenReturn(voiceRegState);
        updateServiceState();
    }

    /// M: For network type big icon test @{
    public void setNetworkType(int networkType){
        when(mServiceState.getDataNetworkType()).thenReturn(TelephonyManager.NETWORK_TYPE_UNKNOWN);
        when(mServiceState.getVoiceNetworkType()).thenReturn(networkType);
        updateServiceState();
    }
    /// @}

    /// M: For 4G+ icon test @{
    public void setUsingCarrierAggregation(boolean value){
        when(mServiceState.isUsingCarrierAggregation()).thenReturn(value);
        updateServiceState();
    }
    /// @}

    /// M: For Volte icon test @{
    public void updateImsConnectionState(int imsState, int[] enabledFeatures){
        if (imsState == ServiceState.STATE_IN_SERVICE) {
            mImsConnectionStateListener.onImsConnected(imsState);
        } else if (imsState == ServiceState.STATE_OUT_OF_SERVICE){
            mImsConnectionStateListener.onImsDisconnected(null);
        }

        mImsConnectionStateListener.onFeatureCapabilityChanged(ImsServiceClass.MMTEL,
                enabledFeatures, null);
    }
    /// @}

    public void setDataRegState(int dataRegState) {
        when(mServiceState.getDataRegState()).thenReturn(dataRegState);
        updateServiceState();
    }

    public void setIsEmergencyOnly(boolean isEmergency) {
        when(mServiceState.isEmergencyOnly()).thenReturn(isEmergency);
        updateServiceState();
    }

    public void setCdmaLevel(int level) {
        when(mSignalStrength.getCdmaLevel()).thenReturn(level);
        updateSignalStrength();
    }

    public void setLevel(int level) {
        when(mSignalStrength.getLevel()).thenReturn(level);
        updateSignalStrength();
    }

    public void setIsGsm(boolean gsm) {
        when(mSignalStrength.isGsm()).thenReturn(gsm);
        updateSignalStrength();
    }

    public void setCdmaEri(int index, int mode) {
        // TODO: Figure this out.
    }

    private void updateSignalStrength() {
        Log.d(TAG, "Sending Signal Strength: " + mSignalStrength);
        mPhoneStateListener.onSignalStrengthsChanged(mSignalStrength);
    }

    protected void updateServiceState() {
        Log.d(TAG, "Sending Service State: " + mServiceState);
        mPhoneStateListener.onServiceStateChanged(mServiceState);
    }

    public void updateCallState(int state) {
        // Inputs not currently used in NetworkControllerImpl.
        mPhoneStateListener.onCallStateChanged(state, "0123456789");
    }

    public void updateDataConnectionState(int dataState, int dataNetType) {
        when(mServiceState.getDataNetworkType()).thenReturn(dataNetType);
        mPhoneStateListener.onDataConnectionStateChanged(dataState, dataNetType);
    }

    public void updateDataActivity(int dataActivity) {
        mPhoneStateListener.onDataActivity(dataActivity);
    }

    public void setCarrierNetworkChange(boolean enable) {
        Log.d(TAG, "setCarrierNetworkChange(" + enable + ")");
        mPhoneStateListener.onCarrierNetworkChange(enable);
    }

    protected void verifyHasNoSims(boolean hasNoSimsVisible) {
        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setNoSims(
                eq(hasNoSimsVisible), eq(false));
    }

    protected void verifyLastQsMobileDataIndicators(boolean visible, int icon, int typeIcon,
            boolean dataIn, boolean dataOut) {
        ArgumentCaptor<IconState> iconArg = ArgumentCaptor.forClass(IconState.class);
        ArgumentCaptor<Integer> typeIconArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> dataInArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> dataOutArg = ArgumentCaptor.forClass(Boolean.class);
        ///M: Add for interface modify
        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setMobileDataIndicators(
                    any(),
                    iconArg.capture(),
                    anyInt(),
                    ArgumentCaptor.forClass(Integer.class).capture(),
                    ArgumentCaptor.forClass(Integer.class).capture(),
                    typeIconArg.capture(), dataInArg.capture(), dataOutArg.capture(),
                    anyString(), anyString(), anyBoolean(), anyInt(), anyBoolean());
        IconState iconState = iconArg.getValue();
        int state = SignalDrawable.getState(icon, SignalStrength.NUM_SIGNAL_STRENGTH_BINS,
                false);
        assertEquals("Visibility in, quick settings", visible, iconState.visible);
        assertEquals("Signal icon in, quick settings", state, iconState.icon);
        assertEquals("Data icon in, quick settings", typeIcon, (int) typeIconArg.getValue());
        assertEquals("Data direction in, in quick settings", dataIn,
                (boolean) dataInArg.getValue());
        assertEquals("Data direction out, in quick settings", dataOut,
                (boolean) dataOutArg.getValue());
    }

    protected void verifyLastMobileDataIndicators(boolean visible, int icon, int typeIcon) {
        verifyLastMobileDataIndicators(visible, icon, typeIcon, false);
    }

    protected void verifyLastMobileDataIndicators(boolean visible, int icon, int typeIcon,
            boolean roaming) {
        verifyLastMobileDataIndicators(visible, icon, typeIcon, roaming, true);
    }

    protected void verifyLastMobileDataIndicators(boolean visible, int icon, int typeIcon,
        boolean roaming, boolean inet) {
        ArgumentCaptor<IconState> iconArg = ArgumentCaptor.forClass(IconState.class);
        ArgumentCaptor<Integer> typeIconArg = ArgumentCaptor.forClass(Integer.class);
         // TODO: Verify all fields.
        ///M: Add for interface modify
        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setMobileDataIndicators(
                iconArg.capture(),
                any(),
                typeIconArg.capture(),
                ArgumentCaptor.forClass(Integer.class).capture(),
                ArgumentCaptor.forClass(Integer.class).capture(),
                anyInt(), anyBoolean(), anyBoolean(), anyString(), anyString(), anyBoolean(),
                anyInt(), eq(roaming));
        IconState iconState = iconArg.getValue();

        int state = icon == -1 ? 0
                : SignalDrawable.getState(icon, SignalStrength.NUM_SIGNAL_STRENGTH_BINS, !inet);
        assertEquals("Signal icon in status bar", state, iconState.icon);
        assertEquals("Data icon in status bar", typeIcon, (int) typeIconArg.getValue());
        assertEquals("Visibility in status bar", visible, iconState.visible);
    }

    protected void verifyLastMobileDataIndicators(boolean visible, int icon, int typeIcon,
            boolean qsVisible, int qsIcon, int qsTypeIcon, boolean dataIn, boolean dataOut) {
        ArgumentCaptor<IconState> iconArg = ArgumentCaptor.forClass(IconState.class);
        ArgumentCaptor<Integer> typeIconArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<IconState> qsIconArg = ArgumentCaptor.forClass(IconState.class);
        ArgumentCaptor<Integer> qsTypeIconArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> dataInArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> dataOutArg = ArgumentCaptor.forClass(Boolean.class);

        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setMobileDataIndicators(
                iconArg.capture(),
                qsIconArg.capture(),
                typeIconArg.capture(),
                ArgumentCaptor.forClass(Integer.class).capture(),
                ArgumentCaptor.forClass(Integer.class).capture(),
                qsTypeIconArg.capture(),
                dataInArg.capture(),
                dataOutArg.capture(),
                anyString(), anyString(), anyBoolean(), anyInt(), anyBoolean());

        IconState iconState = iconArg.getValue();

        int state = SignalDrawable.getState(icon, SignalStrength.NUM_SIGNAL_STRENGTH_BINS,
                false);
        assertEquals("Data icon in status bar", typeIcon, (int) typeIconArg.getValue());
        assertEquals("Signal icon in status bar", state, iconState.icon);
        assertEquals("Visibility in status bar", visible, iconState.visible);

        iconState = qsIconArg.getValue();
        assertEquals("Visibility in quick settings", qsVisible, iconState.visible);
        assertEquals("Signal icon in quick settings", state, iconState.icon);
        assertEquals("Data icon in quick settings", qsTypeIcon, (int) qsTypeIconArg.getValue());
        assertEquals("Data direction in in quick settings", dataIn,
                (boolean) dataInArg.getValue());
        assertEquals("Data direction out in quick settings", dataOut,
                (boolean) dataOutArg.getValue());
    }

    /// M: For network type big icon  and volte icon test.@{
    protected void verifyLastMobileNetworkIcon(int icon){
        ArgumentCaptor<Integer> iconArg = ArgumentCaptor.forClass(Integer.class);
        ///M: Add for interface modify
        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setMobileDataIndicators(
            ArgumentCaptor.forClass(IconState.class).capture(),
                ArgumentCaptor.forClass(IconState.class).capture(),
                ArgumentCaptor.forClass(Integer.class).capture(),
                iconArg.capture(),
                ArgumentCaptor.forClass(Integer.class).capture(),
                ArgumentCaptor.forClass(Integer.class).capture(),
                ArgumentCaptor.forClass(Boolean.class).capture(),
                ArgumentCaptor.forClass(Boolean.class).capture(),
                ArgumentCaptor.forClass(String.class).capture(),
                ArgumentCaptor.forClass(String.class).capture(),
                ArgumentCaptor.forClass(Boolean.class).capture(),
                ArgumentCaptor.forClass(Integer.class).capture(), anyBoolean());

        assertEquals("network icon in status bar", icon, (int) iconArg.getValue());
    }
    /// @}

    /// M: For vlote icon test @{
    protected void volteIconTest(int dataState, int dataNetType,
            int serviceState, int iconId) {
        // Stubbing collaborators.
        updateDataConnectionState(dataState,dataNetType);
        //Invocation Ims action.
        updateImsConnectionState(serviceState, imsFeatureEnabledVolte);
        //Verify volte icon.
        verifyLastVolteIcon(iconId);
    }

    protected void verifyLastVolteIcon(int icon){
        ArgumentCaptor<Integer> volteIconArg = ArgumentCaptor.forClass(Integer.class);
        ///M: Add for interface modify
        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setMobileDataIndicators(
            ArgumentCaptor.forClass(IconState.class).capture(),
                ArgumentCaptor.forClass(IconState.class).capture(),
                ArgumentCaptor.forClass(Integer.class).capture(),
                ArgumentCaptor.forClass(Integer.class).capture(),
                volteIconArg.capture(),
                ArgumentCaptor.forClass(Integer.class).capture(),
                ArgumentCaptor.forClass(Boolean.class).capture(),
                ArgumentCaptor.forClass(Boolean.class).capture(),
                ArgumentCaptor.forClass(String.class).capture(),
                ArgumentCaptor.forClass(String.class).capture(),
                ArgumentCaptor.forClass(Boolean.class).capture(),
                ArgumentCaptor.forClass(Integer.class).capture(), anyBoolean());

        assertEquals("volte icon in status bar", icon, (int) volteIconArg.getValue());
    }
    /// @}

   protected void assertNetworkNameEquals(String expected) {
       assertEquals("Network name", expected, mMobileSignalController.getState().networkName);
   }
}
