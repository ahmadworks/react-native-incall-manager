/*
 * Copyright (c) 2017 Henry Lin @zxcpoiu
 * 
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package com.zxcpoiu.incallmanager;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.pm.PackageManager;
import android.Manifest.permission;
//import android.media.AudioAttributes; // --- for API 21+
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;


import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.lang.Runnable;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;


public class InCallManagerModule extends ReactContextBaseJavaModule implements LifecycleEventListener {
    private static final String REACT_NATIVE_MODULE_NAME = "InCallManager";
    private static final String TAG = REACT_NATIVE_MODULE_NAME;
    private static SparseArray<Promise> mRequestPermissionCodePromises;
    private static SparseArray<String> mRequestPermissionCodeTargetPermission;
    private String mPackageName = "com.zxcpoiu.incallmanager";

    // --- Screen Manager
    private WindowManager.LayoutParams lastLayoutParams;
    private WindowManager mWindowManager;

    // --- AudioRouteManager
    private AudioManager audioManager;
    private boolean audioManagerActivated = false;
    private boolean isAudioFocused = false;
    private boolean isOrigAudioSetupStored = false;
    private boolean origIsSpeakerPhoneOn = false;
    private boolean origIsMicrophoneMute = false;
    private int origAudioMode = AudioManager.MODE_INVALID;
    private boolean defaultSpeakerOn = false;
    private int defaultAudioMode = AudioManager.MODE_IN_COMMUNICATION;
    private int forceSpeakerOn = 0;
    private boolean automatic = true;
    private boolean isProximityRegistered = false;
    private boolean proximityIsNear = false;
    private static final String ACTION_HEADSET_PLUG = (android.os.Build.VERSION.SDK_INT >= 21) ? AudioManager.ACTION_HEADSET_PLUG : Intent.ACTION_HEADSET_PLUG;
    private BroadcastReceiver wiredHeadsetReceiver;
    private BroadcastReceiver noisyAudioReceiver;
    private BroadcastReceiver mediaButtonReceiver;
    private OnFocusChangeListener mOnFocusChangeListener;
    // --- same as: RingtoneManager.getActualDefaultRingtoneUri(reactContext, RingtoneManager.TYPE_RINGTONE);
    private Uri defaultRingtoneUri = Settings.System.DEFAULT_RINGTONE_URI;
    private Uri defaultRingbackUri = Settings.System.DEFAULT_RINGTONE_URI;
    private Uri defaultBusytoneUri = Settings.System.DEFAULT_NOTIFICATION_URI;
    //private Uri defaultAlarmAlertUri = Settings.System.DEFAULT_ALARM_ALERT_URI; // --- too annoying
    private Uri bundleRingtoneUri;
    private Uri bundleRingbackUri;
    private Uri bundleBusytoneUri;
    private Map<String, Uri> audioUriMap;
    private MyPlayerInterface mRingtone;
    private MyPlayerInterface mRingback;
    private MyPlayerInterface mBusytone;
    private Handler mRingtoneCountDownHandler;
    private String media = "audio";
    private static String recordPermission = "unknow";
    private static String cameraPermission = "unknow";

    private static final String SPEAKERPHONE_AUTO = "auto";
    private static final String SPEAKERPHONE_TRUE = "true";
    private static final String SPEAKERPHONE_FALSE = "false";

    /**
     * AudioDevice is the names of possible audio devices that we currently
     * support.
     */
    public enum AudioDevice { SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, BLUETOOTH, NONE }

    /** AudioManager state. */
    public enum AudioManagerState {
        UNINITIALIZED,
        PREINITIALIZED,
        RUNNING,
    }

    private int savedAudioMode = AudioManager.MODE_INVALID;
    private boolean savedIsSpeakerPhoneOn = false;
    private boolean savedIsMicrophoneMute = false;


    // Default audio device; speaker phone for video calls or earpiece for audio
    // only calls.
    private AudioDevice defaultAudioDevice = AudioDevice.NONE;

    // Contains the currently selected audio device.
    // This device is changed automatically using a certain scheme where e.g.
    // a wired headset "wins" over speaker phone. It is also possible for a
    // user to explicitly select a device (and overrid any predefined scheme).
    // See |userSelectedAudioDevice| for details.
    private AudioDevice selectedAudioDevice;

    // Contains the user-selected audio device which overrides the predefined
    // selection scheme.
    // TODO(henrika): always set to AudioDevice.NONE today. Add support for
    // explicit selection based on choice by userSelectedAudioDevice.
    private AudioDevice userSelectedAudioDevice;

    // Contains speakerphone setting: auto, true or false
    private final String useSpeakerphone = SPEAKERPHONE_AUTO;


    private final InCallProximityManager proximityManager;

    // Contains a list of available audio devices. A Set collection is used to
    // avoid duplicate elements.
    private Set<AudioDevice> audioDevices = new HashSet<>();

    // Callback method for changes in audio focus.
    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;

    interface MyPlayerInterface {
        public boolean isPlaying();
        public void startPlay(Map<String, Object> data);
        public void stopPlay();
    }

    @Override
    public String getName() {
        return REACT_NATIVE_MODULE_NAME;
    }

    public InCallManagerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        mPackageName = reactContext.getPackageName();
        reactContext.addLifecycleEventListener(this);
        mWindowManager = (WindowManager) reactContext.getSystemService(Context.WINDOW_SERVICE);
        audioManager = ((AudioManager) reactContext.getSystemService(Context.AUDIO_SERVICE));
        audioUriMap = new HashMap<String, Uri>();
        audioUriMap.put("defaultRingtoneUri", defaultRingtoneUri);
        audioUriMap.put("defaultRingbackUri", defaultRingbackUri);
        audioUriMap.put("defaultBusytoneUri", defaultBusytoneUri);
        audioUriMap.put("bundleRingtoneUri", bundleRingtoneUri);
        audioUriMap.put("bundleRingbackUri", bundleRingbackUri);
        audioUriMap.put("bundleBusytoneUri", bundleBusytoneUri);
        mOnFocusChangeListener = new OnFocusChangeListener();
        mRequestPermissionCodePromises = new SparseArray<Promise>();
        mRequestPermissionCodeTargetPermission = new SparseArray<String>();
        proximityManager = InCallProximityManager.create(reactContext, this);

        Log.d(TAG, "InCallManager initialized");
    }

    private void manualTurnScreenOff() {
        Log.d(TAG, "manualTurnScreenOff()");
        UiThreadUtil.runOnUiThread(new Runnable() {
            public void run() {
                Activity mCurrentActivity = getCurrentActivity();
                if (mCurrentActivity == null) {
                    Log.d(TAG, "ReactContext doesn't hava any Activity attached.");
                    return;
                }
                Window window = mCurrentActivity.getWindow();
                WindowManager.LayoutParams params = window.getAttributes();
                lastLayoutParams = params; // --- store last param
                params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF; // --- Dim as dark as possible. see BRIGHTNESS_OVERRIDE_OFF
                window.setAttributes(params);
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    private void manualTurnScreenOn() {
        Log.d(TAG, "manualTurnScreenOn()");
        UiThreadUtil.runOnUiThread(new Runnable() {
            public void run() {
                Activity mCurrentActivity = getCurrentActivity();
                if (mCurrentActivity == null) {
                    Log.d(TAG, "ReactContext doesn't hava any Activity attached.");
                    return;
                }
                Window window = mCurrentActivity.getWindow();
                if (lastLayoutParams != null) {
                    window.setAttributes(lastLayoutParams);
                } else {
                    WindowManager.LayoutParams params = window.getAttributes();
                    params.screenBrightness = -1; // --- Dim to preferable one
                    window.setAttributes(params);
                }
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }


    public void onProximitySensorChangedState(boolean isNear) {
        if (isNear) {
            turnScreenOff();
        } else {
            turnScreenOn();
        }
        updateAudioRoute();
        WritableMap data = Arguments.createMap();
        data.putBoolean("isNear", isNear);
        sendEvent("Proximity", data);
    }


    private void startProximitySensor() {
        if (!proximityManager.isProximitySupported()) {
            Log.d(TAG, "Proximity Sensor is not supported.");
            return;
        }
        if (isProximityRegistered) {
            Log.d(TAG, "Proximity Sensor is already registered.");
            return;
        }
        // --- SENSOR_DELAY_FASTEST(0 milisecs), SENSOR_DELAY_GAME(20 milisecs), SENSOR_DELAY_UI(60 milisecs), SENSOR_DELAY_NORMAL(200 milisecs)
        if (!proximityManager.start()) {
            Log.d(TAG, "proximityManager.start() failed. return false");
            return;
        }
        Log.d(TAG, "startProximitySensor()");
        isProximityRegistered = true;
    }

    private void stopProximitySensor() {
        if (!proximityManager.isProximitySupported()) {
            Log.d(TAG, "Proximity Sensor is not supported.");
            return;
        }
        if (!isProximityRegistered) {
            Log.d(TAG, "Proximity Sensor is not registered.");
            return;
        }
        Log.d(TAG, "stopProximitySensor()");
        proximityManager.stop();
        isProximityRegistered = false;
    }

    private class OnFocusChangeListener implements AudioManager.OnAudioFocusChangeListener {

        @Override
        public void onAudioFocusChange(final int focusChange) {
            String focusChangeStr;
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_GAIN:
                    focusChangeStr = "AUDIOFOCUS_GAIN";
                    break;
                case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:
                    focusChangeStr = "AUDIOFOCUS_GAIN_TRANSIENT";
                    break;
                case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE:
                    focusChangeStr = "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE";
                    break;
                case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
                    focusChangeStr = "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK";
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                    focusChangeStr = "AUDIOFOCUS_LOSS";
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    focusChangeStr = "AUDIOFOCUS_LOSS_TRANSIENT";
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    focusChangeStr = "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK";
                    break;
                default:
                    focusChangeStr = "AUDIOFOCUS_UNKNOW";
                    break;
            }

            Log.d(TAG, "onAudioFocusChange: " + focusChange + " - " + focusChangeStr);

            WritableMap data = Arguments.createMap();
            data.putString("eventText", focusChangeStr);
            data.putInt("eventCode", focusChange);
            sendEvent("onAudioFocusChange", data);
        }
    }

    
    private void sendEvent(final String eventName, @Nullable WritableMap params) {
        try {
            ReactContext reactContext = getReactApplicationContext();
            if (reactContext != null && reactContext.hasActiveCatalystInstance()) {
                reactContext
                        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                        .emit(eventName, params);
            } else {
                Log.e(TAG, "sendEvent(): reactContext is null or not having CatalystInstance yet.");
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "sendEvent(): java.lang.RuntimeException: Trying to invoke JS before CatalystInstance has been set!");
        }
    }

    @ReactMethod
    public void start(final String _media, final boolean auto, final String ringbackUriType) {
        audioManagerActivated = true;
        requestAudioFocus();
        startEvents();
        if (!ringbackUriType.isEmpty()) {
            startRingback(ringbackUriType);
        }
    }

    public void stop() {
        stop("");
    }

    @ReactMethod
    public void stop(final String busytoneUriType) {
        if (audioManagerActivated) {
            stopRingback();
            if (!busytoneUriType.isEmpty() && startBusytone(busytoneUriType)) {
                stopProximitySensor();
                // play busytone first, and call this func again when finish
                Log.d(TAG, "play busytone before stop InCallManager");
                return;
            } else {
                Log.d(TAG, "stop() InCallManager");
                stopBusytone();
                stopEvents();
                releaseAudioFocus();
                audioManagerActivated = false;
            }
        }
    }

    private void startEvents() {
        startProximitySensor(); // --- proximity event always enable, but only turn screen off when audio is routing to earpiece.
    }

    private void stopEvents() {
        stopProximitySensor();
    }

    private void requestAudioFocus() {
        if (!isAudioFocused) {
            int result = audioManager.requestAudioFocus(mOnFocusChangeListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.d(TAG, "AudioFocus granted");
                isAudioFocused = true;
            } else if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                Log.d(TAG, "AudioFocus failed");
                isAudioFocused = false;
            }
        }
    }

    private void releaseAudioFocus() {
        if (isAudioFocused) {
            audioManager.abandonAudioFocus(null);
            isAudioFocused = false;
        }
    }



    

    @ReactMethod
    public void turnScreenOn() {
        if (proximityManager.isProximityWakeLockSupported()) {
            Log.d(TAG, "turnScreenOn(): use proximity lock.");
            proximityManager.releaseProximityWakeLock(true);
        } else {
            Log.d(TAG, "turnScreenOn(): proximity lock is not supported. try manually.");
            manualTurnScreenOn();
        }
    }

    @ReactMethod
    public void turnScreenOff() {
        if (proximityManager.isProximityWakeLockSupported()) {
            Log.d(TAG, "turnScreenOff(): use proximity lock.");
            proximityManager.acquireProximityWakeLock();
        } else {
            Log.d(TAG, "turnScreenOff(): proximity lock is not supported. try manually.");
            manualTurnScreenOff();
        }
    }

    @ReactMethod
    public void setKeepScreenOn(final boolean enable) {
        Log.d(TAG, "setKeepScreenOn() " + enable);
        UiThreadUtil.runOnUiThread(new Runnable() {
            public void run() {
                Activity mCurrentActivity = getCurrentActivity();
                if (mCurrentActivity == null) {
                    Log.d(TAG, "ReactContext doesn't hava any Activity attached.");
                    return;
                }
                Window window = mCurrentActivity.getWindow();
                if (enable) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            }
        });
    }

    @ReactMethod
    public void setSpeakerphoneOn(final boolean enable) {
    }


    // --- TODO (zxcpoiu): Implement api to let user choose audio devices

    @ReactMethod
    public void setMicrophoneMute(final boolean enable) {

    }

    /** 
     * This is part of start() process. 
     * ringbackUriType must not empty. empty means do not play.
     */
    @ReactMethod
    public void startRingback(final String ringbackUriType) {
        if (ringbackUriType.isEmpty()) {
            return;
        }
        try {
            Log.d(TAG, "startRingback(): UriType=" + ringbackUriType);
            if (mRingback != null) {
                if (mRingback.isPlaying()) {
                    Log.d(TAG, "startRingback(): is already playing");
                    return;
                } else {
                    stopRingback(); // --- use brandnew instance
                }
            }

            Uri ringbackUri;
            Map data = new HashMap<String, Object>();
            data.put("name", "mRingback");
            if (ringbackUriType.equals("_DTMF_")) {
                mRingback = new myToneGenerator(myToneGenerator.RINGBACK);
                mRingback.startPlay(data);
                return;
            } else {
                ringbackUri = getRingbackUri(ringbackUriType);
                if (ringbackUri == null) {
                    Log.d(TAG, "startRingback(): no available media");
                    return;    
                }
            }

            mRingback = new myMediaPlayer();
            data.put("sourceUri", ringbackUri);
            data.put("setLooping", true);
            data.put("audioStream", AudioManager.STREAM_VOICE_CALL);
            /*
            TODO: for API 21
            data.put("audioFlag", AudioAttributes.FLAG_AUDIBILITY_ENFORCED);
            data.put("audioUsage", AudioAttributes.USAGE_VOICE_COMMUNICATION); // USAGE_VOICE_COMMUNICATION_SIGNALLING ?
            data.put("audioContentType", AudioAttributes.CONTENT_TYPE_SPEECH); // CONTENT_TYPE_MUSIC ?
            */
            setMediaPlayerEvents((MediaPlayer)mRingback, "mRingback");
            mRingback.startPlay(data);
        } catch(Exception e) {
            Log.d(TAG, "startRingback() failed");
        }   
    }

    @ReactMethod
    public void stopRingback() {
        try {
            if (mRingback != null) {
                mRingback.stopPlay();
                mRingback = null;
            }
        } catch(Exception e) {
            Log.d(TAG, "stopRingback() failed");
        }   
    }

    /** 
     * This is part of start() process. 
     * busytoneUriType must not empty. empty means do not play.
     * return false to indicate play tone failed and should be stop() immediately
     * otherwise, it will stop() after a tone completed.
     */
    public boolean startBusytone(final String busytoneUriType) {
        if (busytoneUriType.isEmpty()) {
            return false;
        }
        try {
            Log.d(TAG, "startBusytone(): UriType=" + busytoneUriType);
            if (mBusytone != null) {
                if (mBusytone.isPlaying()) {
                    Log.d(TAG, "startBusytone(): is already playing");
                    return false;
                } else {
                    stopBusytone(); // --- use brandnew instance
                }
            }

            Uri busytoneUri;
            Map data = new HashMap<String, Object>();
            data.put("name", "mBusytone");
            if (busytoneUriType.equals("_DTMF_")) {
                mBusytone = new myToneGenerator(myToneGenerator.BUSY);
                mBusytone.startPlay(data);
                return true;
            } else {
                busytoneUri = getBusytoneUri(busytoneUriType);
                if (busytoneUri == null) {
                    Log.d(TAG, "startBusytone(): no available media");
                    return false;    
                }
            }

            mBusytone = new myMediaPlayer();
            data.put("sourceUri", busytoneUri);
            data.put("setLooping", false);
            data.put("audioStream", AudioManager.STREAM_RING);


            setMediaPlayerEvents((MediaPlayer)mBusytone, "mBusytone");

            mBusytone.startPlay(data);
            return true;
        } catch(Exception e) {
            Log.d(TAG, "startBusytone() failed");
            Log.d(TAG, e.getMessage());
            return false;
        }   
    }

    public void stopBusytone() {
        try {
            if (mBusytone != null) {
                mBusytone.stopPlay();
                mBusytone = null;
            }
        } catch(Exception e) {
            Log.d(TAG, "stopBusytone() failed");
        }   
    }

    @ReactMethod
    public void startRingtone(final String ringtoneUriType, final int seconds) {
        try {
            Log.d(TAG, "startRingtone(): UriType=" + ringtoneUriType);
            if (mRingtone != null) {
                if (mRingtone.isPlaying()) {
                    Log.d(TAG, "startRingtone(): is already playing");
                    return;
                } else {
                    stopRingtone(); // --- use brandnew instance
                }
            }

            //if (!audioManager.isStreamMute(AudioManager.STREAM_RING)) {
            //if (origRingerMode == AudioManager.RINGER_MODE_NORMAL) {
            if (audioManager.getStreamVolume(AudioManager.STREAM_RING) == 0) {
                Log.d(TAG, "startRingtone(): ringer is silent. leave without play.");
                return;
            }

            // --- there is no _DTMF_ option in startRingtone()
            Uri ringtoneUri = getRingtoneUri(ringtoneUriType);
            if (ringtoneUri == null) {
                Log.d(TAG, "startRingtone(): no available media");
                return;    
            }



            Map data = new HashMap<String, Object>();
            mRingtone = new myMediaPlayer();
            data.put("name", "mRingtone");
            data.put("sourceUri", ringtoneUri);
            data.put("setLooping", true);
            data.put("audioStream", AudioManager.STREAM_RING);
            /*
            TODO: for API 21
            data.put("audioFlag", 0);
            data.put("audioUsage", AudioAttributes.USAGE_NOTIFICATION_RINGTONE); // USAGE_NOTIFICATION_COMMUNICATION_REQUEST ?
            data.put("audioContentType", AudioAttributes.CONTENT_TYPE_MUSIC);
            */
            setMediaPlayerEvents((MediaPlayer) mRingtone, "mRingtone");
            mRingtone.startPlay(data);

            if (seconds > 0) {
                mRingtoneCountDownHandler = new Handler();
                mRingtoneCountDownHandler.postDelayed(new Runnable() {
                    public void run() {
                        try {
                            Log.d(TAG, String.format("mRingtoneCountDownHandler.stopRingtone() timeout after %d seconds", seconds));
                            stopRingtone();
                        } catch(Exception e) {
                            Log.d(TAG, "mRingtoneCountDownHandler.stopRingtone() failed.");
                        }
                    }
                }, seconds * 1000);
            }
        } catch(Exception e) {
            Log.d(TAG, "startRingtone() failed");
        }   
    }

    @ReactMethod
    public void stopRingtone() {
        try {
            if (mRingtone != null) {
                mRingtone.stopPlay();
                mRingtone = null;
            }
            if (mRingtoneCountDownHandler != null) {
                mRingtoneCountDownHandler.removeCallbacksAndMessages(null);
                mRingtoneCountDownHandler = null;
            }
        } catch(Exception e) {
            Log.d(TAG, "stopRingtone() failed");
        }
    }

    private void setMediaPlayerEvents(MediaPlayer mp, final String name) {

        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            //http://developer.android.com/reference/android/media/MediaPlayer.OnErrorListener.html
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Log.d(TAG, String.format("MediaPlayer %s onError(). what: %d, extra: %d", name, what, extra));
                //return True if the method handled the error
                //return False, or not having an OnErrorListener at all, will cause the OnCompletionListener to be called. Get news & tips 
                return true;
            }
        });

        mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            //http://developer.android.com/reference/android/media/MediaPlayer.OnInfoListener.html
            @Override
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                Log.d(TAG, String.format("MediaPlayer %s onInfo(). what: %d, extra: %d", name, what, extra));
                //return True if the method handled the info
                //return False, or not having an OnInfoListener at all, will cause the info to be discarded.
                return true;
            }
        });

        mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                Log.d(TAG, String.format("MediaPlayer %s onPrepared(), start play, isSpeakerPhoneOn %b", name, audioManager.isSpeakerphoneOn()));
                if (name.equals("mBusytone")) {
                    audioManager.setMode(AudioManager.MODE_RINGTONE);
                } else if (name.equals("mRingback")) {
                    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                } else if (name.equals("mRingtone")) {
                    audioManager.setMode(AudioManager.MODE_RINGTONE);
                } 
                updateAudioRoute();
                mp.start();
            }
        });

        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                Log.d(TAG, String.format("MediaPlayer %s onCompletion()", name));
                if (name.equals("mBusytone")) {
                    Log.d(TAG, "MyMediaPlayer(): invoke stop()");
                    stop();
                    
                }
            }
        });

    }


