package com.example.arduinoserial;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.LruCache;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.WindowManager;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class ArduinoService extends Service {
    private static final String TAG = ArduinoService.class.getSimpleName();

    private static final int BAUD_RATE = 115200;
    private static final int BLOCK_LENGTH = 200;
    private static int CHUNK_SIZE = 40;

    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAITING_LOCK = 1;
    private static final int STATE_WAITING_PRECAPTURE = 2;
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;
    private static final int STATE_PICTURE_TAKEN = 4;
    private static final int STATE_QUEUED = 5;
    private static final int STATE_FOCUS_UNLOCKED = 6;

    private static final int MAX_PREVIEW_WIDTH = 1920;
    private static final int MAX_PREVIEW_HEIGHT = 1080;


    public UsbSerialPort mPort = null;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private Handler handler;

    private SerialInputOutputManager mSerialIoManager;

    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

                StringBuilder previousData = new StringBuilder();

                @Override
                public void onRunError(Exception e) {
                    Log.e(TAG, "Runner stopped.", e);

                    if (callback != null) {
                        callback.onError("Arduino disconnected");
                    }

                    connected = false;
                }

                @Override
                public void onNewData(final byte[] data) {
                    final String message = new String(data);

                    for (char c : message.toCharArray()) {
                        if (c == '\n') {
                            final String prev = previousData.toString().trim();
                            previousData.delete(0, previousData.length());
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    updateReceivedData(prev);
                                }
                            });
                        }
                        else {
                            previousData.append(c);
                        }
                    }
                }
            };

    private String mCameraId;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private CameraCaptureSession mCaptureSession;
    protected CameraDevice mCameraDevice;
    private ImageReader mImageReader;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CaptureRequest mPreviewRequest;
    private int mState = STATE_PREVIEW;
    private SurfaceTexture mDummyPreview = new SurfaceTexture(1);
    private Surface mDummySurface = new Surface(mDummyPreview);
    private Size mPreviewSize;
    private boolean mFlashSupported;
    private boolean cameraClosed = true;
    Queue<PhotoRequest> photoQueue = new LinkedBlockingQueue<>();

    private CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private int frameCount = 0;

        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: {
                    frameCount++;
                    break;
                }
                case STATE_QUEUED: {
                    PhotoRequest request = photoQueue.peek();
                    long now = Calendar.getInstance().getTime().getTime();
                    boolean delayPassed = request.burstDelay == 0 || (now - lastPictureTime) > request.burstDelay * 1000;

                    if (frameCount > 90 && delayPassed) {
                        frameCount = 0;
                        lockFocus();
                    }

                    frameCount++;
                    break;
                }
                case STATE_WAITING_LOCK: {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) {
                        captureStillPicture();
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState || CaptureResult.CONTROL_AF_STATE_INACTIVE == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_PICTURE_TAKEN;
                            captureStillPicture();
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }
                case STATE_FOCUS_UNLOCKED: {
                    frameCount = 0;
                    mState = STATE_PREVIEW;
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @SuppressLint("DefaultLocale")
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            process(result);
        }
    };

    private final ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imReader) {
            PhotoRequest request = photoQueue.peek();
            if (request == null) {
                Log.e(TAG, "onImageAvailable: PhotoRequest null");
                return;
            }

            lastPictureTime = Calendar.getInstance().getTime().getTime();

            final Image image = imReader.acquireNextImage();
            final ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];

            buffer.get(bytes);
            image.close();
            /*unlockFocus();
            if (!photoQueue.isEmpty()) {
                mState = STATE_QUEUED;
                //lockFocus();
            }
            else {
                //unlockFocus();
            }*/
            closeCamera();

            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, request.q, byteArrayOutputStream);
            bytes = byteArrayOutputStream.toByteArray();

            int chunksX = (int) Math.ceil(((double) bitmap.getWidth()) / CHUNK_SIZE);
            int chunksY = (int) Math.ceil(((double) bitmap.getHeight()) / CHUNK_SIZE);

            saveImageAndSendResponse(bytes, chunksX*chunksY, chunksX);
        }
    };

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            //This is called when the camera is open
            Log.d(TAG, "onOpened camera");
            mCameraOpenCloseLock.release();
            mCameraDevice = camera;
            cameraClosed = false;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            mCameraOpenCloseLock.release();
            closeCamera();
            Log.e(TAG, "onDisconnected: camera disconnected");
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            mCameraOpenCloseLock.release();
            closeCamera();
            Log.e(TAG, "camera state onError: " + error);
        }

        @Override
        public void onClosed(@NonNull CameraDevice camera) {
            Log.d(TAG, "camera onClosed");
            if (photoQueue.size() > 0) {
                PhotoRequest request = photoQueue.peek();
                openCamera(request.front);
                mState = STATE_QUEUED;
            }
            //openCamera();
        }
    };

    LruCache<String, byte[]> pictureCache = new LruCache<>(5);

    LocationListener listenerGps = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            lastKnown = location;
            lastKnownGps = location;
            Log.d(TAG, "location timer onLocationChanged: got gps fix:" + location);
        }

        @Override
        public void onStatusChanged(String s, int i, Bundle bundle) {

        }

        @Override
        public void onProviderEnabled(String s) {

        }

        @Override
        public void onProviderDisabled(String s) {

        }
    };

    LocationListener listenerNetwork = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (lastKnownGps == null) {
                lastKnown = location;
                Log.d(TAG, "location timer onLocationChanged: got network fix:" + location);
            }
        }

        @Override
        public void onStatusChanged(String s, int i, Bundle bundle) {

        }

        @Override
        public void onProviderEnabled(String s) {

        }

        @Override
        public void onProviderDisabled(String s) {

        }
    };
    Location lastKnown, lastKnownGps;

    public Callback callback;
    private MyBinder mLocalbinder = new MyBinder();

    private PowerManager.WakeLock mWakeLock;

    public boolean connected;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("android.hardware.usb.action.USB_DEVICE_DETACHED".equals(action)){
                if (callback != null) {
                    callback.onError("Device disconnected");
                }
            }
        }
    };
    private long lastPictureTime;

    @Override
    public void onCreate() {
        handler = new Handler();
        super.onCreate();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel("com.example.arduinoserial", "App Service", NotificationManager.IMPORTANCE_DEFAULT));
            startForeground(1, new Notification.Builder(this).setContentTitle("Arduino Service").setChannelId("com.example.arduinoserial").build());

        }

        mWakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "arduinoserial:arduineservice");
        mWakeLock.acquire();

        IntentFilter filter = new IntentFilter();
        filter.addAction("android.hardware.usb.action.USB_DEVICE_DETACHED");
        registerReceiver(receiver, filter);
    }

    private void runOnUiThread(Runnable runnable) {
        handler.post(runnable);
    }

    public void disconnect() {
        stopIoManager();
        if (mPort != null) {
            try {
                mPort.close();
            } catch (IOException e) {
                // Ignore
            }
        }

        photoQueue.clear();
        Log.d(TAG, "disconnect: photoqueue cleared");
        connected = false;

        stopBackgroundThread();
        closeCamera();

        ((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(listenerGps);
        ((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(listenerNetwork);

        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    @SuppressLint({"SetTextI18n", "MissingPermission"})
    public void connect() {
        if (mPort == null) {
            if (callback != null) {
                callback.onError("No serial device.");
            }
            return;
        }

        final UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        UsbDeviceConnection connection = usbManager.openDevice(mPort.getDriver().getDevice());
        if (connection == null) {
            if (callback != null) {
                callback.onError("Opening device failed");
            }
            return;
        }

        try {
            if (!connected) {
                mPort.open(connection);
                mPort.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            }

            ((LocationManager) getSystemService(LOCATION_SERVICE)).requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30 * 1000, 0, listenerNetwork, getMainLooper());
            ((LocationManager) getSystemService(LOCATION_SERVICE)).requestLocationUpdates(LocationManager.GPS_PROVIDER, 30 * 1000, 0, listenerGps, getMainLooper());

            onDeviceStateChange();


            startBackgroundThread();
            //openCamera();
        } catch (IOException e) {
            Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
            if (callback != null) {
                callback.onError("Error opening device: " + e.getMessage());
            }
            try {
                mPort.close();
            } catch (IOException e2) {
                // Ignore.
            }
            mPort = null;
        }

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnect();
        unregisterReceiver(receiver);
    }

    private void stopIoManager() {
        if (mSerialIoManager != null) {
            Log.i(TAG, "Stopping io manager ..");
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
    }

    private void startIoManager() {
        if (mPort != null) {
            Log.i(TAG, "Starting io manager ..");
            mSerialIoManager = new SerialInputOutputManager(mPort, mListener);
            mExecutor.submit(mSerialIoManager);

            callback.onConnected();
            connected = true;
        }
    }

    private void onDeviceStateChange() {
        stopIoManager();
        startIoManager();
    }


    @SuppressLint({"MissingPermission", "DefaultLocale"})
    private void updateReceivedData(String message) {
        if (callback != null) {
            callback.onMessageReceived("< " + message + "\n");
        }

        if ("".equals(message)) {
            Log.e(TAG, "updateReceivedData: empty message");
            return;
        }

        Log.d(TAG, "updateReceivedData: message: " + message);

        message = message.toLowerCase();

        int id;
        try {
            id = Integer.parseInt(message.split(",")[0]);
        } catch (Exception e) {
            Log.e(TAG, "updateReceivedData: id invalid; message: " + message, e);
            return;
        }

        message = message.substring(message.indexOf(",") + 1);

        if ("init".equals(message)) {
            sendMessage(id, 'I', "init");
        } else if ("time".equals(message)) {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);
            String messageBack = sdf.format(Calendar.getInstance().getTime());

            sendMessage(id, 'T', messageBack);
        } else if ("gps".equals(message)) {
            LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

            Location last = locationManager.getLastKnownLocation("gps");
            if (lastKnown != null) {
                Log.d(TAG, "onFinish: old gps location known");
                sendMessage(id, 'G', String.format("%f,%f", lastKnown.getLatitude(), lastKnown.getLongitude()));
            } else {
                if (last != null) {
                    Log.d(TAG, "onFinish: old gps location");
                    sendMessage(id, 'G', String.format("%f,%f", last.getLatitude(), last.getLongitude()));
                } else {
                    last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (last != null) {
                        Log.d(TAG, "onFinish: last network location");
                        sendMessage(id, 'G', String.format("%f,%f", last.getLatitude(), last.getLongitude()));
                    } else {
                        sendMessage(id, 'G', "N/A");
                    }
                }
            }

        } else if (message.startsWith("photo") || message.startsWith("burst")) {
            int start = message.indexOf("(") + 1;

            Dictionary<String, Integer> wbDict = new Hashtable<>();
            wbDict.put("auto", CaptureRequest.CONTROL_AWB_MODE_AUTO);
            wbDict.put("cloudy", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT);
            wbDict.put("day", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT);
            wbDict.put("fluorescent", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT);
            wbDict.put("incandescent", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT);
            wbDict.put("shade", CaptureRequest.CONTROL_AWB_MODE_SHADE);
            wbDict.put("twilight", CaptureRequest.CONTROL_AWB_MODE_TWILIGHT);
            wbDict.put("warm_fluorescent", CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT);

            int iso;
            int wb;
            long shutter;
            byte q;
            boolean front;
            int count = 5;
            int delay = 0;
            if (start != 0) {
                try {
                    front = message.charAt(start) == 'f';
                    String[] split = message.substring(start+2, message.length() - 1).split(",");
                    q = Byte.parseByte(split[0].substring(0, 2));
                    iso = Integer.parseInt(split[1]);
                    shutter = Long.parseLong(split[2]);
                    wb = wbDict.get(split[3]) != null ? wbDict.get(split[3]) : CaptureRequest.CONTROL_AWB_MODE_AUTO;
                    if (split.length >= 6) {
                        count = Integer.parseInt(split[4]);
                        delay = Integer.parseInt(split[5]);
                    }
                } catch (NumberFormatException | IndexOutOfBoundsException | NullPointerException e) {
                    Log.e(TAG, "updateReceivedData: bad photo command: " + message);
                    return;
                }
            } else {
                Log.e(TAG, "updateReceivedData: bad photo command: " + message);
                return;
            }

            PhotoRequest request = new PhotoRequest(id, shutter, wb, iso, q, front, message.startsWith("burst"), delay, count);
            photoQueue.add(request);
            Log.d(TAG, "updateReceivedData: photoqueue added, current length: " + photoQueue.size());
            if (photoQueue.size() == 1) { //TODO test
                openCamera(front);
                mState = STATE_QUEUED;
            }
            if (message.startsWith("burst")) {
                for (int i = 1; i  < count; i++) {
                    PhotoRequest request1 = new PhotoRequest(request, i);
                    photoQueue.add(request1);
                }
            }
        } else if (message.startsWith("stream")) {
            int start = message.indexOf("(") + 1;
            int middle = message.indexOf(",");
            int end = message.indexOf(")");

            boolean sendNext = false;
            if (message.charAt(end - 1) == '+') {
                end--;
                sendNext = true;
            }

            if (start == 0 || middle < start || end < middle) {
                Log.e(TAG, "bad stream message: " + message);
                return;
            }

            String pathWithChunk = message.substring(start, middle);
            String pathWithChunkOrig = pathWithChunk;
            String path;
            int underscore = pathWithChunk.lastIndexOf("_");
            int dot = pathWithChunk.lastIndexOf(".");
            int dash = pathWithChunk.indexOf("-");
            int chunk;
            int size = 0;
            if (dot < underscore) {
                Log.e(TAG, "bad stream message: " + message);
                return;
            }
            if (dash != -1) {
                try {
                    int end_ = underscore != -1 ? underscore : dot;
                    size = Integer.parseInt(pathWithChunk.substring(dash+1, end_));
                    pathWithChunk = pathWithChunk.substring(0, dash) + pathWithChunk.substring(end_);
                    underscore = pathWithChunk.lastIndexOf("_");
                    dot = pathWithChunk.lastIndexOf(".");
                }
                catch (NumberFormatException e) {
                    Log.e(TAG, "bad stream message: " + message);
                    return;
                }
            }
            if (underscore == -1) {
                chunk = -1;
                path = pathWithChunk;
            }
            else {
                path = pathWithChunk.substring(0, underscore) + ".png";
                try {
                    chunk = Integer.parseInt(pathWithChunk.substring(underscore + 1, dot));
                }
                catch (NumberFormatException e) {
                    Log.e(TAG, "bad stream message: " + message);
                    return;
                }
            }

            File f = new File(getGalleryPath() + "/" + path);
            int block;
            try {
                block = Integer.parseInt(message.substring(middle + 1, end).trim());
            }
            catch (NumberFormatException e) {
                Log.e(TAG, "updateReceivedData: cant convert to int: " + message, e);
                return;
            }

            byte[] fullFile = pictureCache.get(path);
            if (fullFile == null) {
                try (DataInputStream dis = new DataInputStream(new FileInputStream(f))) {
                    fullFile = new byte[(int) f.length()];
                    dis.read(fullFile);

                    pictureCache.put(path, fullFile);
                } catch (FileNotFoundException e) {
                    Log.e(TAG, "file doesnt exist: " + f);
                    if (callback != null) {
                        callback.onMessageSent("file not found!!!\n");
                    }
                    return;
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
            }

            if (f.length() - block*BLOCK_LENGTH < 0) {
                Log.e(TAG, "updateReceivedData: f.length() - block*BLOCK_LENGTH < 0" + message);
                return;
            }

            byte[] chunkBytes;
            int chunksX = 0, chunksY = 0;
            if (chunk != -1) {
                Bitmap fullImage = BitmapFactory.decodeByteArray(fullFile, 0, fullFile.length);
                Bitmap cropped;
                if (fullImage.getWidth() >= fullImage.getHeight()) {
                    cropped = Bitmap.createBitmap( fullImage, fullImage.getWidth() / 2 - fullImage.getHeight() / 2,
                            0, fullImage.getHeight(), fullImage.getHeight());
                }
                else {
                    cropped = Bitmap.createBitmap(fullImage, 0,
                            fullImage.getHeight() / 2 - fullImage.getWidth() / 2, fullImage.getWidth(),
                            fullImage.getWidth());
                }

                int resize_size = (size + 4) * 40;
                Bitmap scaled = Bitmap.createScaledBitmap(cropped, resize_size, resize_size, true);

                chunksX = (int) Math.ceil(((double) scaled.getWidth()) / CHUNK_SIZE);
                chunksY = (int) Math.ceil(((double) scaled.getHeight()) / CHUNK_SIZE);
                int x = chunk % chunksX;
                int y = chunk / chunksX;
                int w = Math.min(CHUNK_SIZE, scaled.getWidth() - x * CHUNK_SIZE);
                int h = Math.min(CHUNK_SIZE, scaled.getHeight() - y * CHUNK_SIZE);
                Bitmap chunkBitmap = Bitmap.createBitmap(scaled, x*CHUNK_SIZE, y*CHUNK_SIZE, w, h);
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                chunkBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
                chunkBytes = stream.toByteArray();
            }
            else {
                chunkBytes = fullFile;
            }

            int messageLength = Math.min(BLOCK_LENGTH, chunkBytes.length - block * BLOCK_LENGTH);
            byte[] messageBytes = new byte[messageLength];
            if (messageLength >= 0) {
                System.arraycopy(chunkBytes, block * 200, messageBytes, 0, messageLength);
            }

            /*if (callback != null) {
                callback.onMessageSent(bytesToHex(messageBytes));
            }*/

            byte hasMore = (byte) ((block + 1) * BLOCK_LENGTH < chunkBytes.length ? 1 : 0);
            if (sendNext) {
                hasMore |= 0b010;
            }
            if (chunk < chunksX * chunksY - 1) {
                hasMore |= 0b100;
            }

            sendStreamMessage(id, block, (byte) messageBytes.length, hasMore, pathWithChunkOrig, messageBytes);

        } else {
            Log.e(TAG, "updateReceivedData: bad message: " + message);
        }
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    private void sendStreamMessage(int id, int block, byte len, byte hasMore, String path, byte[] messageBack) {
        if (mSerialIoManager != null) {
            try {
                mPort.write((id + ",S" + path + ",").getBytes(), 2000);
                mPort.write(new byte[]{(byte) (block >> 8), (byte) (block % 256), len, hasMore}, 2000);
                mPort.write(messageBack, 2000);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (callback != null) {
            callback.onMessageSent("> " + id + " " + "<binary>" + "\n");
        }
    }

    private void sendMessage(int id, char type, String messageBack) {
        messageBack = id + "," + type + messageBack + "\n";
        if (mSerialIoManager != null) {
            try {
                mPort.write(messageBack.getBytes(), 2000);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (callback != null) {
            callback.onMessageSent("> " + messageBack);
        }
    }

    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
                                          int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new ArduinoService.CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new ArduinoService.CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }


    private void setUpCameraOutputs(boolean front) {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = manager.getCameraCharacteristics(cameraId);
                
                Range<Long> shutter = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                if (shutter == null) {
                    shutter = new Range<>(-1L, -1L);
                }
                Range<Integer> iso = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                if (iso == null) {
                    iso = new Range<>(-1, -1);
                }
                int[] wb = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
                if (wb == null) {
                    wb = new int[]{};
                }
                int level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);

                if (callback != null) {
                    //callback.onMessageSent("setUpCameraOutputs: camera info: " + String.format("%d, %d, %d-%d, %d-%d \n", facing, level, shutter.getLower(), shutter.getUpper(), iso.getLower(), iso.getUpper()) + Arrays.toString(wb) );
                    Log.d(TAG, "setUpCameraOutputs: camera info: " + String.format("%d, %d, %d-%d, %d-%d \n", facing, level, shutter.getLower(), shutter.getUpper(), iso.getLower(), iso.getUpper()) + Arrays.toString(wb));
                }
                
                if (facing != null && facing == (front ? CameraCharacteristics.LENS_FACING_BACK : CameraCharacteristics.LENS_FACING_FRONT)) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                /*Size largest = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new CompareSizesByArea());*/
                Size largest = new Size(1280, 720);
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                        ImageFormat.JPEG, /*maxImages*/2);
                mImageReader.setOnImageAvailableListener(
                        onImageAvailableListener, mBackgroundHandler);

                Point displaySize = new Point();
                ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getSize(displaySize);
                int rotatedPreviewWidth = 640;
                int rotatedPreviewHeight = 480;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight, largest);

                // Check if the flash is supported.
                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                mFlashSupported = available == null ? false : available;

                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera(boolean front) {
        setUpCameraOutputs(front);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "openCamera: timed out waiting for camera opening");
            }
            ((CameraManager) getSystemService(CAMERA_SERVICE)).openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    int getOrientation() {
        final int rotation = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        return ORIENTATIONS.get(rotation);
    }

    private void createCameraPreviewSession() {
        Log.d(TAG, "createCameraPreviewSession");
        try {
            mDummyPreview.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(mDummySurface);

            // Here, we create a CameraCaptureSession for camera preview.
            if (mImageReader == null || cameraClosed) {
                Log.e(TAG, "createCameraPreviewSession: camera is closed");
                return;
            }
            mCameraDevice.createCaptureSession(Arrays.asList(mDummySurface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCameraDevice || cameraClosed) {
                                Log.e(TAG, "createCameraPreviewSession: camera is closed");
                                return;
                            }
                            Log.d(TAG, "cameracapturesession onConfigured");
                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_OFF);
                                mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f);
                                // Flash is automatically enabled when necessary.
                                setAutoFlash(mPreviewRequestBuilder);

                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException | IllegalStateException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            Log.e(TAG, "onConfigureFailed");
                        }

                        @Override
                        public void onClosed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            Log.d(TAG, "cameracapturesession onClosed");
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        try {
            if (mBackgroundThread != null) {
                mBackgroundThread.quitSafely();
                mBackgroundThread.join();
            }
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void lockFocus() {
        mState = STATE_WAITING_LOCK;
       /* try {
            Log.d(TAG, "lockFocus");
            // This is how to tell the camera to lock focus.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException | NullPointerException e) {
            e.printStackTrace();
        }*/
    }

    private void unlockFocus() {
        mState = STATE_FOCUS_UNLOCKED;
        /*try {
            // Reset the auto-focus trigger
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            setAutoFlash(mPreviewRequestBuilder);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_FOCUS_UNLOCKED;
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException | NullPointerException e) {
            e.printStackTrace();
        }*/
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
     */
    private void runPrecaptureSequence() {
        try {
            Log.d(TAG, "runPrecaptureSequence");
            // This is how to tell the camera to trigger.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException | NullPointerException e) {
            e.printStackTrace();
        }
    }

    private void setAutoFlash(CaptureRequest.Builder requestBuilder) {
        if (mFlashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }

    private void captureStillPicture() {
        if (mCameraDevice == null) {
            Log.e(TAG, "captureStillPicture: mCameraDevice null");
            return;
        }
        if (cameraClosed) {
            Log.e(TAG, "captureStillPicture: camera closed");
            return;
        }

        try {
            Log.d(TAG, "captureStillPicture");
            final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF);
            captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f);
            PhotoRequest request = photoQueue.peek();
            if (request.shutter != 0) {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, request.shutter * 1000*1000);
            }
            captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, request.iso);
            captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, request.wb);
            setAutoFlash(captureBuilder);
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation());
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 90);
            CameraCaptureSession.CaptureCallback CaptureCallback
                    = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    //unlockFocus();
                    //closeCamera();
                }
            };

            mCaptureSession.stopRepeating();
            mCaptureSession.abortCaptures();
            mCaptureSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException | NullPointerException e) {
            e.printStackTrace();
        }
    }


    String getGalleryPath() {
        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/Arduino");

        if (!folder.exists()) {
            folder.mkdirs();
        }

        return folder.getAbsolutePath();
    }

    private int testSize(int q, int CHUNK_SIZE, String path) {
        File f = new File(getGalleryPath() + "/" + path);
        byte[] fullFile;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(f))) {
            fullFile = new byte[(int) f.length()];
            dis.read(fullFile);
        }
        catch (Exception e) {
            return 0;
        }

        Bitmap fullImage = BitmapFactory.decodeByteArray(fullFile, 0, fullFile.length);

        int len = 0;
        int chunksX = (int) Math.ceil(((double) fullImage.getWidth()) / CHUNK_SIZE);
        int chunksY = (int) Math.ceil(((double) fullImage.getHeight()) / CHUNK_SIZE);

        for (int chunk = 0; chunk < chunksX * chunksY; chunk++) {
            int x = chunk % chunksX;
            int y = chunk / chunksX;
            int w = Math.min(CHUNK_SIZE, fullImage.getWidth() - x * CHUNK_SIZE);
            int h = Math.min(CHUNK_SIZE, fullImage.getHeight() - y * CHUNK_SIZE);
            Bitmap chunkBitmap = Bitmap.createBitmap(fullImage, x * CHUNK_SIZE, y * CHUNK_SIZE, w, h);
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            chunkBitmap.compress(Bitmap.CompressFormat.WEBP, q, stream);
            len += stream.size();
            File f1 = new File(getGalleryPath() + "/Arduino test/" + path + "_" + chunk + ".webp");
            try {
                FileOutputStream fos = new FileOutputStream(f1);
                fos.write(stream.toByteArray());
            }
            catch (Exception e) {
                //ignore
            }
        }

        return len;
    }

    @SuppressLint("DefaultLocale")
    private void saveImageAndSendResponse(final byte[] bytes, final int chunks, final int chunksX) {
        Log.d(TAG, "saveImageAndSendResponse");
        int i = 0;
        String path;
        File file;
        do {
            path = i + ".png";
            file = new File(getGalleryPath() + "/" + path);
            i++;
        } while (file.exists());

        try (final OutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        } catch (final IOException e) {
            Log.e(TAG, "Exception occurred while saving picture to external storage ", e);
            return;
        }

        final String fileName = path;
        final long fileSize = file.length();
        final PhotoRequest photo = photoQueue.poll();
        Log.d(TAG, "saveImageAndSendResponse: photoqueue polled, got " + (photo == null ? "null" : photo) + ", current size: " + photoQueue.size());

        if (photo == null) {
            Log.d(TAG, "run: id null from photoQueue on saveImageAndSendResonse");
            return;
        }

        if (photo.burst) {
            if (photo.burstId == photo.burstLength - 1) {
                int bestPath = i - 1, bestCount = 0;
                for (int j = i - photo.burstLength; j < i; j++) {
                    try {
                        Bitmap b = BitmapFactory.decodeFile(getGalleryPath() + String.format("/%d.png", j));
                        int[][] edge = getEdgeImage(b);
                        int cnt = countWhitePixels(edge);

                        if (cnt > bestCount) {
                            bestCount = cnt;
                            bestPath = j;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                final int bestPathCopy = bestPath;

                runOnUiThread(new Runnable() {
                    @SuppressLint("DefaultLocale")
                    @Override
                    public void run() {
                        sendMessage(photo.id, 'P', String.format("%s,%d,%d,%d", String.format("%d.png", bestPathCopy), fileSize, chunks, chunksX));
                    }
                });
            }
        }
        else {
            runOnUiThread(new Runnable() {
                @SuppressLint("DefaultLocale")
                @Override
                public void run() {
                    sendMessage(photo.id, 'P', String.format("%s,%d,%d,%d", fileName, fileSize, chunks, chunksX));
                }
            });
        }

    }

    private static int[][] getEdgeImage(Bitmap image){
        int x = image.getWidth();
        int y = image.getHeight();

        int[][] edgeColors = new int[x][y];
        int maxGradient = -1;

        int[] pixels = new int[x*y];
        int[][] edgePixels = new int[x][y];

        image.getPixels(pixels, 0, x, 0, 0, x, y);

        for (int i = 0; i < y; i++) {
            for (int j = 0; j < x; j++) {
                pixels[i*x+j] = getGrayScale(pixels[i*x+j]);
            }
        }

        for (int i = 1; i < y - 1; i++) {
            for (int j = 1; j < x - 1; j++) {
                int val00 = pixels[(i - 1)*x + j - 1];
                int val01 = pixels[(i - 1)*x + j];
                int val02 = pixels[(i - 1)*x + j + 1];

                int val10 = pixels[i*x +  j - 1];
                int val11 = pixels[i*x +  j];
                int val12 = pixels[i*x +  j + 1];

                int val20 = pixels[(i + 1)*x + j - 1];
                int val21 = pixels[(i + 1)*x + j];
                int val22 = pixels[(i + 1)*x + j + 1];

                int gx = -1 * val00 + 0 * val01 + 1 * val02
                        + -2 * val10 + 0 * val11 + 2 * val12
                        + -1 * val20 + 0 * val21 + 1 * val22;

                int gy = -1 * val00 + -2 * val01 + -1 * val02
                        + 0 * val10 + 0 * val11 + 0 * val12
                        + 1 * val20 + 2 * val21 + 1 * val22;

                double gval = Math.sqrt(gx * gx + gy * gy);
                int g = (int) gval;

                if(maxGradient < g) {
                    maxGradient = g;
                }
                edgeColors[j][i] = g;
            }
        }
        double scale = 255.0 / maxGradient;
        for (int i = 1; i < y - 1; i++) {
            for (int j = 1; j < x - 1; j++) {
                int edgeColor = edgeColors[j][i];
                edgeColor = (int)(edgeColor * scale);
                edgeColor = 0xff000000 | edgeColor << 16 | edgeColor << 8 | edgeColor;
                edgePixels[j][i] = edgeColor;
            }
        }
        return edgePixels;
    }
    public static int  getGrayScale(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = (rgb) & 0xff;

        //from https://en.wikipedia.org/wiki/Grayscale, calculating luminance
        int gray = (int)(0.2126 * r + 0.7152 * g + 0.0722 * b);
        //int gray = (r + g + b) / 3;
        return gray;
    }

    private int countWhitePixels(int[][] bmp){
        int whiteCount = 0;
        for(int i=0;i<bmp.length;i++){
            for(int j=0;j<bmp[i].length;j++){
                whiteCount += ((bmp[i][j] & 0xFF)> 220)? 1 :0;
            }
        }

        return whiteCount;
    }

    private void closeCamera() {
        Log.d(TAG, "closeCamera");
        cameraClosed = true;
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Resumed, port=" + mPort);
        return mLocalbinder;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
        if (connected) {
            callback.onConnected();
        }
    }

    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    public interface Callback {
        void onConnected();

        void onError(String error);

        void onMessageReceived(String message);

        void onMessageSent(String message);
    }

    public class MyBinder extends Binder {
        public ArduinoService getService() {
            return ArduinoService.this;
        }
    }

    static class PhotoRequest {
        int id;
        long shutter;
        int wb;
        int iso;
        byte q;
        boolean front;
        boolean burst;
        int burstId;
        int burstDelay;
        int burstLength;

        PhotoRequest(int id, long shutter, int wb, int iso, byte q, boolean front, boolean burst, int delay, int length) {
            this.id = id;
            this.shutter = shutter;
            this.wb = wb;
            this.iso = iso;
            this.q = q;
            this.front = front;
            this.burst = burst;
            this.burstId = 0;
            this.burstDelay = delay;
            this.burstLength = length;
        }

        PhotoRequest(PhotoRequest orig, int burstId) {
            this(orig.id, orig.shutter, orig.wb, orig.iso, orig.q, orig.front, orig.burst, orig.burstDelay, orig.burstLength);
            this.burstId =  burstId;
        }

        @SuppressLint("DefaultLocale")
        @Override
        public @NonNull String toString() {
            return String.format("id: %d, q: %d, front: %b, iso: %d, shutter: %d, wb: %s, burst: %b, burstId: %d, delay: %d", id, q, front, iso, shutter, wb, burst, burstId, burstDelay);
        }
    }
}
