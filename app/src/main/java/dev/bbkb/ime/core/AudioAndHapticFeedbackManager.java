package dev.bbkb.ime.core;

import android.content.Context;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.View;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.BuildConfig;



public final class AudioAndHapticFeedbackManager {

    static final String TAG = "AudioAndHapticFeedbackManager";

    /** Concurrent keypress sounds the pool can mix. */
    private static final int MAX_SOUND_STREAMS = 4;

    private static final AudioAndHapticFeedbackManager sInstance = new AudioAndHapticFeedbackManager();

    private AudioManager audioManager;

    private Vibrator vibrator;

    private SettingsValues settings;

    private boolean soundEnabled;

    private SoundPool soundPool = null;

    private final SparseIntArray soundIds = new SparseIntArray();

    final int[] SOUND_RES_IDS = {R.raw.input_delswipe, R.raw.input_enter, R.raw.input_keypress, R.raw.input_aux, R.raw.input_predswipe, R.raw.input_space, R.raw.input_symswipe, R.raw.input_modifier, R.raw.input_correction};

    public static AudioAndHapticFeedbackManager getInstance() {
        return sInstance;
    }

    private AudioAndHapticFeedbackManager() {
    }

    public static void init(Context context) {
        sInstance.initInternal(context);
    }

    private void initInternal(Context context) {
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        // Audit CT-14: VIBRATOR_SERVICE is deprecated from API 31 in favour of VibratorManager.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager =
                    (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            this.vibrator = vibratorManager != null ? vibratorManager.getDefaultVibrator() : null;
        } else {
            this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        releaseSounds();
    }

    private void loadSounds(Context context) {
        if (this.soundPool == null) {
            // Audit CT-14: the 3-arg SoundPool constructor is deprecated (API 21) and its stream
            // type was written as a bare `1` (STREAM_SYSTEM). Without an explicit
            // USAGE_ASSISTANCE_SONIFICATION attribute set, keypress clicks route to the wrong
            // stream on modern Android and duck the user's media.
            this.soundPool = new SoundPool.Builder()
                    .setMaxStreams(MAX_SOUND_STREAMS)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .build();
        }
        for (int i = 0; i < this.SOUND_RES_IDS.length; i++) {
            try {
                if (this.soundIds.get(this.SOUND_RES_IDS[i]) == 0) {
                    this.soundIds.put(this.SOUND_RES_IDS[i], this.soundPool.load(context, this.SOUND_RES_IDS[i], 1));
                }
            } catch (Resources.NotFoundException e) {
                releaseSounds();
                if (BuildConfig.DEBUG) Log.e(TAG, "Keyboard sound files failed to load", e);
                return;
            }
        }
    }

    private void releaseSounds() {
        int i = 0;
        while (true) {
            int[] iArr = this.SOUND_RES_IDS;
            if (i >= iArr.length) {
                break;
            }
            int i2 = this.soundIds.get(iArr[i]);
            if (i2 != 0) {
                this.soundPool.unload(i2);
            }
            i++;
        }
        this.soundIds.clear();
        SoundPool soundPool = this.soundPool;
        if (soundPool != null) {
            soundPool.release();
            this.soundPool = null;
        }
    }

    public void performAudioAndHapticFeedback(int i, View view) {
        performHapticFeedback(view);
        performAudioFeedback(i);
    }

    public boolean hasVibrator() {
        Vibrator vibrator = this.vibrator;
        return vibrator != null && vibrator.hasVibrator();
    }

    public void vibrate(long durationMs) {
        Vibrator vibrator = this.vibrator;
        if (vibrator == null) {
            return;
        }
        // Audit CT-14: Vibrator.vibrate(long) is deprecated from API 26.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(durationMs);
        }
    }

    private boolean reevaluateIfSoundIsOn() {
        AudioManager audioManager;
        SettingsValues c0804d = this.settings;
        return c0804d != null && c0804d.isSoundEnabled && (audioManager = this.audioManager) != null && audioManager.getRingerMode() == 2;
    }

    public void performAudioFeedback(int i) {
        if (!this.soundEnabled || this.soundPool == null) {
            return;
        }
        
        int soundId;
        
        // Handle specific key codes first
        if (i == -29 || i == -27 || i == -23) {
            soundId = 0;
        } else if (i == -15 || i == -3) {
            soundId = this.soundIds.get(R.raw.input_aux);
        } else if (i == -1) {
            soundId = this.soundIds.get(R.raw.input_modifier);
        } else if (i == 10) {
            soundId = this.soundIds.get(R.raw.input_enter);
        } else if (i == 32) {
            soundId = this.soundIds.get(R.raw.input_space);
        } else {
            // Handle packed-switch cases
            switch (i) {
                case -21:
                    soundId = 0;
                    break;
                case -20:
                    soundId = this.soundIds.get(R.raw.input_correction);
                    break;
                case -19:
                    soundId = this.soundIds.get(R.raw.input_symswipe);
                    break;
                case -18:
                    soundId = this.soundIds.get(R.raw.input_delswipe);
                    break;
                case -17:
                    soundId = this.soundIds.get(R.raw.input_predswipe);
                    break;
                default:
                    soundId = this.soundIds.get(R.raw.input_keypress);
                    break;
            }
        }
        
        if (soundId != 0) {
            this.soundPool.play(soundId, this.settings.keypressSoundVolume, this.settings.keypressSoundVolume, 1, 0, 1.0f);
        }
    }

    public void performHapticFeedback(View view) {
        if (this.settings.isVibrationEnabled) {
            if (this.settings.keypressVibrationDuration >= 0) {
                vibrate(this.settings.keypressVibrationDuration);
            } else if (view != null) {
                // Audit CT-14: 3 == KEYBOARD_TAP, 2 == FLAG_IGNORE_GLOBAL_SETTING.
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            }
        }
    }

    public void onSettingsChanged(SettingsValues c0804d, Context context) {
        this.settings = c0804d;
        this.soundEnabled = reevaluateIfSoundIsOn();
        if (this.soundEnabled) {
            loadSounds(context);
        } else {
            releaseSounds();
        }
    }

    public void onRingerModeChanged() {
        this.soundEnabled = reevaluateIfSoundIsOn();
    }
}
