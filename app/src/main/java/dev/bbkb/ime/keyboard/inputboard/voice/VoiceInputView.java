package dev.bbkb.ime.keyboard.inputboard.voice;

import android.content.Context;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageButton;
import android.widget.RelativeLayout;

import dev.bbkb.ime.R;
import dev.bbkb.ime.keyboard.KeyboardColorManager;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import dev.bbkb.ime.keyboard.inputboard.BoardHeightPolicy;


public class VoiceInputView extends RelativeLayout {

    private ImageButton mVoiceButton;

    private ImageButton mDeleteButton;

    private ImageButton mSettingsButton;

    // Material-only widgets (stay GONE under Classic/Modern)
    private android.widget.TextView mStatusText;

    private VoiceWaveformView mWaveform;

    private android.widget.TextView mLanguageChip;

    private Listener mListener;

    private Map<VoiceRecognitionManager.Mode, ImageButton> mModeButtons;



    private Handler mHandler;

    private boolean mDebouncing;

    private final KeyboardSwitcher mKeyboardSwitcher;

    private final int mKeyboardHeight;

    private final Context mContext;

    
    public interface Listener {
        void onStartListening();

        void onDelete();

        void onOpenSettings();
    }

    public VoiceInputView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        this.mHandler = new Handler(android.os.Looper.getMainLooper());
        hide();
        this.mKeyboardSwitcher = KeyboardSwitcher.getInstance();
        this.mContext = getContext();
        this.mKeyboardHeight = BoardHeightPolicy.fallbackHeight(this.mContext.getResources());
    }

    public void hide() {
        setVisibility(View.GONE);
    }

    public boolean isShowing() {
        return getVisibility() == View.VISIBLE;
    }

    public void show() {
        setVisibility(View.VISIBLE);
        // Otherwise MainKeyboardView can cover the board on PKB devices with the VKB enabled.
        bringToFront();
    }

    @Override // android.view.View
    protected void onFinishInflate() {
        super.onFinishInflate();
        this.mVoiceButton = (ImageButton) findViewById(R.id.voice_input_btn);
        this.mVoiceButton.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                if (VoiceInputView.this.mDebouncing) {
                    return;
                }
                VoiceInputView.this.mListener.onStartListening();
                VoiceInputView.this.startDebounce();
            }
        });
        this.mDeleteButton = (ImageButton) findViewById(R.id.voice_input_delete_btn);
        this.mDeleteButton.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                VoiceInputView.this.mListener.onDelete();
            }
        });
        this.mSettingsButton = (ImageButton) findViewById(R.id.voice_input_settings_btn);
        this.mSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                VoiceInputView.this.mListener.onOpenSettings();
            }
        });
        initButtonMaps();

        if (KeyboardColorManager.INSTANCE.isInitialized()) {
            boolean modernBoards = KeyboardColorManager.styleSpec().getModernBoards();
            setBackgroundColor(modernBoards
                    ? KeyboardColorManager.INSTANCE.getBackgroundColor()
                    : KeyboardColorManager.INSTANCE.getKeyColor());

            int iconColorAlt = KeyboardColorManager.INSTANCE.getIconColorAlt();
            KeyboardColorManager.INSTANCE.tint(this.mVoiceButton, iconColorAlt);
            KeyboardColorManager.INSTANCE.tint(this.mDeleteButton, iconColorAlt);
            KeyboardColorManager.INSTANCE.tint(this.mSettingsButton, iconColorAlt);

            if (modernBoards) {
                // The mic drawable is a filled circle with the glyph knocked out; the background
                // gets the same accent tint so the listening-animation circle matches.
                int accent = KeyboardColorManager.INSTANCE.getAccentColor();
                KeyboardColorManager.INSTANCE.tint(this.mVoiceButton, accent);
                this.mVoiceButton.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(accent));
                applyMaterialSideButton(this.mSettingsButton);
                applyMaterialSideButton(this.mDeleteButton);
                setupMaterialWidgets();
            }
        }
    }

    private void applyMaterialSideButton(ImageButton button) {
        int minPx = (int) (48 * getResources().getDisplayMetrics().density);
        button.setMinimumWidth(minPx);
        button.setMinimumHeight(minPx);
        button.setForeground(KeyboardColorManager.pressedHighlight());
    }

    /** Material additions: status line, live waveform, recognition-language chip. */
    private void setupMaterialWidgets() {
        this.mStatusText = findViewById(R.id.voice_status_text);
        this.mWaveform = findViewById(R.id.voice_waveform);
        this.mLanguageChip = findViewById(R.id.voice_language_chip);
        int hintColor = KeyboardColorManager.INSTANCE.getHintColor(KeyboardColorManager.ALPHA_FULL);
        float density = getResources().getDisplayMetrics().density;

        // Shrink the 100dp mic so the status/waveform stack fits even an extra-compact keyboard height.
        int micSize = (int) (84 * density);
        android.view.ViewGroup.LayoutParams micParams = this.mVoiceButton.getLayoutParams();
        micParams.width = micSize;
        micParams.height = micSize;
        this.mVoiceButton.setLayoutParams(micParams);
        this.mVoiceButton.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);

        this.mStatusText.setTextColor(hintColor);
        this.mStatusText.setText(R.string.voice_status_idle);
        this.mStatusText.setVisibility(View.VISIBLE);

        this.mWaveform.setVisibility(View.VISIBLE);

        android.graphics.drawable.GradientDrawable chipBackground =
                new android.graphics.drawable.GradientDrawable();
        chipBackground.setColor(KeyboardColorManager.INSTANCE.getKeyColorAlt());
        chipBackground.setCornerRadius(14 * density);
        this.mLanguageChip.setBackground(chipBackground);
        this.mLanguageChip.setTextColor(hintColor);
        this.mLanguageChip.setText(formatRecognitionLocale());
        this.mLanguageChip.setVisibility(View.VISIBLE);
    }

    /** Names the language a dictation would actually be sent with, whichever setting supplies it. */
    private String formatRecognitionLocale() {
        try {
            dev.bbkb.ime.core.settings.util.SettingsValues settings =
                    dev.bbkb.ime.core.settings.util.SettingsManager.getInstance().getSettingsValues();
            android.view.inputmethod.InputMethodSubtype subtype =
                    dev.bbkb.ime.core.locale.SubtypeManager.getInstance().getCurrentSubtype();
            String locale = VoiceLanguageTags.effectiveTag(settings.voiceInputUseInputLanguage,
                    subtype != null ? subtype.getLocale() : null, settings.voiceInputLanguageList);
            if (locale == null || locale.isEmpty()) {
                return "";
            }
            String[] parts = locale.split("-");
            if (parts.length >= 2) {
                return parts[0].toUpperCase(java.util.Locale.ROOT) + " · "
                        + parts[1].toUpperCase(java.util.Locale.ROOT);
            }
            return parts[0].toUpperCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /** The recogniser refused the language: say which, where the "Listening" line normally goes. */
    public void showLanguageError(String languageName) {
        if (this.mStatusText != null) {
            this.mStatusText.setText(getContext().getString(R.string.voice_status_language_unsupported, languageName));
        }
        if (this.mWaveform != null) {
            this.mWaveform.setActive(false);
        }
    }

    /**
     * Why the last session stopped (no recognition service, a network error, nothing heard...),
     * where the "Listening" line normally goes, until the next session starts.
     *
     * @return false when this style has no status line (Classic/Modern), so the caller can say it
     *         some other way
     */
    public boolean showStatusMessage(int messageRes) {
        return showStatusMessage(getContext().getText(messageRes));
    }

    /** {@link #showStatusMessage(int)} for a formatted message. */
    public boolean showStatusMessage(CharSequence message) {
        if (this.mStatusText == null) {
            return false;
        }
        this.mStatusText.setText(message);
        if (this.mWaveform != null) {
            this.mWaveform.setActive(false);
        }
        return true;
    }

    public void setAudioLevel(float rmsDb) {
        if (this.mWaveform != null) {
            this.mWaveform.setLevel(rmsDb);
        }
    }

    /**
     * Status-line side margins: 24dp, or enough to clear the language chip when it shows. Equal
     * on both sides so wrapped lines stay centred on the mic.
     */
    private void keepStatusClearOfLanguageChip() {
        if (this.mStatusText == null || this.mLanguageChip == null) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int side = (int) (24 * density);
        if (this.mLanguageChip.getVisibility() == View.VISIBLE) {
            int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            this.mLanguageChip.measure(unspecified, unspecified);
            MarginLayoutParams chip = (MarginLayoutParams) this.mLanguageChip.getLayoutParams();
            side = Math.max(side, this.mLanguageChip.getMeasuredWidth() + chip.getMarginEnd()
                    + (int) (8 * density));
        }
        MarginLayoutParams status = (MarginLayoutParams) this.mStatusText.getLayoutParams();
        if (status.leftMargin != side || status.rightMargin != side) {
            status.leftMargin = side;
            status.rightMargin = side;
            status.setMarginStart(side);
            status.setMarginEnd(side);
        }
    }

    /**
     * @param i width measure spec (ignored: the board sizes itself)
     * @param i2 height measure spec (ignored)
     */
    @Override // android.widget.RelativeLayout, android.view.View
    protected void onMeasure(int i, int i2) {
        keepStatusClearOfLanguageChip();
        setMeasuredDimension(
                BoardHeightPolicy.measuredWidth(this),
                BoardHeightPolicy.measuredHeight(this, this.mKeyboardSwitcher, this.mKeyboardHeight));
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(getMeasuredWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(getMeasuredHeight(), View.MeasureSpec.EXACTLY));
    }

        void startDebounce() {
        this.mDebouncing = true;
        this.mHandler.postDelayed(new Runnable() {
            @Override // java.lang.Runnable
            public void run() {
                VoiceInputView.this.mDebouncing = false;
            }
        }, 350L);
    }

    /** @param interfaceC1002a the controller receiving button taps, or null to detach */
    public void setListener(Listener interfaceC1002a) {
        this.mListener = interfaceC1002a;
    }

    /**
     * @param aVar the recognizer's mode
     * @param i the recognizer state ({@code VoiceRecognitionManager.STATE_*})
     */
    public void updateState(VoiceRecognitionManager.Mode aVar, int i) {
        updateState(aVar, i, false);
    }

    /**
     * @param aVar the recognizer's mode
     * @param i the recognizer state ({@code VoiceRecognitionManager.STATE_*})
     * @param continuous the session keeps listening through pauses, so the status line says the
     *                   mic is what ends it
     */
    public void updateState(VoiceRecognitionManager.Mode aVar, int i, boolean continuous) {
        this.mContinuous = continuous;
        if (aVar == VoiceRecognitionManager.Mode.NONE) {
            deactivateOthers((ImageButton) null);
            fadeOthers(null, false);
            setContentDescriptionMicOnOff(true);
            updateMaterialStatus(false);
        } else if (i == VoiceRecognitionManager.STATE_STARTED
                || i == VoiceRecognitionManager.STATE_STOPPED) {
            ImageButton imageButton = this.mModeButtons.get(aVar);
            deactivateOthers(imageButton);
            updateButtonIcon(imageButton, true);
            fadeOthers(imageButton, true);
            setContentDescriptionMicOnOff(false);
            updateMaterialStatus(true);
        }
    }

    private void updateMaterialStatus(boolean listening) {
        if (this.mStatusText != null) {
            this.mStatusText.setText(listeningStatusRes(listening));
        }
        if (this.mWaveform != null) {
            this.mWaveform.setActive(listening);
        }
        if (this.mLanguageChip != null) {
            // The keyboard language may have changed since inflate.
            this.mLanguageChip.setText(formatRecognitionLocale());
        }
    }

    /** @param idle true when the recognizer is not listening */
    private void setContentDescriptionMicOnOff(boolean idle) {
        if (this.mVoiceButton != null) {
            this.mVoiceButton.setContentDescription(getContext().getString(listeningStatusRes(!idle)));
        }
    }

    /** Set with each state update. */
    private boolean mContinuous;

    /** The status line (and the mic's description) for a listening or idle board. */
    private int listeningStatusRes(boolean listening) {
        if (!listening) {
            return R.string.voice_status_idle;
        }
        return this.mContinuous ? R.string.voice_status_listening_tap_to_stop : R.string.voice_status_listening;
    }

    /**
     * Only the background animation distinguishes listening from idle; the glyph is constant.
     *
     * @param z true while listening
     */
    private void updateButtonIcon(ImageButton imageButton, boolean z) {
        {
            if (imageButton == null) {
                return;
            }
            imageButton.setImageResource(R.drawable.ic_inputboard_voice_listen);
            if (KeyboardColorManager.styleSpec().getModernBoards() && imageButton == this.mVoiceButton) {
                // Ring-only pulse so the accent disc's knocked-out mic keeps showing the board
                // surface; the legacy animation carries its own glyph and would fill the knockout.
                if (z) {
                    imageButton.setBackgroundResource(R.drawable.ic_inputboard_voice_pulse_ring_animation);
                    Drawable ringBackground = imageButton.getBackground();
                    if (ringBackground instanceof Animatable) {
                        ((Animatable) ringBackground).start();
                    }
                } else {
                    imageButton.setBackground(null);
                }
                return;
            }
            // Read the running background first so the idle branch stops the real animation.
            Drawable current = imageButton.getBackground();
            if (imageButton == this.mVoiceButton && z) {
                imageButton.setBackgroundResource(R.drawable.ic_inputboard_voice_listen_animation);
                Drawable background = imageButton.getBackground();
                if (background instanceof Animatable) {
                    ((Animatable) background).start();
                }
            } else {
                if (current instanceof Animatable) {
                    ((Animatable) current).stop();
                }
                imageButton.setBackgroundResource(R.drawable.ic_inputboard_voice_listen);
            }
        }
    }

    private void deactivateOthers(ImageButton imageButton) {
        for (ImageButton imageButton2 : this.mModeButtons.values()) {
            if (imageButton2 != imageButton) {
                updateButtonIcon(imageButton2, false);
            }
        }
    }

    /**
     * @param imageButton the button to leave unfaded, or null
     * @param z true to fade the others (listening), false to restore them
     */
    private void fadeOthers(ImageButton imageButton, boolean z) {
        Iterator<ImageButton> it = this.mModeButtons.values().iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            ImageButton next = it.next();
            if (next != imageButton && next != null) {
                next.animate().alpha(z ? 0.3f : 1.0f).setDuration(100L);
            }
        }
        this.mDeleteButton.animate().alpha(z ? 0.3f : 1.0f).setDuration(100L);
        this.mDeleteButton.setEnabled(!z);
    }

    private void initButtonMaps() {
        this.mModeButtons = java.util.Collections.singletonMap(
                VoiceRecognitionManager.Mode.DICTATION, this.mVoiceButton);
    }
}