// ===== File Uri Start =====
    @ReactMethod
    public void getAudioUriJS(String audioType, String fileType, Promise promise) {
        Uri result = null;
        if (audioType.equals("ringback")) {
            result = getRingbackUri(fileType);
        } else if (audioType.equals("busytone")) {
            result = getBusytoneUri(fileType);
        } else if (audioType.equals("ringtone")) {
            result = getRingtoneUri(fileType);
        }
        try {
            if (result != null) {
                promise.resolve(result.toString());
            } else {
                promise.reject("failed");
            }
        } catch (Exception e) {
            promise.reject("failed");
        }
    }

    private Uri getRingtoneUri(final String _type) {
        final String fileBundle = "incallmanager_ringtone";
        final String fileBundleExt = "mp3";
        final String fileSysWithExt = "media_volume.ogg";
        final String fileSysPath = "/system/media/audio/ui"; // --- every devices all ships with different in ringtone. maybe ui sounds are more "stock"
        String type;
        // --- _type MAY be empty
        if (_type.equals("_DEFAULT_") ||  _type.isEmpty()) {
            //type = fileSysWithExt;
            return getDefaultUserUri("defaultRingtoneUri");
        } else {
            type = _type;
        }
        return getAudioUri(type, fileBundle, fileBundleExt, fileSysWithExt, fileSysPath, "bundleRingtoneUri", "defaultRingtoneUri");
    }

    private Uri getRingbackUri(final String _type) {
        final String fileBundle = "incallmanager_ringback";
        final String fileBundleExt = "mp3";
        final String fileSysWithExt = "media_volume.ogg";
        final String fileSysPath = "/system/media/audio/ui"; // --- every devices all ships with different in ringtone. maybe ui sounds are more "stock"
        String type;
        // --- _type would never be empty here. just in case.
        if (_type.equals("_DEFAULT_") ||  _type.isEmpty()) {
            //type = fileSysWithExt;
            return getDefaultUserUri("defaultRingbackUri");
        } else {
            type = _type;
        }
        return getAudioUri(type, fileBundle, fileBundleExt, fileSysWithExt, fileSysPath, "bundleRingbackUri", "defaultRingbackUri");
    }

    private Uri getBusytoneUri(final String _type) {
        final String fileBundle = "incallmanager_busytone";
        final String fileBundleExt = "mp3";
        final String fileSysWithExt = "LowBattery.ogg";
        final String fileSysPath = "/system/media/audio/ui"; // --- every devices all ships with different in ringtone. maybe ui sounds are more "stock"
        String type;
        // --- _type would never be empty here. just in case.
        if (_type.equals("_DEFAULT_") ||  _type.isEmpty()) {
            //type = fileSysWithExt; // --- 
            return getDefaultUserUri("defaultBusytoneUri");
        } else {
            type = _type;
        }
        return getAudioUri(type, fileBundle, fileBundleExt, fileSysWithExt, fileSysPath, "bundleBusytoneUri", "defaultBusytoneUri");
    }

    private Uri getAudioUri(final String _type, final String fileBundle, final String fileBundleExt, final String fileSysWithExt, final String fileSysPath, final String uriBundle, final String uriDefault) {
        String type = _type;
        if (type.equals("_BUNDLE_")) {
            if (audioUriMap.get(uriBundle) == null) {
                int res = 0;
                ReactContext reactContext = getReactApplicationContext();
                if (reactContext != null) {
                    res = reactContext.getResources().getIdentifier(fileBundle, "raw", mPackageName);
                } else {
                    Log.d(TAG, "getAudioUri() reactContext is null");
                }
                if (res <= 0) {
                    Log.d(TAG, String.format("getAudioUri() %s.%s not found in bundle.", fileBundle, fileBundleExt));
                    audioUriMap.put(uriBundle, null);
                    //type = fileSysWithExt;
                    return getDefaultUserUri(uriDefault); // --- if specified bundle but not found, use default directlly
                } else {
                    audioUriMap.put(uriBundle, Uri.parse("android.resource://" + mPackageName + "/" + Integer.toString(res)));
                    //bundleRingtoneUri = Uri.parse("android.resource://" + reactContext.getPackageName() + "/" + R.raw.incallmanager_ringtone);
                    //bundleRingtoneUri = Uri.parse("android.resource://" + reactContext.getPackageName() + "/raw/incallmanager_ringtone");
                    Log.d(TAG, "getAudioUri() using: " + type);
                    return audioUriMap.get(uriBundle);
                }
            } else {
                Log.d(TAG, "getAudioUri() using: " + type);
                return audioUriMap.get(uriBundle);
            }
        }

        // --- Check file every time in case user deleted.
        final String target = fileSysPath + "/" + type;
        Uri _uri = getSysFileUri(target);
        if (_uri == null) {
            Log.d(TAG, "getAudioUri() using user default");
            return getDefaultUserUri(uriDefault);
        } else {
            Log.d(TAG, "getAudioUri() using internal: " + target);
            audioUriMap.put(uriDefault, _uri);
            return _uri;
        }
    }

    private Uri getSysFileUri(final String target) {
        File file = new File(target);
        if (file.isFile()) {
            return Uri.fromFile(file);
        }
        return null;
    }

    private Uri getDefaultUserUri(final String type) {
        // except ringtone, it doesn't suppose to be go here. and every android has different files unlike apple;
        if (type.equals("defaultRingtoneUri")) {
            return Settings.System.DEFAULT_RINGTONE_URI;
        } else if (type.equals("defaultRingbackUri")) {
            return Settings.System.DEFAULT_RINGTONE_URI;
        } else if (type.equals("defaultBusytoneUri")) {
            return Settings.System.DEFAULT_NOTIFICATION_URI; // --- DEFAULT_ALARM_ALERT_URI
        } else {
            return Settings.System.DEFAULT_NOTIFICATION_URI;
        }
    }
