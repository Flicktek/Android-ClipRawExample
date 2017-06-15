package com.flicktek.clip;

import android.Manifest;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.flicktek.clip.eventbus.ConnectedEvent;
import com.flicktek.clip.eventbus.ConnectingEvent;
import com.flicktek.clip.eventbus.DisconnectedEvent;
import com.flicktek.clip.util.Helpers;
import com.flicktek.example.R;
import com.google.android.gms.analytics.HitBuilders;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Formatter;
import java.util.LinkedList;
import java.util.Random;

import static java.util.Locale.US;

public class FlicktekBleFragment extends Fragment implements View.OnClickListener {
    protected static final String TAG = "BleFragment";
    protected static final String ARG_JSON = "JSON";
    protected static final String ARG_EXTRA = "EXTRA";

    protected static final boolean REPORT_MESSAGES = false;

    protected TextView tv_device_status;
    protected TextView tv_device_name;
    protected TextView tv_device_address;
    protected TextView tv_device_charging;

    protected MainActivity mainActivity;

    protected Button mStartCapture;
    protected Button mStreamingMode;
    protected Button mShutdown;
    protected TextView mCaptureText;

    protected int TIME_WINDOW_SIZE = 200;
    protected int MIN_Y_MANUAL = 1000;
    protected int MAX_Y_MANUAL = 10000;

    protected int CHECK_MIN_SENSOR_THRESHOLD = 3000;
    protected int CHECK_MAX_SENSOR_THRESHOLD = 7000;

    protected int CHECK_MIN_SENSOR_CENTER = 5400;
    protected int CHECK_MAX_SENSOR_CENTER = 5600;
    protected int CHECK_SENSOR_SAMPLES = 200 * 2 * 4;
    protected int samples_tested = 0;

    public boolean mCheckMinSensor[] = new boolean[4];
    public boolean mCheckMaxSensor[] = new boolean[4];
    public boolean mCheckAverageSensor[] = new boolean[4];

    protected boolean mTriggerMode = false;
    protected boolean mTriggerCapturing = false;

    protected JSONObject config;

    protected final Handler mHandler = new Handler();
    protected Runnable mTimer;
    protected LineGraphSeries<DataPoint> mSeries1;
    protected LineGraphSeries mSeries[] = new LineGraphSeries[8];

    protected double graph2LastXValue = 5d;

    protected GraphView graph1;

    protected int mStreamingSpeed = 0;
    protected int samples_captured = 0;
    protected int samples_arrived = 0;

    // Default bundle constructor as google best practices
    public static FlicktekBleFragment newInstance(String jsonString, String extra) {
        FlicktekBleFragment myFragment = new FlicktekBleFragment();
        Bundle args = new Bundle();
        args.putString(ARG_JSON, jsonString);
        args.putString(ARG_EXTRA, extra);
        myFragment.setArguments(args);
        return myFragment;
    }

    private void trackerSendScreenName(String s) {
        if (mainActivity.mTracker == null)
            return;

        mainActivity.mTracker.setScreenName(s);
        mainActivity.mTracker.send(new HitBuilders.ScreenViewBuilder().build());
    }

    public void trackerSendValue(String action, int value) {
        if (mainActivity.mTracker == null)
            return;

        String mac = FlicktekManager.getInstance().getMacAddress();
        if (mac == null) {
            mac = "Mac N/A";
        }

        String version = FlicktekManager.getInstance().getFirmwareVersion();
        mainActivity.mTracker.send(new HitBuilders.EventBuilder()
                .setCategory(mac)
                .setAction(action)
                .setLabel(version)
                .setValue(value)
                .build());
    }

    public void trackerSend(String action) {
        if (mainActivity.mTracker == null)
            return;

        String mac = FlicktekManager.getInstance().getMacAddress();
        if (mac == null) {
            mac = "Mac N/A";
        }

        String version = FlicktekManager.getInstance().getFirmwareVersion();
        mainActivity.mTracker.send(new HitBuilders.EventBuilder()
                .setCategory(mac)
                .setAction(action)
                .setLabel(version)
                .build());
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() == null)
            return;

