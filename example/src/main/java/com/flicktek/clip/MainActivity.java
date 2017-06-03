/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.flicktek.clip;

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.flicktek.clip.eventbus.DeviceToPhoneEvent;
import com.flicktek.clip.eventbus.DeviceToWearEvent;
import com.flicktek.clip.profile.BleProfileService;
import com.flicktek.clip.profile.BleProfileServiceReadyActivity;
import com.flicktek.clip.uart.UARTInterface;
import com.flicktek.clip.uart.UARTService;
import com.flicktek.clip.upload.UploadGestureAsync;
import com.flicktek.example.FlicktekClipApplication;
import com.flicktek.example.R;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Receives its own events using a listener API designed for foreground activities. Updates a data
 * item every second while it is open. Also allows user to take a photo and send that as an asset
 * to the paired wearable.
 */
public class MainActivity extends BleProfileServiceReadyActivity<UARTService.UARTBinder>
        implements UARTInterface, FlicktekCommands.UARTInterface, FlicktekManager.BackMenu {
    private static final String TAG = "MainActivity";

    public Tracker mTracker;

    public TextView tv_battery;
    private ImageView iv_battery;
    public LinearLayout ll_battery;
    private TextView tv_current_menu;

    private View mDecorView;

    public static final int PERMISSION_REQUEST_WRITING_EXTERNAL = 100;

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    // This snippet hides the system bars.
    private void hideSystemUI() {
        // Set the IMMERSIVE flag.
        // Set the content to appear under the system bars so that the content
        // doesn't resize when the system bars hide and show.
        mDecorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // hide nav bar
                        | View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar
                        | View.SYSTEM_UI_FLAG_IMMERSIVE);
    }

    ///////////////////////////////////////////////////////////////////////////
    //                      End app-specific settings.                       //
    ///////////////////////////////////////////////////////////////////////////


    private boolean mResolvingError = false;

    private UARTService.UARTBinder mServiceBinder;

    private Bundle config;

    FlicktekBleFragment mFlickTekGraphs;

    @Override
    protected UUID getFilterUUID() {
        return null; // not used
    }

    String menuName = null;

    public void initializeBatteryDisplay() {
        // --- Battery layouts and display ---
        ll_battery = (LinearLayout) findViewById(R.id.ll_battery);
        tv_battery = (TextView) findViewById(R.id.tv_battery_level);
        iv_battery = (ImageView) findViewById(R.id.iv_battery);

        ll_battery.setVisibility(View.INVISIBLE);
        // --- Battery layouts and display ---

        if (tv_current_menu != null) {
            if (menuName != null) {
                tv_current_menu = (TextView) findViewById(R.id.tv_current_menu);
                tv_current_menu.setText(menuName);
            } else {
                tv_current_menu.setVisibility(View.GONE);
            }
        }

        updateBattery(FlicktekManager.getInstance().getBatteryLevel());
    }

    SharedPreferences mSharedPreferences;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        FlicktekClipApplication application = (FlicktekClipApplication) getApplication();
        mTracker = application.getDefaultTracker();
        super.onCreate(savedInstanceState);
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        FlicktekCommands.getInstance().init(getApplicationContext());
        mDecorView = getWindow().getDecorView();

        config = getIntent().getExtras();
        initializeBatteryDisplay();

        FlicktekSettings.getInstance().setPreferencesActivity(this);
    }

    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
    }

    @Override
    public void onResume() {
        super.onResume();
        EventBus.getDefault().register(this);
        FlicktekCommands.getInstance().setApplicationFocus(true);
    }

    @Override
    public void onPause() {
        super.onPause();
        FlicktekCommands.getInstance().setApplicationFocus(false);
        EventBus.getDefault().unregister(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onServiceBinded(final UARTService.UARTBinder binder) {
        mServiceBinder = binder;
        FlicktekCommands.getInstance().registerDataChannel(this);
    }

    @Override
    protected void onServiceUnbinded() {
        mServiceBinder = null;
    }

    @Override
    protected Class<? extends BleProfileService> getServiceClass() {
        return UARTService.class;
    }

    @Override
    protected void onCreateView(Bundle savedInstanceState) {
        config = getIntent().getExtras();
        setContentView(R.layout.activity_fragments);
        setupViews();
    }

    @Override
    protected void setDefaultUI() {

    }

    @Override
    protected int getDefaultDeviceName() {
        return R.string.app_name;
    }

    @Override
    protected int getAboutTextId() {
        return R.string.app_name;
    }

    public void showFragment(final Fragment _fragment, final String name, final boolean _isSameView) {
        if (mFlickTekGraphs != _fragment && _fragment instanceof FlicktekBleFragment) {
            mFlickTekGraphs = (FlicktekBleFragment) _fragment;
        } else
            mFlickTekGraphs = null;

        Log.d(TAG, "showFragment: ");
        runOnUiThread(new Runnable() {

            public void run() {
                try {
                    initializeBatteryDisplay();
                    FragmentManager fragmentManager = getFragmentManager();
                    FragmentTransaction transaction = fragmentManager.beginTransaction();

                    transaction.replace(R.id.container, _fragment, name);
                    transaction.commit();
                } catch (Exception e) {
                    e.printStackTrace();
                    finish();
                }
            }
        });

        mTracker.send(new HitBuilders.EventBuilder()
                .setCategory("Navigation")
                .setAction(_fragment.getTag())
                .build());
        Log.d(TAG, "End track: ");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        if (intent != null) {
            config = getIntent().getExtras();
            if (config != null)
                setupViews();
        }
    }

    /**
     * Sets up UI components and their callback handlers.
     */
    private void setupViews() {
        Fragment fragment = FlicktekBleFragment.newInstance("Ble", "");
        showFragment(fragment, "Factory Graphs", true);
    }

    public void showMessage(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onGesturePerformed(FlicktekCommands.onGestureEvent gestureEvent) {
    }

    @Nullable
    public Fragment getFragment(String classFragment) {
        String packageName = getPackageName();
        String className = packageName + "." + classFragment;

        Fragment myFragment = null;

        // Build Fragment from class name
        Class<?> clazz = null;
        try {
            clazz = Class.forName(className);
            Constructor<?> ctor = null;
            ctor = clazz.getConstructor();
            Object object = ctor.newInstance();
            myFragment = (Fragment) object;
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException |
                IllegalAccessException | InvocationTargetException e) {
            //e.printStackTrace();
            return null;
        }
        return myFragment;
    }

    public void newFragmentFromClassName(String classFragment) {
        Fragment frg = getFragment(classFragment);
        if (frg != null) {
            showFragment(frg, classFragment, false);
        }
    }

    // Store the old battery levels so we don't update in case we already did
    static int old_battery = 0;
    static int battery_level = 0;
    static LinearLayout old_battery_layout = null;

    public void setBatteryUI(LinearLayout battery_layout, TextView battery_text, ImageView battery_image) {
        if (tv_battery != null && tv_battery != battery_text)
            tv_battery.setVisibility(View.GONE);

        if (iv_battery != null && iv_battery != battery_image)
            iv_battery.setVisibility(View.GONE);

        if (ll_battery != null && ll_battery != battery_layout)
            ll_battery.setVisibility(View.GONE);

        tv_battery = battery_text;
        iv_battery = battery_image;
        ll_battery = battery_layout;
    }

    public void updateBattery(int value) {
        TextView battery_text = tv_battery;
        ImageView battery_image = iv_battery;
        LinearLayout battery_layout = ll_battery;

        if (value == 0) {
            battery_layout.setVisibility(View.INVISIBLE);
            battery_image.setVisibility(View.INVISIBLE);
            return;
        }

        battery_layout.setVisibility(View.VISIBLE);
        battery_image.setVisibility(View.VISIBLE);

        battery_level = value;

        // If we are in a different menu we have to po pulate the values
        if (old_battery_layout != battery_layout)
            old_battery = 0;

        if (battery_level == old_battery)
            return;

        old_battery = battery_level;
        old_battery_layout = battery_layout;

        battery_text.setText(battery_level + "%");
        battery_layout.setVisibility(View.VISIBLE);

        int res;

        if (battery_level < 5)
            res = R.drawable.ic_batt_empty;
        else if (battery_level < 15)
            res = R.drawable.ic_batt_1;
        else if (battery_level < 30)
            res = R.drawable.ic_batt_2;
        else if (battery_level < 50)
            res = R.drawable.ic_batt_3;
        else if (battery_level < 75)
            res = R.drawable.ic_batt_4;
        else if (battery_level < 90)
            res = R.drawable.ic_batt_5;
        else
            res = R.drawable.ic_batt_full;

        battery_image.setImageResource(res);
    }

    @Override
    public void send(String text) {

    }

    public void addSampleData(int sensorValues[]) {
        if (mFlickTekGraphs != null)
            mFlickTekGraphs.onAddSamples(sensorValues);
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, final @NonNull String[] permissions, final @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case PERMISSION_REQUEST_WRITING_EXTERNAL:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(MainActivity.this, "Granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Writing to extorage permission required", Toast.LENGTH_SHORT).show();
                    finish();
                }
                break;
        }
    }

    //------------------------------------------------------------------------------

    public static int mSensorNumber = 0;
    public static int mSamplesProcessed = 0;
    public static int mErrorFound = 0;

    int mSensorValues[] = new int[4];

    public void processSampleData(byte[] buf) {
        // If there was a transmission error '----' is stored as the sensor values;
        if (buf.length < 20) {
            return;
        }

        // Check if we have an error
        int next_sample_start = 0;
        boolean print_hex = false;
        for (int n = 0; n < 20; n++) {
            int value = buf[n] & 0xFF;

            if (value == 0xFF) {
                mErrorFound++;
            } else {
                mErrorFound = 0;
            }

            // Found a reset command!
            if (mErrorFound == 8) {
                mSensorNumber = 0;
                mErrorFound = 0;
                mSamplesProcessed = 0;
                Log.v(TAG, "---------- SPURIOUS DATA ---------");
                next_sample_start = n + 1;

                UARTService.printHex(buf);
                print_hex = true;
                for (int c = 0; c < 4; c++)
                    mSensorValues[c] = 5500;

                addSampleData(mSensorValues);
                mSensorValues = new int[4];

                if (next_sample_start == 20) {
                    //Log.v(TAG, "END OF DATA");
                    return;
                }
                break;
            }
        }

        //UARTService.printHex(buf);

        int p = next_sample_start;
        for (int n = next_sample_start / 2; n < 10; n++) {
            byte[] arr = {buf[p], buf[p + 1]};

            int low = buf[p] & 0xFF;
            int high = (buf[p + 1] & 0xFF) << 8;
            int mynumber = low + high;
            int number = mynumber;
            try {
                ByteBuffer wrapped = ByteBuffer.wrap(arr); // big-endian by default
                wrapped.order(ByteOrder.LITTLE_ENDIAN);
                number = wrapped.getShort();
            } catch (Exception e) {
                e.printStackTrace();
            }

            p += 2;

            mSensorValues[mSensorNumber++] = number;
            if (mSensorNumber == 4) {
                mSensorNumber = 0;
                mSamplesProcessed++;

                if (print_hex) {
                    Log.v(TAG, "Sample " + mSamplesProcessed + ": ("
                            + mSensorValues[0] + ","
                            + mSensorValues[1] + ","
                            + mSensorValues[2] + ","
                            + mSensorValues[3] + ")");
                }
                addSampleData(mSensorValues);
                mSensorValues = new int[4];
            }
        }
    }

    @Override
    public void processSampleData(int sample, byte[] data) {
        processSampleData(data);
    }

    public void processSimpleCommand(char cmd, char value) {
        if (mFlickTekGraphs != null)
            mFlickTekGraphs.onGesture(value);
    }

    @Override
    public void processTextData(String data) {
        if (data == null || data.length() == 0)
            return;

        byte[] cmd = data.getBytes();
        if (cmd.length < 4) {
            return;
        }

        if (cmd[0] == '{' && cmd[3] == '}') {
            char command = (char) cmd[1];
            char value = (char) (cmd[2] - '0');
            Log.v(TAG, "Got command " + data);
            processSimpleCommand(command, value);
        } else {
            Log.v(TAG, "Process text " + data);
        }
    }

    @Override
    public void sendString(String data) {
        if (mServiceBinder != null) {
            mServiceBinder.send(data);
        }
    }

    @Override
    public void sendDataBuffer(byte[] data) {
        // This is temporal, add the byte[] interface
        if (mServiceBinder != null) {
            mServiceBinder.send(new String(data));
        }
    }

    // HACK, HACK...
    public void forceConnection() {
        // We have to disconnect for the DFU to be able to connect
        if (mServiceBinder != null) {
            if (mServiceBinder.connect()) {
                Log.v(TAG, "Service has everything to try to connect");
                return;
            }
        }

        final Intent service = new Intent(this, getServiceClass());

        String mac = FlicktekSettings.getInstance().getString(FlicktekSettings.DEVICE_MAC_SELECTED, "");
        if (mac != null && mac.length() > 0) {
            Log.v(TAG, "We have the mac and name so we try to connect directly using the service");
            service.putExtra(BleProfileService.EXTRA_DEVICE_ADDRESS, mac);

            String name = FlicktekSettings.getInstance().getString(FlicktekSettings.DEVICE_CLIP_NAME, "");
            if (name != null && name.length() > 0)
                service.putExtra(BleProfileService.EXTRA_DEVICE_NAME, name);

            startService(service);
            bindService(service, mServiceConnection, 0);
        } else {
            Log.v(TAG, "No idea of where to connect. Launch scanner");
            onConnectClicked(null);
        }
    }

    public void forceDeviceDisconnection() {
        // We have to disconnect for the DFU to be able to connect
        if (mServiceBinder != null) {
            mServiceBinder.disconnect();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBatteryLevel(final FlicktekCommands.onBatteryEvent batteryEvent) {
        runOnUiThread(new Runnable() {

            public void run() {
                try {
                    updateBattery(batteryEvent.value);
                } catch (Exception e) {
                    e.printStackTrace();
                    finish();
                }
            }
        });
    }

    public void backFragment() {
        Log.d(TAG, "showFragment");

        runOnUiThread(new Runnable() {

            public void run() {
                FragmentManager fragmentManager = getFragmentManager();
                if (fragmentManager.getBackStackEntryCount() > 1) {
                    fragmentManager.popBackStack();
                    Log.d(TAG, "BackStackEntryCount: " + fragmentManager.getBackStackEntryCount());
                    fragmentManager.beginTransaction().commit();

                    Log.i(TAG, "-------- BEGIN BACK FRAGMENT --------");
                    for (int entry = 0; entry < fragmentManager.getBackStackEntryCount(); entry++) {
                        FragmentManager.BackStackEntry backStackEntryAt = fragmentManager.getBackStackEntryAt(entry);
                        Log.i(TAG, "Fragment: " + backStackEntryAt.getId() + " " + backStackEntryAt.getName());
                    }
                    Log.i(TAG, "---------- END BACK FRAGMENT --------");
                } else {
                    Log.i(TAG, "---------- No back stack! --------");
                    finish(); // Closes app
                }
            }
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSettingsEvent(FlicktekSettings.onSettingsEvent event) {
        Log.v(TAG, "APP onSettingsEvent " + event.key + " " + event.value);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDeviceToWear(DeviceToWearEvent event) {
        forceDeviceDisconnection();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDeviceToPhone(DeviceToPhoneEvent event) {
        forceConnection();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onGestureRawData(final FlicktekCommands.onGestureRawData gestureData) {
        UploadGestureAsync.getInstance(gestureData);
    }
}