// ===== File Uri End =====


// ===== Internal Classes Start =====
    private class myToneGenerator extends Thread implements MyPlayerInterface {
        private int toneType;
        private int toneCategory;
        private boolean playing = false;
        private static final int maxWaitTimeMs = 3600000; // 1 hour fairly enough
        private static final int loadBufferWaitTimeMs = 20;
        private static final int toneVolume = 100; // The volume of the tone, given in percentage of maximum volume (from 0-100).
        // --- constant in ToneGenerator all below 100
        public static final int BEEP = 101;
        public static final int BUSY = 102;
        public static final int CALLEND = 103;
        public static final int CALLWAITING = 104;
        public static final int RINGBACK = 105;
        public static final int SILENT = 106;
        public int customWaitTimeMs = maxWaitTimeMs;
        public String caller;

        myToneGenerator(final int t) {
            super();
            toneCategory = t;
        }

        public void setCustomWaitTime(final int ms) {
            customWaitTimeMs = ms;
        }

        @Override
        public void startPlay(final Map data) {
            String name = (String) data.get("name");
            caller = name;
            start();
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }

        @Override
        public void stopPlay() {
            synchronized (this) {
                if (playing) {
                    notify();
                }
                playing = false;
            }
        }

        @Override
        public void run() {
            int toneWaitTimeMs;
            switch (toneCategory) {
                case SILENT:
                    //toneType = ToneGenerator.TONE_CDMA_SIGNAL_OFF;
                    toneType = ToneGenerator.TONE_CDMA_ANSWER;
                    toneWaitTimeMs = 1000;
                    break;
                case BUSY:
                    //toneType = ToneGenerator.TONE_SUP_BUSY;
                    //toneType = ToneGenerator.TONE_SUP_CONGESTION;
                    //toneType = ToneGenerator.TONE_SUP_CONGESTION_ABBREV;
                    //toneType = ToneGenerator.TONE_CDMA_NETWORK_BUSY;
                    //toneType = ToneGenerator.TONE_CDMA_NETWORK_BUSY_ONE_SHOT;
                    toneType = ToneGenerator.TONE_SUP_RADIO_NOTAVAIL;
                    toneWaitTimeMs = 4000;
                    break;
                case RINGBACK:
                    //toneType = ToneGenerator.TONE_SUP_RINGTONE;
                    toneType = ToneGenerator.TONE_CDMA_NETWORK_USA_RINGBACK;
                    toneWaitTimeMs = maxWaitTimeMs; // [STOP MANUALLY]
                    break;
                case CALLEND:
                    toneType = ToneGenerator.TONE_PROP_PROMPT;
                    toneWaitTimeMs = 200; // plays when call ended
                    break;
                case CALLWAITING:
                    //toneType = ToneGenerator.TONE_CDMA_NETWORK_CALLWAITING;
                    toneType = ToneGenerator.TONE_SUP_CALL_WAITING;
                    toneWaitTimeMs = maxWaitTimeMs; // [STOP MANUALLY]
                    break;
                case BEEP:
                    //toneType = ToneGenerator.TONE_SUP_PIP;
                    //toneType = ToneGenerator.TONE_CDMA_PIP;
                    //toneType = ToneGenerator.TONE_SUP_RADIO_ACK;
                    //toneType = ToneGenerator.TONE_PROP_BEEP;
                    toneType = ToneGenerator.TONE_PROP_BEEP2;
                    toneWaitTimeMs = 1000; // plays when call ended
                    break;
                default:
                    // --- use ToneGenerator internal type.
                    Log.d(TAG, "myToneGenerator: use internal tone type: " + toneCategory);
                    toneType = toneCategory;
                    toneWaitTimeMs = customWaitTimeMs;
            }
            Log.d(TAG, String.format("myToneGenerator: toneCategory: %d ,toneType: %d, toneWaitTimeMs: %d", toneCategory, toneType, toneWaitTimeMs));

            ToneGenerator tg;
            try {
                tg = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, toneVolume);
            } catch (RuntimeException e) {
                Log.d(TAG, "myToneGenerator: Exception caught while creating ToneGenerator: " + e);
                tg = null;
            }

            if (tg != null) {
                synchronized (this) {
                    if (!playing) {
                        playing = true;

                        // --- make sure audio routing, or it will be wired when switch suddenly
                        if (caller.equals("mBusytone")) {
                            audioManager.setMode(AudioManager.MODE_RINGTONE);
                        } else if (caller.equals("mRingback")) {
                            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                        } else if (caller.equals("mRingtone")) {
                            audioManager.setMode(AudioManager.MODE_RINGTONE);
                        } 
                        InCallManagerModule.this.updateAudioRoute();

                        tg.startTone(toneType);
                        try {
                            wait(toneWaitTimeMs + loadBufferWaitTimeMs);
                        } catch  (InterruptedException e) {
                            Log.d(TAG, "myToneGenerator stopped. toneType: " + toneType);
                        }
                        tg.stopTone();
                    }
                    playing = false;
                    tg.release();
                }
            }
            Log.d(TAG, "MyToneGenerator(): play finished. caller=" + caller);
            if (caller.equals("mBusytone")) {
                Log.d(TAG, "MyToneGenerator(): invoke stop()");
                InCallManagerModule.this.stop();
            }
        }
    }

    private class myMediaPlayer extends MediaPlayer implements MyPlayerInterface {

        //myMediaPlayer() {
        //    super();
        //}

        @Override
        public void stopPlay() {
            stop();
            reset();
            release();
        }

        @Override
        public void startPlay(final Map data) {
            try {
                Uri sourceUri = (Uri) data.get("sourceUri");
                boolean setLooping = (Boolean) data.get("setLooping");
                int stream = (Integer) data.get("audioStream");
                String name = (String) data.get("name");

                ReactContext reactContext = getReactApplicationContext();
                setDataSource(reactContext, sourceUri);
                setLooping(setLooping);
                setAudioStreamType(stream); // is better using STREAM_DTMF for ToneGenerator?

                /*
                // TODO: use modern and more explicit audio stream api
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    int audioFlag = (Integer) data.get("audioFlag");
                    int audioUsage = (Integer) data.get("audioUsage");
                    int audioContentType = (Integer) data.get("audioContentType");

                    setAudioAttributes(
                        new AudioAttributes.Builder()
                            .setFlags(audioFlag)
                            .setLegacyStreamType(stream)
                            .setUsage(audioUsage)
                            .setContentType(audioContentType)
                            .build()
                    );
                }
                */

                // -- will start at onPrepared() event
                prepareAsync();
            } catch (Exception e) {
                Log.d(TAG, "startPlay() failed");
            }
        }

        @Override
        public boolean isPlaying() {
            return super.isPlaying();
        }
    }