        mainActivity = (MainActivity) getActivity();

        try {
            String configuration_json = getArguments().getString(ARG_JSON);
            config = Helpers.getJsonFromResources((MainActivity) getActivity(), configuration_json);
        } catch (Exception e) {
            Log.e(TAG, "Failed parsing JSON");
        }

        trackerSendScreenName("Launch");

        if (FlicktekManager.getInstance().isHandshakeOk())
            FlicktekCommands.getInstance().setStartSensorCapturing(true);
    }

    protected ImageView check_connect;
    protected ImageView check_button;
    protected ImageView check_sensor[] = new ImageView[4];
    protected ImageView check_lines;
    protected ImageView check_led_1;
    protected ImageView check_charging;
    protected ImageView check_battery;

    protected Button mCheck_led_1;

    protected SeekBar mSeekBar;

    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View rootView = inflater.inflate(R.layout.fragment_sensor_testing, container, false);

        check_connect = (ImageView) rootView.findViewById(R.id.check_connectivity);

        check_lines = (ImageView) rootView.findViewById(R.id.check_lines);

        check_button = (ImageView) rootView.findViewById(R.id.check_button);
        check_sensor[0] = (ImageView) rootView.findViewById(R.id.check_sensor1);
        check_sensor[1] = (ImageView) rootView.findViewById(R.id.check_sensor2);
        check_sensor[2] = (ImageView) rootView.findViewById(R.id.check_sensor3);
        check_sensor[3] = (ImageView) rootView.findViewById(R.id.check_sensor4);

        check_led_1 = (ImageView) rootView.findViewById(R.id.check_led_1);

        check_charging = (ImageView) rootView.findViewById(R.id.check_charging);
        check_battery = (ImageView) rootView.findViewById(R.id.check_battery);

        tv_device_status = (TextView) rootView.findViewById(R.id.tv_current_menu);

        tv_device_name = (TextView) rootView.findViewById(R.id.tv_device_name);
        tv_device_address = (TextView) rootView.findViewById(R.id.tv_device_mac);

        tv_device_charging = (TextView) rootView.findViewById(R.id.tv_charging);
        tv_device_charging.setText("");

        mainActivity.mConnectButton = (Button) rootView.findViewById(R.id.action_connect);
        mStartCapture = (Button) rootView.findViewById(R.id.start_capture);
        mShutdown = (Button) rootView.findViewById(R.id.shutdown);

        // Replace default menu battery items by the ones on our bar
        LinearLayout ll_battery = (LinearLayout) rootView.findViewById(R.id.ll_battery);
        TextView tv_battery = (TextView) rootView.findViewById(R.id.tv_battery_level);
        ImageView iv_battery = (ImageView) rootView.findViewById(R.id.iv_battery);

        mainActivity.setBatteryUI(ll_battery, tv_battery, iv_battery);

        mCaptureText = (TextView) rootView.findViewById(R.id.capture_text);
        mCaptureText.setVisibility(View.VISIBLE);

        mStreamingMode = (Button) rootView.findViewById(R.id.streaming_mode);

        mCheck_led_1 = (Button) rootView.findViewById(R.id.button_check_led_1);
        mCheck_led_1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                check_led_1.setVisibility(View.INVISIBLE);
                mCheck_led_1.setActivated(false);
                FlicktekCommands.getInstance().
                        writeSingleCommand(FlicktekCommands.COMMAND_ENABLE, 'b');
                check_device_successful();
                trackerSend("Led");
            }
        });

        mStreamingMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                if (mTriggerMode) {
                    mStreamingMode.setText("CONTINOUS STREAMING");
                } else {
                    mStreamingMode.setText("GESTURE CAPTURE");
                }

                mTriggerMode = !mTriggerMode;
                trackerSend("Capture");
            }
        });

        mShutdown.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                trackerSend("Shutdown");
                FlicktekCommands.getInstance().writeShutdown();
            }
        });

        mSeekBar = (SeekBar) rootView.findViewById(R.id.seekBar1);

        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int progress = 0;

            final int SIZE_SAMPLING = 7;

            @Override
            public void onProgressChanged(SeekBar seekBar, int progresValue, boolean fromUser) {
                progress = progresValue;
                int value = (int) (SIZE_SAMPLING * progresValue) / seekBar.getMax();
                Log.v(TAG, "Progress : " + progress + "/" + seekBar.getMax() + " = " + value);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = (int) (SIZE_SAMPLING * progress) / seekBar.getMax();
                mSeekBar.setProgress((value * seekBar.getMax()) / SIZE_SAMPLING);
                FlicktekCommands.getInstance().setCaptureRate(value);
                Log.v(TAG, "Covered: " + progress + "/" + seekBar.getMax() + " VAlue " + value);
                samples_arrived = 0;
                samplingRateUnixTime = 0;

            }
        });

        mStartCapture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                onStartClicked();
            }
        });

        graph1 = (GraphView) rootView.findViewById(R.id.graph1);
        if (graph1 != null) {
            graph1.getLegendRenderer().setVisible(false);
            graph1.getGridLabelRenderer().setHorizontalLabelsVisible(false);
            graph1.getGridLabelRenderer().setGridColor(R.color.black_54p);
            graph1.getGridLabelRenderer().setVerticalAxisTitleColor(Color.WHITE);
            graph1.getGridLabelRenderer().setVerticalLabelsColor(Color.WHITE);

            graph1.getViewport().setXAxisBoundsManual(true);
            graph1.getViewport().setMinX(0);
            graph1.getViewport().setMaxX(TIME_WINDOW_SIZE);

            graph1.getViewport().setMinY(MIN_Y_MANUAL);
            graph1.getViewport().setMaxY(MAX_Y_MANUAL);
            graph1.getViewport().setYAxisBoundsManual(true);

            for (int t = 0; t < 5; t++) {
                mSeries[t] = new LineGraphSeries<>();
                mSeries[t].setThickness(4);

                switch (t) {
                    case 0:
                        mSeries[t].setColor(0xFFFFFFFF);
                        break;
                    case 1:
                        mSeries[t].setColor(0xFFFF6666);
                        break;
                    case 2:
                        mSeries[t].setColor(0xFF66FF66);
                        break;
                    case 3:
                        mSeries[t].setColor(0xFF6666FF);
                        break;
                    case 4:
                        mSeries[t].setColor(0xFF00FFFF);
                        break;
                }
                graph1.addSeries(mSeries[t]);
            }
        }

        return rootView;
    }

    LinkedList<int[]> mFifoSampleData = new LinkedList<int[]>();

    String format = "%d,%d,%d,%d,%d,%d,0\r\n";
    String header = "sample_number, timestamp, sample_1, sample_2, sample_3, sample_4, gesture_number\r\n";

    long captureUnixTime;
    public long samplingRateUnixTime;
    long lastSampleCapturedTime;

    public void onAddSamples(int sampleData[]) {
        samples_arrived++;
        if (samplingRateUnixTime == 0)
            samplingRateUnixTime = System.currentTimeMillis();

        if (captureUnixTime != 0) {
            samples_captured++;
            long unixTime = (System.currentTimeMillis() - captureUnixTime);
            int gesture = mGestureDetected + 1;

            if (gesture != 0 && mTriggerCapturing) {
                Log.v(TAG, "+++++++++++ STOP CAPTURING +++++++++");
                mTriggerCapturing = false;
            }

            StringBuilder sb = new StringBuilder();
            Formatter formatter = new Formatter(sb, US);
            formatter.format(format, samples_captured, unixTime, sampleData[0], sampleData[1], sampleData[2], sampleData[3], gesture);
            String string = sb.toString();

            if (REPORT_MESSAGES)
                Log.v(TAG, "CAPTURE: " + string);

        }

        boolean capture = !mTriggerMode;
        boolean test = false;

        for (int t = 0; t < 4; t++)
            if (sampleData[t] > 1) {
                if (mCheckAverageSensor[t] == false &&
                        sampleData[t] > CHECK_MIN_SENSOR_CENTER &&
                        sampleData[t] < CHECK_MAX_SENSOR_CENTER) {

                    if (samples_tested > CHECK_SENSOR_SAMPLES)
                        mCheckAverageSensor[t] = true;

                    samples_tested++;
                } else {
                    samples_tested = 0;
                }

                if (mCheckMaxSensor[t] == false && sampleData[t] > CHECK_MAX_SENSOR_THRESHOLD) {
                    mCheckMaxSensor[t] = true;
                    test = true;
                }

                if (mCheckMinSensor[t] == false && sampleData[t] < CHECK_MIN_SENSOR_THRESHOLD) {
                    mCheckMinSensor[t] = true;
                    test = true;
                }

                if (test && mCheckMaxSensor[t] && mCheckMinSensor[t]) {
                    check_sensor[t].setVisibility(View.INVISIBLE);
                    check_device_successful();
                }
            }

        if (mTriggerMode && !mTriggerCapturing) {
            long elapsed = System.currentTimeMillis() - lastSampleCapturedTime;
            if (lastSampleCapturedTime == 0 || elapsed > 1000) {
                for (int t = 0; t < 4; t++) {
                    if (sampleData[t] > 1000 && (sampleData[t] > 5600 || sampleData[t] < 5300)) {
                        Log.v(TAG, "+++++++++++ Trigger capturing ++++++++++");
                        mTriggerCapturing = true;
                    }
                }
            }
        }

        int count = 0;
        for (int a = 0; a < 4; a++) {
            if (mCheckAverageSensor[a] == true) {
                count++;
            }
        }

        if (count == 4 && check_lines != null) {
            if (check_lines.getVisibility() == View.VISIBLE) {
                check_device_successful();
                check_lines.setVisibility(View.INVISIBLE);
                trackerSend("Sensors centered");
            }
        }

        if (mTriggerCapturing)
            capture = true;

        if (capture)
            mFifoSampleData.add(sampleData);
    }

    @Nullable
    public int[] fetchSample() {
        if (mFifoSampleData.isEmpty())
            return null;

        if (REPORT_MESSAGES)
            if (mFifoSampleData.size() > 1) {
                Log.v(TAG, " Samples left " + mFifoSampleData.size());
            }

        return mFifoSampleData.removeFirst();
    }

    int mGestureDetected = -1;

    public void onGesture(int gesture) {
        mGestureDetected = gesture;
        if (mTriggerCapturing) {
            Log.v(TAG, "+++++++++++ STOP CAPTURING +++++++++");
            mTriggerCapturing = false;
            lastSampleCapturedTime = System.currentTimeMillis();
        }
    }

    public static boolean isStreaming = false;

    @Override
    public void onResume() {
        EventBus.getDefault().register(this);
        super.onResume();

        mTimer = new Runnable() {
            @Override
            public void run() {
                if (mSeries[0] == null)
                    return;

                int[] samples = fetchSample();

                if (!isStreaming && samples == null) {
                    graph2LastXValue += 1d;
                    double value = (graph1.getViewport().getMaxY(false) - graph1.getViewport().getMinY(false));
                    for (int t = 0; t < 5; t++)
                        mSeries[t].appendData(new DataPoint(graph2LastXValue, value + t * 1000), true, TIME_WINDOW_SIZE * 2);
                } else {
                    isStreaming = true;
                }

                while (samples != null) {
                    graph2LastXValue += 1d;
                    for (int t = 0; t < 4; t++) {
                        int value = samples[t] + t * 1000;
                        mSeries[t].appendData(new DataPoint(graph2LastXValue, value), true, TIME_WINDOW_SIZE * 2);

                        if (value > graph1.getViewport().getMaxY(false)) {
                            if (value < 16000)
                                graph1.getViewport().setMaxY(value);
                        } else if (value < graph1.getViewport().getMinY(false)) {
                            graph1.getViewport().setMinY(value);
                        }
                    }
                    samples = fetchSample();
                }

                if (isStreaming) {
                    double maxY = graph1.getViewport().getMaxY(false);
                    double minY = graph1.getViewport().getMinY(false);

                    if (maxY > MAX_Y_MANUAL) {
                        graph1.getViewport().setMaxY(maxY - 10);
                    }

                    if (minY < MIN_Y_MANUAL) {
                        graph1.getViewport().setMinY(minY + 10);
                    }

                    if (mGestureDetected != -1) {
                        mSeries[4].setThickness(6);
                        switch (mGestureDetected) {
                            case 0:
                                mSeries[4].setColor(0xFFFFFFFF);
                                break;
                            case 1:
                                mSeries[4].setColor(0xFFFF6666);
                                break;
                            case 2:
                                mSeries[4].setColor(0xFF66FF66);
                                break;
                            case 3:
                                mSeries[4].setColor(0xFF6666FF);
                                break;
                            case 4:
                                mSeries[4].setColor(0xFF00FFFF);
                                break;
                        }

                        mSeries[4].appendData(new DataPoint(graph2LastXValue, 0), true, 10);
                        mSeries[4].appendData(new DataPoint(graph2LastXValue, maxY), true, 10);
                        mSeries[4].appendData(new DataPoint(graph2LastXValue, 0), true, 10);
                        mGestureDetected = -1;
                    } else {

                    }
                }

                mHandler.postDelayed(this, 15);
                if (samples_captured != 0 && captureUnixTime != 0) {
                    long unixTime = (System.currentTimeMillis() - captureUnixTime);

                    float ratio = 0;
                    if (unixTime > 1000)
                        ratio = samples_captured / (unixTime / 1000);

                    mCaptureText.setText("Samples:" + samples_captured + "\nElapsed: " + unixTime + "ms " + (int) ratio + "smp/sec");
                } else {
                    if (samplingRateUnixTime != 0) {
                        long unixTime = (System.currentTimeMillis() - samplingRateUnixTime);

                        float ratio = 0;
                        if (unixTime > 1000)
                            ratio = samples_arrived / (unixTime / 1000);

                        if (ratio < 100)
                            ratio = 100;

                        TIME_WINDOW_SIZE = (int) ratio;
                        String text = "Samples: " + samples_arrived;
                        if (mStreamingSpeed != 0)
                            text += " Rate: " + mStreamingSpeed;

                        text += "\n" + (int) ratio + " samples/sec";
                        mCaptureText.setText(text);
                    }
                }
            }
        };
        mHandler.postDelayed(mTimer, 100);
    }

    @Override
    public void onPause() {
        super.onPause();

        mHandler.removeCallbacks(mTimer);
        EventBus.getDefault().unregister(this);
    }

    double mLastRandom = 2;
    Random mRand = new Random();

    protected double getRandom() {
        return mLastRandom += mRand.nextDouble() * 0.5 - 0.25;
    }

    protected DataPoint[] generateData() {
        int count = 30;
        DataPoint[] values = new DataPoint[count];
        for (int i = 0; i < count; i++) {
            double x = i;
            double f = mRand.nextDouble() * 0.15 + 0.3;
            double y = Math.sin(i * f + 2) + mRand.nextDouble() * 0.3;
            DataPoint v = new DataPoint(x, y);
            values[i] = v;
        }
        return values;
    }

    public void showFragment(final Fragment _fragment, final boolean _isNext) {
        Log.d(TAG, "showFragment: ");
        mainActivity.runOnUiThread(new Runnable() {

            public void run() {
                try {
                    FragmentManager fragmentManager = getFragmentManager();
                    FragmentTransaction transaction = fragmentManager.beginTransaction();

                    transaction.replace(R.id.container, _fragment);
                    transaction.commit();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    FileOutputStream fileOutputStream;
    File fileData;

    @Override
    public Context getContext() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP) {
            return mainActivity.getApplicationContext();
        } else {
            return super.getContext();
        }
    }

    @Nullable
    public File getTempFile(Context context, String url) {
        File file = null;
        String fileName = Uri.parse(url).getLastPathSegment();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (getContext().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, MainActivity.PERMISSION_REQUEST_WRITING_EXTERNAL);
            }
        }

        if (canWriteToFlash()) {
            File externalFilesDir = getContext().getExternalFilesDir(null);
            file = new File(externalFilesDir, fileName);
        } else {
            file = new File(getContext().getCacheDir(), fileName);
        }

        return file;
    }

    String currentCaptureFile;

    protected void createFile() {
        Date date = new Date();

        DateFormat df = new SimpleDateFormat("yyyy_MM_dd", US);
        String newFolderFile = df.format(date);

        DateFormat dfile = new SimpleDateFormat("yyyyMMdd_kk_mm_ss", US);
        currentCaptureFile = dfile.format(date) + ".csv";

        try {
            fileData = getTempFile(mainActivity.getApplicationContext(), currentCaptureFile);
            fileOutputStream = new FileOutputStream(fileData);
            writeToFile(header);
            Toast.makeText(mainActivity.getApplicationContext(),
                    "CREATED FILE " + fileData.getAbsolutePath(), Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(mainActivity.getApplicationContext(),
                    "FAILED CREATING FILE " + currentCaptureFile + e.toString(), Toast.LENGTH_SHORT).show();
        }

        samples_captured = 0;
        captureUnixTime = System.currentTimeMillis();
        mCaptureText.setVisibility(View.VISIBLE);

        trackerSend("Create file");
    }

    protected void writeToFile(String data) {
        if (fileOutputStream == null) {
            Toast.makeText(mainActivity.getApplicationContext(),
                    "FAILED SAVING FILE", Toast.LENGTH_SHORT).show();
        }

        try {
            fileOutputStream.write(data.getBytes());
            if (samples_captured % 20 == 0)
                fileOutputStream.flush();

        } catch (IOException e) {
            Toast.makeText(mainActivity.getApplicationContext(),
                    "FAILED SAVING FILE " + e.toString(), Toast.LENGTH_SHORT).show();
            onCloseClicked();

            Log.e("Exception", "File write failed: " + e.toString());
        }

        trackerSend("Write to file");
    }

    protected boolean canWriteToFlash() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        } else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            // Read only isn't good enough
            return false;
        }
        return false;
    }

    public void onCloseClicked() {
        mStartCapture.setText("CAPTURE DATA");
        captureUnixTime = 0;

        if (fileOutputStream != null) {
            try {
                fileOutputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            fileOutputStream = null;
        }

        if (fileData != null && fileData.exists()) {
            Toast.makeText(mainActivity.getApplicationContext(),
                    "SAVED " + samples_captured + " SAMPLES", Toast.LENGTH_LONG).show();
            fileData = null;
        }
    }

    public void onStartClicked() {
        if (fileData != null) {
            onCloseClicked();
            return;
        }

        createFile();
        if (fileData != null) {
            mStartCapture.setText("CAPTURE STOP");
        }
    }

    protected void updateUi() {
    }

    public void close() {
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onGesturePerformed(FlicktekCommands.onGestureEvent gestureEvent) {
        if (mTriggerCapturing) {
            Log.v(TAG, "+++++++++++ GESTURE STOP CAPTURING +++++++++");
            mTriggerCapturing = false;
        }

        trackerSend("Gesture " + gestureEvent.status);

        if (REPORT_MESSAGES) {
            Toast toast = Toast.makeText(mainActivity.getApplicationContext(), gestureEvent.status, Toast.LENGTH_SHORT);
            toast.setGravity(Gravity.TOP | Gravity.RIGHT, 0, 0);
            toast.show();
        }
    }

    @Override
    public void onClick(View v) {

    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBatteryLevel(FlicktekCommands.onBatteryEvent batteryEvent) {
        check_battery.setVisibility(View.INVISIBLE);

        if (check_battery.getVisibility() == View.VISIBLE) {
            trackerSend("Battery OK");
        }

        trackerSendValue("Battery", batteryEvent.value);

        mainActivity.updateBattery(batteryEvent.value);
    }

    int min_value = 0;

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDeviceButtonPressed(FlicktekCommands.onButtonPressed event) {
        check_button.setVisibility(View.INVISIBLE);
        trackerSend("Button OK");
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onVersion(FlicktekCommands.onVersionRequested event) {
        check_connect.setVisibility(View.INVISIBLE);
        trackerSend("Version " + event.value);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDeviceConnected(ConnectedEvent event) {
        tv_device_name.setVisibility(View.VISIBLE);
        tv_device_name.setText("[" + event.name + "]");

        tv_device_name.setVisibility(View.VISIBLE);
        tv_device_address.setText("[" + event.mac_address + "]");

        tv_device_status.setText("Connected");

        mainActivity.tv_battery.setVisibility(View.VISIBLE);
        mainActivity.ll_battery.setVisibility(View.VISIBLE);

        if (event.name.startsWith("Flick")) {

        }

        trackerSend("Device " + event.name);
        samplingRateUnixTime = 0;
        samples_arrived = 0;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDeviceConnecting(ConnectingEvent event) {
        tv_device_status.setVisibility(View.VISIBLE);
        tv_device_status.setText("Connecting");
    }

    boolean finished_testing = false;

    void check_device_successful() {
        if (check_connect.getVisibility() == View.VISIBLE) {
            finished_testing = false;
            return;
        }

        if (check_button.getVisibility() == View.VISIBLE)
            return;

        for (int t = 0; t < 4; t++) {
            if (check_sensor[t].getVisibility() == View.VISIBLE)
                return;
        }

        if (check_led_1.getVisibility() == View.VISIBLE)
            return;

        if (check_charging.getVisibility() == View.VISIBLE)
            return;

        if (check_battery.getVisibility() == View.VISIBLE)
            return;

        if (mainActivity.mTracker != null) {
            mainActivity.mTracker.setScreenName("R&D Sensors ");
            mainActivity.mTracker.send(new HitBuilders.ScreenViewBuilder().build());
        }

        if (!finished_testing) {
            finished_testing = true;
            mainActivity.runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        trackerSend("Success");
                        Toast.makeText(mainActivity.getApplicationContext(),
                                "TEST COMPLETED", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    boolean mSeenChargeState = false;
    boolean mSeenDischargeState = false;

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDeviceDisconnected(DisconnectedEvent event) {
        trackerSend("Disconnected");
        tv_device_name.setVisibility(View.GONE);
        tv_device_address.setVisibility(View.GONE);

        tv_device_status.setText("Disconnected");

        check_connect.setVisibility(View.VISIBLE);
        check_button.setVisibility(View.VISIBLE);
        check_led_1.setVisibility(View.VISIBLE);
        check_charging.setVisibility(View.VISIBLE);
        check_battery.setVisibility(View.VISIBLE);

        mCheck_led_1.setActivated(true);

        mSeenChargeState = false;
        mSeenDischargeState = false;

        for (int t = 0; t < 4; t++) {
            mCheckMinSensor[t] = false;
            mCheckMaxSensor[t] = false;
            mCheckAverageSensor[t] = false;
            check_sensor[t].setVisibility(View.VISIBLE);
        }

        check_lines.setVisibility(View.VISIBLE);

        tv_device_charging.setText("");
        mainActivity.tv_battery.setVisibility(View.INVISIBLE);
        mainActivity.ll_battery.setVisibility(View.INVISIBLE);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDeviceACK(FlicktekCommands.onDeviceACK event) {
        check_connect.setVisibility(View.INVISIBLE);
        trackerSend("Device ACK");
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStreamingSpeed(FlicktekCommands.onStreamingSpeed event) {
        mStreamingSpeed = event.ticks;
        trackerSend("Stream speed " + mStreamingSpeed);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onChargingState(FlicktekCommands.onChargingState event) {
        if (event.isCharging) {
            mSeenChargeState = true;
            tv_device_charging.setText("   [Charging]");
            trackerSend("Charging");
        }

        if (!event.isCharging) {
            mSeenDischargeState = true;
            tv_device_charging.setText("[Discharging]");
            trackerSend("Discharging");
        }

        if (mSeenDischargeState && mSeenChargeState)
            check_charging.setVisibility(View.INVISIBLE);

        check_device_successful();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDeviceReady(FlicktekCommands.onDeviceReady event) {
        FlicktekCommands.getInstance().setStartSensorCapturing(true);
        trackerSend("DeviceReady");
    }
}