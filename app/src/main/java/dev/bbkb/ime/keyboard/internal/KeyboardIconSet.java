package dev.bbkb.ime.keyboard.internal;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.SparseIntArray;

import dev.bbkb.ime.R;
import dev.bbkb.ime.keyboard.KeyboardColorManager;

import java.util.HashMap;
import dev.bbkb.ime.BuildConfig;



public final class KeyboardIconSet {

    private static final String TAG = "KeyboardIconSet";

    private static final SparseIntArray sStyleableIdToIconId = new SparseIntArray();

    private static final HashMap<String, Integer> sNameToIconId = new HashMap<>();

    // Updated to use proper R.styleable attribute indices instead of hardcoded values
    private static final Object[] NAMES_AND_ATTR_IDS = {"undefined", 0, "shift_key", R.styleable.Keyboard_iconShiftKey, "delete_key", R.styleable.Keyboard_iconDeleteKey, "delete_rtl_key", R.styleable.Keyboard_iconDeleteRTLKey, "settings_key", R.styleable.Keyboard_iconSettingsKey, "space_key", R.styleable.Keyboard_iconSpaceKey, "enter_key", R.styleable.Keyboard_iconEnterKey, "pkb_enter_key", R.styleable.Keyboard_iconPkbEnterKey, "go_key", R.styleable.Keyboard_iconGoKey, "search_key", R.styleable.Keyboard_iconSearchKey, "send_key", R.styleable.Keyboard_iconSendKey, "next_key", R.styleable.Keyboard_iconNextKey, "done_key", R.styleable.Keyboard_iconDoneKey, "previous_key", R.styleable.Keyboard_iconPreviousKey, "tab_key", R.styleable.Keyboard_iconTabKey, "shortcut_key", R.styleable.Keyboard_iconShortcutKey, "space_key_for_number_layout", R.styleable.Keyboard_iconSpaceKeyForNumberLayout, "shift_key_shifted", R.styleable.Keyboard_iconShiftKeyShifted, "shift_key_locked", R.styleable.Keyboard_iconShiftKeyLocked, "shortcut_key_disabled", R.styleable.Keyboard_iconShortcutKeyDisabled, "language_switch_key", R.styleable.Keyboard_iconLanguageSwitchKey, "language_quick_switch_key", R.styleable.Keyboard_iconLanguageQuickSwitchKey, "zwnj_key", R.styleable.Keyboard_iconZwnjKey, "zwj_key", R.styleable.Keyboard_iconZwjKey, "emoji_action_key", R.styleable.Keyboard_iconEmojiActionKey, "emoji_normal_key", R.styleable.Keyboard_iconEmojiNormalKey, "diacritic_up_key", R.styleable.Keyboard_iconDiacriticKeyUp, "diacritic_down_key", R.styleable.Keyboard_iconDiacriticKeyDown, "voice_input_small_key", R.styleable.Keyboard_iconVoiceInputSmallKey, "show_input_menu_key", R.styleable.Keyboard_iconShowInputMenuKey, "show_keyboard_key", R.styleable.Keyboard_iconShowKeyboardKey, "show_keyboard_settings_key", R.styleable.Keyboard_iconShowKeyboardSettingsKey, "show_keyboard_active_key", R.styleable.Keyboard_iconShowKeyboardActiveKey, "emoji_key", R.styleable.Keyboard_iconEmojiKey, "emoji_active_key", R.styleable.Keyboard_iconEmojiActiveKey, "emoji_key_disabled", R.styleable.Keyboard_iconEmojiKeyDisabled, "paste_key", R.styleable.Keyboard_iconPasteKey, "paste_active_key", R.styleable.Keyboard_iconPasteActiveKey, "paste_key_disabled", R.styleable.Keyboard_iconPasteKeyDisabled, "fcc_key", R.styleable.Keyboard_iconFccKey, "fcc_key_disabled", R.styleable.Keyboard_iconFccKeyDisabled, "fcc_active_key", R.styleable.Keyboard_iconFccActiveKey, "slideboard_settings_key", R.styleable.Keyboard_iconSlideboardSettingsKey, "voice_input_key", R.styleable.Keyboard_iconVoiceInputKey, "voice_active_key", R.styleable.Keyboard_iconVoiceActiveKey, "voice_input_key_disabled", R.styleable.Keyboard_iconVoiceInputKeyDisabled, "arrow_left_key", R.styleable.Keyboard_iconArrowLeftKey, "arrow_right_key", R.styleable.Keyboard_iconArrowRightKey, "arrow_up_key", R.styleable.Keyboard_iconArrowUpKey, "arrow_down_key", R.styleable.Keyboard_iconArrowDownKey, "password_keeper_toggle_key", R.styleable.Keyboard_iconPasswordKeeperToggleKey, "password_keeper_toggle_inactive_key", R.styleable.Keyboard_iconPasswordKeeperInactiveKey, "password_keeper_toggle_active_key", R.styleable.Keyboard_iconPasswordKeeperActiveKey, "hide_keyboard_big_key", R.styleable.Keyboard_iconHideKeyboardBigKey, "dedicated_show_ib_key", R.styleable.Keyboard_iconDedicatedShowIbKey, "number_pad_key", R.styleable.Keyboard_iconNumberPadKey, "number_pad_active_key", R.styleable.Keyboard_iconNumberPadActiveKey, "number_pad_key_disabled", R.styleable.Keyboard_iconNumberPadKeyDisabled};