// ===== Internal Classes End =====

//  ===== Permission Start =====
    @ReactMethod
    public void checkRecordPermission(Promise promise) {
        Log.d(TAG, "RNInCallManager.checkRecordPermission(): enter");
        _checkRecordPermission();
        if (recordPermission.equals("unknow")) {
            Log.d(TAG, "RNInCallManager.checkRecordPermission(): failed");
            promise.reject(new Exception("checkRecordPermission failed"));
        } else {
            promise.resolve(recordPermission);
        }
    }

    @ReactMethod
    public void checkCameraPermission(Promise promise) {
        Log.d(TAG, "RNInCallManager.checkCameraPermission(): enter");
        _checkCameraPermission();
        if (cameraPermission.equals("unknow")) {
            Log.d(TAG, "RNInCallManager.checkCameraPermission(): failed");
            promise.reject(new Exception("checkCameraPermission failed"));
        } else {
            promise.resolve(cameraPermission);
        }
    }

    private void _checkRecordPermission() {
        recordPermission = _checkPermission(permission.RECORD_AUDIO);
        Log.d(TAG, String.format("RNInCallManager.checkRecordPermission(): recordPermission=%s", recordPermission));
    }

    private void _checkCameraPermission() {
        cameraPermission = _checkPermission(permission.CAMERA);
        Log.d(TAG, String.format("RNInCallManager.checkCameraPermission(): cameraPermission=%s", cameraPermission));
    }

    private String _checkPermission(String targetPermission) {
        try {
            ReactContext reactContext = getReactApplicationContext();
            if (ContextCompat.checkSelfPermission(reactContext, targetPermission) == PackageManager.PERMISSION_GRANTED) {
                return "granted";
            } else {
                return "denied";
            }
        } catch (Exception e) {
            Log.d(TAG, "_checkPermission() catch");
            return "denied";
        }
    }

    @ReactMethod
    public void requestRecordPermission(Promise promise) {
        Log.d(TAG, "RNInCallManager.requestRecordPermission(): enter");
        _checkRecordPermission();
        if (!recordPermission.equals("granted")) {
            _requestPermission(permission.RECORD_AUDIO, promise);
        } else {
            // --- already granted
            promise.resolve(recordPermission);
        }
    }

    @ReactMethod
    public void requestCameraPermission(Promise promise) {
        Log.d(TAG, "RNInCallManager.requestCameraPermission(): enter");
        _checkCameraPermission();
        if (!cameraPermission.equals("granted")) {
            _requestPermission(permission.CAMERA, promise);
        } else {
            // --- already granted
            promise.resolve(cameraPermission);
        }
    }


    private void _requestPermission(String targetPermission, Promise promise) {
        Activity currentActivity = getCurrentActivity();
        if (currentActivity == null) {
            Log.d(TAG, String.format("RNInCallManager._requestPermission(): ReactContext doesn't hava any Activity attached when requesting %s", targetPermission));
            promise.reject(new Exception("_requestPermission(): currentActivity is not attached"));
            return;
        }
        int requestPermissionCode = getRandomInteger(1, 65535);
        while (mRequestPermissionCodePromises.get(requestPermissionCode, null) != null) {
            requestPermissionCode = getRandomInteger(1, 65535);
        }
        mRequestPermissionCodePromises.put(requestPermissionCode, promise);
        mRequestPermissionCodeTargetPermission.put(requestPermissionCode, targetPermission);
        /*
        if (ActivityCompat.shouldShowRequestPermissionRationale(currentActivity, permission.RECORD_AUDIO)) {
            showMessageOKCancel("You need to allow access to microphone for making call", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    ActivityCompat.requestPermissions(currentActivity, new String[] {permission.RECORD_AUDIO}, requestPermissionCode);
                }
            });
            return;
        }
        */
        ActivityCompat.requestPermissions(currentActivity, new String[] {targetPermission}, requestPermissionCode);
    }

    private static int getRandomInteger(int min, int max) {
        if (min >= max) {
            throw new IllegalArgumentException("max must be greater than min");
        }
        Random random = new Random();
        return random.nextInt((max - min) + 1) + min;
    }

    protected static void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        Log.d(TAG, "RNInCallManager.onRequestPermissionsResult(): enter");
        Promise promise = mRequestPermissionCodePromises.get(requestCode, null);
        String targetPermission = mRequestPermissionCodeTargetPermission.get(requestCode, null);
        mRequestPermissionCodePromises.delete(requestCode);
        mRequestPermissionCodeTargetPermission.delete(requestCode);
        if (promise != null && targetPermission != null) {

            Map<String, Integer> permissionResultMap = new HashMap<String, Integer>();

            for (int i = 0; i < permissions.length; i++) {
                permissionResultMap.put(permissions[i], grantResults[i]);
            }

            if (!permissionResultMap.containsKey(targetPermission)) {
                Log.wtf(TAG, String.format("RNInCallManager.onRequestPermissionsResult(): requested permission %s but did not appear", targetPermission));
                promise.reject(String.format("%s_PERMISSION_NOT_FOUND", targetPermission), String.format("requested permission %s but did not appear", targetPermission));
                return;
            }

            String _requestPermissionResult = "unknow";
            if (permissionResultMap.get(targetPermission) == PackageManager.PERMISSION_GRANTED) {
                _requestPermissionResult = "granted";
            } else {
                _requestPermissionResult = "denied";
            }

            if (targetPermission.equals(permission.RECORD_AUDIO)) {
                recordPermission = _requestPermissionResult;
            } else if (targetPermission.equals(permission.CAMERA)) {
                cameraPermission = _requestPermissionResult;
            }
            promise.resolve(_requestPermissionResult);
        } else {
            //super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            Log.wtf(TAG, "RNInCallManager.onRequestPermissionsResult(): request code not found");
            promise.reject("PERMISSION_REQUEST_CODE_NOT_FOUND", "request code not found");
        }
    }
//  ===== Permission End =====

    private void pause() {
        if (audioManagerActivated) {
            Log.d(TAG, "pause audioRouteManager");
            stopEvents();
        }
    }

    private void resume() {
        if (audioManagerActivated) {
            Log.d(TAG, "resume audioRouteManager");
            startEvents();
        }
    }

    @Override
    public void onHostResume() {
        Log.d(TAG, "onResume()");
        //resume();
    }

    @Override
    public void onHostPause() {
        Log.d(TAG, "onPause()");
        //pause();
    }

    @Override
    public void onHostDestroy() {
        Log.d(TAG, "onDestroy()");
        stopRingtone();
        stopRingback();
        stopBusytone();
        stop();
    }

    private void updateAudioRoute() {
        if (!automatic) {
            return;
        }
    }




}
