package dev.bbkb.ime.keyboard.internal;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.SparseIntArray;

import dev.bbkb.ime.R;
import dev.bbkb.ime.keyboard.KeyboardColorManager;

import androidx.annotation.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * Prototype icons for the themes seen most recently, newest first. Every Keyboard (and every
     * long-press popup) used to inflate and mutate all ~52 icons for itself: 164 zip inflations and
     * about 17 MB of native churn per 20 long-presses, and a private copy of every icon per cached
     * Keyboard. Now each theme's icons are inflated once, on first use, and a keyboard copies only
     * the icons it actually draws from those prototypes (no XML, no zip). Bounded: a theme change
     * leaves the old prototypes to the keyboards still holding them and to the garbage collector.
     *
     * <p>Why each keyboard still gets its own instances: a VectorDrawable's tint, alpha and raster
     * size are per instance, and up to Android 9 HWUI reads them when it renders, not when a view
     * records. One instance shared by the keyboard, the aux bar and the popups would then paint
     * with whichever tint or size the last view set (the aux bar dims inactive icons; the main
     * keyboard draws the mic and the globe as 16dp hints). Per-keyboard copies of the few icons
     * drawn keep today's behaviour exactly.
     */
    private static final ArrayList<SharedIcons> sSharedIcons = new ArrayList<>();

    private static final int MAX_SHARED_ICON_SETS = 4;

    /** This set's icons, materialised on first request when attached to a theme's prototypes. */
    private final Drawable[] mIcons;

    private final boolean[] mResolved;

    private int[] mIconResourceIds;

    /** The theme's prototypes, or null for a set that was never loaded (tests, fallbacks). */
    private SharedIcons mShared;

    /** A popup's set: every call goes to its template's set (see {@link #shareFrom}). */
    private KeyboardIconSet mDelegate;

    /** The icon color {@link #applyColorTint} last applied; valid while {@link #mTinted}. */
    private int mTintedIconColor;

    private boolean mTinted;

    public KeyboardIconSet() {
        int i = sIconsCount;
        this.mIcons = new Drawable[i];
        this.mResolved = new boolean[i];
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

    /**
     * Attaches this set to the theme's prototypes, creating them on first use. Only the resource
     * ids are read here; an icon is inflated (once per theme) and copied (once per keyboard) the
     * first time a key asks for it.
     */
    public void loadIcons(TypedArray typedArray, Resources.Theme theme) {
        Resources resources = typedArray.getResources();
        int[] resourceIds = new int[sIconsCount];
        Drawable[] literalIcons = null;
        int size = sStyleableIdToIconId.size();
        for (int i = 0; i < size; i++) {
            int attr = sStyleableIdToIconId.keyAt(i);
            int iconId = sStyleableIdToIconId.valueAt(i);
            int resourceId = typedArray.getResourceId(attr, 0);
            resourceIds[iconId] = resourceId;
            if (resourceId == 0 && typedArray.hasValue(attr)) {
                // Not a resource reference (an inline value): it cannot be inflated later from
                // an id, so take it now, as this set's own copy.
                if (literalIcons == null) {
                    literalIcons = new Drawable[sIconsCount];
                }
                try {
                    literalIcons[iconId] = typedArray.getDrawable(attr);
                } catch (Resources.NotFoundException unused) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Drawable for icon '" + getIconName(iconId) + "' not found");
                }
            }
        }
        this.mShared = obtainShared(resources, theme, resourceIds);
        this.mIconResourceIds = this.mShared.resourceIds;
        this.mTinted = false;
        if (literalIcons != null) {
            for (int i = 0; i < sIconsCount; i++) {
                if (literalIcons[i] != null) {
                    this.mIcons[i] = prepare(literalIcons[i].mutate());
                    this.mResolved[i] = true;
                }
            }
        }
    }

    /**
     * Makes this set a view of {@code other}: a long-press popup draws from the more-keys
     * template's set, so popups of one theme share one set of icons. Popups are drawn one at a
     * time, by one view, which is what makes sharing instances safe here.
     */
    public void shareFrom(KeyboardIconSet other) {
        KeyboardIconSet target = other;
        while (target.mDelegate != null) {
            target = target.mDelegate;
        }
        this.mDelegate = target;
        this.mIconResourceIds = target.mIconResourceIds;
    }

    private static Drawable prepare(Drawable drawable) {
        setDefaultBounds(drawable);
        return drawable;
    }

    private static SharedIcons obtainShared(Resources resources, Resources.Theme theme, int[] resourceIds) {
        synchronized (sSharedIcons) {
            for (int i = 0; i < sSharedIcons.size(); i++) {
                SharedIcons candidate = sSharedIcons.get(i);
                if (candidate.matches(resources, theme, resourceIds)) {
                    if (i != 0) {
                        sSharedIcons.remove(i);
                        sSharedIcons.add(0, candidate);
                    }
                    return candidate;
                }
            }
            SharedIcons created = new SharedIcons(resources, theme, resourceIds);
            sSharedIcons.add(0, created);
            while (sSharedIcons.size() > MAX_SHARED_ICON_SETS) {
                sSharedIcons.remove(sSharedIcons.size() - 1);
            }
            return created;
        }
    }

    @VisibleForTesting
    static void clearSharedIconsForTest() {
        synchronized (sSharedIcons) {
            sSharedIcons.clear();
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
        if (this.mDelegate != null) {
            return this.mDelegate.getIconResourceId(str);
        }
        int iM7159a = getIconId(str);
        if (isValidIconId(iM7159a)) {
            return this.mIconResourceIds[iM7159a];
        }
        throw new RuntimeException("unknown icon name: " + str);
    }

    public Drawable getIconDrawable(int i) {
        if (isValidIconId(i)) {
            if (this.mDelegate != null) {
                return this.mDelegate.getIconDrawable(i);
            }
            if (this.mShared != null && !this.mResolved[i]) {
                materialize(i);
            }
            return this.mIcons[i];
        }
        throw new RuntimeException("unknown icon id: " + getIconName(i));
    }

    /** This set's own copy of icon {@code i}, made from the theme's prototype. */
    private synchronized void materialize(int i) {
        if (this.mResolved[i]) {
            return;
        }
        Drawable prototype = this.mShared.prototype(i);
        if (prototype != null) {
            Drawable.ConstantState state = prototype.getConstantState();
            Drawable copy = state != null
                    ? state.newDrawable(this.mShared.resources)
                    : this.mShared.inflate(i);
            if (copy != null) {
                // Our own copy for tinting, so a tint never reaches another keyboard's icon.
                copy = prepare(copy.mutate());
                if (this.mTinted) {
                    KeyboardColorManager.INSTANCE.tint(copy, this.mTintedIconColor);
                }
                this.mIcons[i] = copy;
            }
        }
        this.mResolved[i] = true;
    }

    private static void setDefaultBounds(Drawable drawable) {
        if (drawable != null) {
            drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        }
    }
    
    /**
     * Apply color tint from KeyboardColorManager to all loaded icons.
     * This should be called after icons are loaded and when theme changes.
     *
     * <p>Tints in place and only when the icon color changed since the last call. The icons are
     * already mutated copies (the theme's shared set mutates each one as it inflates it, and the
     * tint state lives with that set, so the first keyboard of a palette tints for all), so a fresh
     * {@code newDrawable().mutate()} per call bought nothing: it deep-copied every vector tree on
     * each keyboard swap (every shift change), and swapped the instances out from under anything
     * that had captured them (MainKeyboardView's shift/alt overlay icons). The key draw path
     * re-tints each icon before drawing it (KeyboardView.applyIconTint), so this is only the
     * baseline color for consumers that draw an icon without tinting it (key previews).
     */
    public void applyColorTint() {
        if (this.mDelegate != null) {
            this.mDelegate.applyColorTint();
            return;
        }
        if (!KeyboardColorManager.INSTANCE.isInitialized()) {
            return;
        }
        int color = KeyboardColorManager.INSTANCE.getPalette().getIcon();
        if (mTinted && color == mTintedIconColor) {
            return;
        }
        // Icons not materialised yet take this tint when they are.
        for (Drawable drawable : mIcons) {
            if (drawable != null) {
                KeyboardColorManager.INSTANCE.tint(drawable, color);
            }
        }
        mTintedIconColor = color;
        mTinted = true;
    }

    /**
     * One theme's prototype icons: inflated on first request and never drawn, tinted or mutated,
     * so their constant state is a clean template for every keyboard's copies.
     */
    private static final class SharedIcons {

        final Resources resources;

        final WeakReference<Resources.Theme> theme;

        final int densityDpi;

        final int uiMode;

        final int[] resourceIds;

        private final Drawable[] prototypes = new Drawable[sIconsCount];

        private final boolean[] resolved = new boolean[sIconsCount];

        SharedIcons(Resources resources, Resources.Theme theme, int[] resourceIds) {
            this.resources = resources;
            this.theme = new WeakReference<>(theme);
            this.densityDpi = resources.getConfiguration().densityDpi;
            this.uiMode = resources.getConfiguration().uiMode;
            this.resourceIds = resourceIds;
        }

        boolean matches(Resources resources, Resources.Theme theme, int[] resourceIds) {
            // Keyed on the Theme instance too: the icons resolve ?attr colours against it.
            return this.resources == resources && this.theme.get() == theme
                    && this.densityDpi == resources.getConfiguration().densityDpi
                    && this.uiMode == resources.getConfiguration().uiMode
                    && Arrays.equals(this.resourceIds, resourceIds);
        }

        synchronized Drawable prototype(int iconId) {
            if (!resolved[iconId]) {
                resolved[iconId] = true;
                prototypes[iconId] = inflate(iconId);
            }
            return prototypes[iconId];
        }

        Drawable inflate(int iconId) {
            int resourceId = resourceIds[iconId];
            if (resourceId == 0) {
                return null;
            }
            try {
                return resources.getDrawable(resourceId, theme.get());
            } catch (Resources.NotFoundException unused) {
                // Icon attribute not found - this is normal for optional icons
                if (BuildConfig.DEBUG) Log.w(TAG, "Drawable resource for icon '" + getIconName(iconId) + "' not found");
                return null;
            }
        }
    }
}
