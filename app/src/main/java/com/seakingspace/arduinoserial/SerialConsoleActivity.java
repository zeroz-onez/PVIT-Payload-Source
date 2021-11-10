/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.seakingspace.arduinoserial;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.TextView;

import com.hoho.driver.UsbSerialPort;


public class SerialConsoleActivity extends Activity implements ArduinoService.Callback, ServiceConnection {

    private static final String TAG = SerialConsoleActivity.class.getSimpleName();

    /* *
     * Driver instance, passed in statically via
     * {@link #show(Context, UsbSerialPort)}.
     *
     * <p/>
     * This is a devious hack; it'd be cleaner to re-create the driver using
     * arguments passed in with the {@link #startActivity(Intent)} intent. We
     * can get away with it because both activities will run in the same
     * process, and this is a simple demo.
     */

    private static UsbSerialPort sPort = null;

    private TextView mTitleTextView;
    private TextView mDumpTextView;
    private ScrollView mScrollView;

    boolean autoScroll = true;

    ArduinoService mService;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.serial_console);
        mTitleTextView = findViewById(R.id.demoTitle);
        mDumpTextView = findViewById(R.id.consoleText);
        mScrollView = findViewById(R.id.demoScroller);

        findViewById(R.id.disconnectButton).setOnClickListener(view -> {
            Button btn = (Button) view;
            if (btn.getText().equals("Disconnect")) {
                mService.stopSelf();
                btn.setEnabled(false);
                btn.setText(R.string.messageReconnect);
            }
        });

        ((CheckBox) findViewById(R.id.autoScrollCheckBox)).setOnCheckedChangeListener((compoundButton, b) -> autoScroll = b);

        Intent service = new Intent(this, ArduinoService.class);
        startForegroundService(service);
        bindService(service, this, BIND_IMPORTANT);
    }

    private void print(String text) {
        if (mDumpTextView.getText().toString().split("\n").length > 2000) {
            mDumpTextView.setText("");
        }
        mDumpTextView.append(text);

        if (autoScroll) {
            mScrollView.fullScroll(View.FOCUS_DOWN);
        }
    }

    static void show(Context context, UsbSerialPort port) {
        sPort = port;
        final Intent intent = new Intent(context, SerialConsoleActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }

    @Override
    public void onConnected() {
        runOnUiThread(() -> {
            mTitleTextView.setText(R.string.mTitleConnect);
            ((Button) findViewById(R.id.disconnectButton)).setText(R.string.mTitleDisconnect);

            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    @Override
    public void onError(final String error) {
        Log.e(TAG, "serial console activity service onError: " + error);
        runOnUiThread(() -> {
            mTitleTextView.setText(error);
            Button btn = findViewById(R.id.disconnectButton);
            btn.setEnabled(false);
            btn.setText(R.string.mTitlePlugUnplugMsg);

            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });

        if (mService != null) {
            mService.stopSelf();
        }
    }

    @Override
    public void onMessageReceived(final String message) {
        runOnUiThread(() -> print(message));
    }

    @Override
    public void onMessageSent(final String message) {
        runOnUiThread(() -> print(message));

    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
        mService = ((ArduinoService.MyBinder) iBinder).getService();
        mService.setCallback(this);
        if (!mService.connected) {
            mService.mPort = sPort;
            mService.connect();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        mService = null;
        Log.e(TAG, "onServiceDisconnected: service disconnected");

        /* Removed calls to onError("service disconnected") & finish() */
    }
}