    private static int sIconsCount = NAMES_AND_ATTR_IDS.length / 2;

    private static final String[] sIconNames = new String[sIconsCount];

    private final Drawable[] mIcons;

    private final int[] mIconResourceIds;

    public KeyboardIconSet() {
        int i = sIconsCount;
        this.mIcons = new Drawable[i];
        this.mIconResourceIds = new int[i];
    }

    static {
        int i = 0;
        int i2 = 0;
        while (true) {
            Object[] objArr = NAMES_AND_ATTR_IDS;
            if (i >= objArr.length) {
                break;
            }
            String str = (String) objArr[i];
            Integer num = (Integer) objArr[i + 1];
            if (num.intValue() != 0) {
                sStyleableIdToIconId.put(num.intValue(), i2);
            }
            sNameToIconId.put(str, Integer.valueOf(i2));
            sIconNames[i2] = str;
            i2++;
            i += 2;
        }
    }

    public void loadIcons(TypedArray typedArray) {
        int size = sStyleableIdToIconId.size();
        for (int i = 0; i < size; i++) {
            int iKeyAt = sStyleableIdToIconId.keyAt(i);
            try {
                Drawable drawable = typedArray.getDrawable(iKeyAt);
                // Mutate drawable so we have our own copy for tinting
                // This prevents shared drawable state issues when changing icon colors
                if (drawable != null) {
                    drawable = drawable.mutate();
                }
                setDefaultBounds(drawable);
                Integer numValueOf = Integer.valueOf(sStyleableIdToIconId.get(iKeyAt));
                this.mIcons[numValueOf.intValue()] = drawable;
                this.mIconResourceIds[numValueOf.intValue()] = typedArray.getResourceId(iKeyAt, 0);
            } catch (Resources.NotFoundException unused) {
                // Icon attribute not found - this is normal for optional icons
                Integer iconIndex = Integer.valueOf(sStyleableIdToIconId.get(iKeyAt));
                String iconName = getIconName(iconIndex.intValue());
                if (BuildConfig.DEBUG) Log.w(TAG, "Drawable resource for icon '" + iconName + "' not found");
            }
        }
    }

    private static boolean isValidIconId(int i) {
        return i >= 0 && i < sIconNames.length;
    }

    public static String getIconName(int i) {
        if (isValidIconId(i)) {
            return sIconNames[i];
        }
        return "unknown<" + i + ">";
    }

    public static int getIconId(String str) {
        Integer num = sNameToIconId.get(str);
        if (num != null) {
            return num.intValue();
        }
        throw new RuntimeException("unknown icon name: " + str);
    }

    public int getIconResourceId(String str) {
        int iM7159a = getIconId(str);
        if (isValidIconId(iM7159a)) {
            return this.mIconResourceIds[iM7159a];
        }
        throw new RuntimeException("unknown icon name: " + str);
    }

    public Drawable getIconDrawable(int i) {
        if (isValidIconId(i)) {
            return this.mIcons[i];
        }
        throw new RuntimeException("unknown icon id: " + getIconName(i));
    }

    private static void setDefaultBounds(Drawable drawable) {
        if (drawable != null) {
            drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        }
    }
    
    /**
     * Apply color tint from KeyboardColorManager to all loaded icons.
     * This should be called after icons are loaded and when theme changes.
     */
    public void applyColorTint() {
        if (!KeyboardColorManager.INSTANCE.isInitialized()) {
            return;
        }
        
        // Apply base icon color to all loaded drawables
        for (int i = 0; i < mIcons.length; i++) {
            Drawable drawable = mIcons[i];
            if (drawable != null) {
                // Create ClipboardItem new drawable to avoid shared state issues
                Drawable.ConstantState state = drawable.getConstantState();
                if (state != null) {
                    drawable = state.newDrawable().mutate();
                    mIcons[i] = drawable;
                }
                // Apply default icon color (will be overridden for inactive keys)
                KeyboardColorManager.INSTANCE.tint(drawable);
            }
        }
    }
}
