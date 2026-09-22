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
        // Ensure this view is drawn on top of MainKeyboardView and slideboards
        // in keyboard_frame. Without this, the keyboard can visually cover the
        // voice board on PKB devices when the VKB is enabled.
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
        
        // Apply colors from KeyboardColorManager
        if (KeyboardColorManager.INSTANCE.isInitialized()) {
            boolean modernBoards = KeyboardColorManager.styleSpec().getModernBoards();
            // Modern boards share the keyboard's background surface; legacy keyColor.
            setBackgroundColor(modernBoards
                    ? KeyboardColorManager.INSTANCE.getBackgroundColor()
                    : KeyboardColorManager.INSTANCE.getKeyColor());

            // Tint all buttons with iconColorAlt (lighter secondary color)
            int iconColorAlt = KeyboardColorManager.INSTANCE.getIconColorAlt();
            KeyboardColorManager.INSTANCE.tint(this.mVoiceButton, iconColorAlt); // voice input button
            KeyboardColorManager.INSTANCE.tint(this.mDeleteButton, iconColorAlt); // delete button
            KeyboardColorManager.INSTANCE.tint(this.mSettingsButton, iconColorAlt); // settings button

            if (modernBoards) {
                // Accent mic pill: the mic drawable is a filled circle with the glyph
                // knocked out, so tinting it accent yields an accent circle with a
                // board-colored glyph. The background gets the same tint so the
                // listening-animation circle matches.
                int accent = KeyboardColorManager.INSTANCE.getAccentColor();
                KeyboardColorManager.INSTANCE.tint(this.mVoiceButton, accent);
                this.mVoiceButton.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(accent));
                // Side buttons: 48dp touch targets + rounded state-layer press.
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

        // Shrink the mic from its 100dp asset size so the status/waveform stack fits
        // every keyboard height (the board matches the alphabet keyboard's height,
        // which can be as short as extra-compact).
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

    private String formatRecognitionLocale() {
        try {
            String locale = dev.bbkb.ime.core.locale.SubtypeManager.getInstance()
                    .getCurrentSubtype().getLocale();
            if (locale == null || locale.isEmpty()) {
                return "";
            }
            String[] parts = locale.replace('_', '-').split("-");
            if (parts.length >= 2) {
                return parts[0].toUpperCase(java.util.Locale.ROOT) + " · "
                        + parts[1].toUpperCase(java.util.Locale.ROOT);
            }
            return parts[0].toUpperCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /** Audio level from the recognizer (Material waveform). */
    public void setAudioLevel(float rmsDb) {
        if (this.mWaveform != null) {
            this.mWaveform.setLevel(rmsDb);
        }
    }

    @Override // android.widget.RelativeLayout, android.view.View
    protected void onMeasure(int i, int i2) {
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

    public void setListener(Listener interfaceC1002a) {
        this.mListener = interfaceC1002a;
    }

    public void updateState(VoiceRecognitionManager.Mode aVar, int i) {
        if (aVar == VoiceRecognitionManager.Mode.NONE) {
            deactivateOthers((ImageButton) null);
            fadeOthers(null, false);
            setContentDescriptionMicOnOff(true);
            updateMaterialStatus(false);
        } else if (i == VoiceRecognitionManager.STATE_STARTED
                || i == VoiceRecognitionManager.STATE_STOPPED) {
            // Was `(i & 1) != 0` - the same test for the only three states ever sent
            // (audit IB-16).
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
            this.mStatusText.setText(
                    listening ? R.string.voice_status_listening : R.string.voice_status_idle);
        }
        if (this.mWaveform != null) {
            this.mWaveform.setActive(listening);
        }
        if (this.mLanguageChip != null) {
            // Refresh: the keyboard language may have changed since inflate.
            this.mLanguageChip.setText(formatRecognitionLocale());
        }
    }

    /**
     * Audit IB-12: this had an empty body, so the mic button carried no accessibility
     * state at all across every recognizer transition.
     *
     * @param idle true when the recognizer is not listening
     */
    private void setContentDescriptionMicOnOff(boolean idle) {
        if (this.mVoiceButton != null) {
            this.mVoiceButton.setContentDescription(getContext().getString(
                    idle ? R.string.voice_status_idle : R.string.voice_status_listening));
        }
    }

    /**
     * Audit IB-12: the "active" and "inactive" icon maps both held
     * {@code ic_inputboard_voice_listen}, so the whole active/inactive decision was a
     * constant-valued dead branch and setImageResource was a no-op. Only the
     * background animation distinguishes listening from idle; the foreground glyph is
     * now set unconditionally.
     */
    private void updateButtonIcon(ImageButton imageButton, boolean z) {
        {
            if (imageButton == null) {
                return;
            }
            imageButton.setImageResource(R.drawable.ic_inputboard_voice_listen);
            if (KeyboardColorManager.styleSpec().getModernBoards() && imageButton == this.mVoiceButton) {
                // Modern boards: the pulse is a ring-only drawable so the accent disc's
                // knocked-out mic keeps showing the board surface. The legacy
                // animation base carries its own mic glyph, which filled the knockout
                // and washed the icon out.
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
            // The idle branch used to inflate the animation drawable and immediately
            // overwrite it, and stopped that brand-new instance rather than whatever
            // was actually running. Read the current background first (audit IB-12).
            Drawable current = imageButton.getBackground();
            if (imageButton == this.mVoiceButton && z) {
                imageButton.setBackgroundResource(R.drawable.ic_inputboard_voice_listen_animation);
                Drawable background = imageButton.getBackground();
                // Start animation for both AnimationDrawable and AnimatedVectorDrawable
                if (background instanceof Animatable) {
                    ((Animatable) background).start();
                }
            } else {
                // Stop whatever animation is currently running
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
        // The map is read-only; the double-brace form here was three anonymous HashMap
        // subclasses (audit IB-12).
        this.mModeButtons = java.util.Collections.singletonMap(
                VoiceRecognitionManager.Mode.DICTATION, this.mVoiceButton);
    }
}
