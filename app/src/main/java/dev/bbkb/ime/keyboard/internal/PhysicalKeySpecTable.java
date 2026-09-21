package dev.bbkb.ime.keyboard.internal;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.device.profile.DeviceProfile;

import java.util.HashMap;
import java.util.Locale;



public class PhysicalKeySpecTable {

    private final HashMap<String, String[]> moreKeysByLabel = new HashMap<>();

    private HashMap<String, String[]> multitapKeysByLabel;

    private HashMap<String, String[]> characterMapKeysByLabel;

    private Locale locale;

    public PhysicalKeySpecTable(KeyboardParams params) {
        // The Resources / XmlPullParser the decompiled constructor accepted were never used: this
        // class holds the physical-keyboard more-keys and multitap tables, it does not parse XML
        // of its own (that lives in KeyboardXMLParser.parsePhysicalKey).
        this.locale = params.mId.mLocale;
    }

    public void addPhysicalKeySpec(PhysicalKeySpec keySpec) {
        String[] strArr;
        String str = keySpec.label;
        String[] strArr2 = keySpec.moreKeys;
        if (strArr2 != null) {
            this.moreKeysByLabel.put(str, strArr2);
            String upperCase = str.toUpperCase(this.locale);
            if (!upperCase.equals(str)) {
                int length = strArr2.length;
                String[] strArr3 = new String[length];
                int i = 0;
                for (String str2 : strArr2) {
                    strArr3[i] = toUpperCaseWithGreek(str2, true, this.locale, true);
                    if (!strArr3[i].equals(upperCase)) {
                        i++;
                    }
                }
                if (i != length) {
                    strArr = new String[i];
                    System.arraycopy(strArr3, 0, strArr, 0, i);
                } else {
                    strArr = strArr3;
                }
                this.moreKeysByLabel.put(upperCase, strArr);
            }
        }
        String[] strArr4 = keySpec.multitapKeys;
        if (strArr4 != null) {
            if (this.multitapKeysByLabel == null) {
                this.multitapKeysByLabel = new HashMap<>();
            }
            this.multitapKeysByLabel.put(str, strArr4);
            String upperCase2 = str.toUpperCase(this.locale);
            if (!upperCase2.equals(str)) {
                int length2 = strArr4.length;
                String[] strArr5 = new String[length2];
                for (int i2 = 0; i2 < length2; i2++) {
                    strArr5[i2] = strArr4[i2].toUpperCase(this.locale);
                }
                this.multitapKeysByLabel.put(upperCase2, strArr5);
            }
        }
        String[] strArr6 = keySpec.characterMapKeys == null ? keySpec.multitapKeys : keySpec.characterMapKeys;
        if (strArr6 != null) {
            if (this.characterMapKeysByLabel == null) {
                this.characterMapKeysByLabel = new HashMap<>();
            }
            this.characterMapKeysByLabel.put(str, strArr6);
            String upperCase3 = str.toUpperCase(this.locale);
            if (upperCase3.equals(str)) {
                return;
            }
            int length3 = strArr6.length;
            String[] strArr7 = new String[length3];
            for (int i3 = 0; i3 < length3; i3++) {
                strArr7[i3] = strArr6[i3].toUpperCase(this.locale);
            }
            this.characterMapKeysByLabel.put(upperCase3, strArr7);
        }
    }

    public String[] splitKeySpecs(String str) {
        int i;
        String[] strArr = this.moreKeysByLabel.get(str);
        KeyCharacterMap keyCharacterMap = DeviceProfile.getKeyCharacterMap();
        String str2 = SettingsManager.getInstance().getSettingsValues().currencySymbol;
        if (!str2.isEmpty()) {
            int i2 = 0;
            if (Character.getType(str.charAt(0)) == Character.CURRENCY_SYMBOL && strArr != null && strArr.length > 0 && (!strArr[0].equals(String.valueOf((char) keyCharacterMap.get(KeyEvent.KEYCODE_0, 0))) || str.equals(str2))) {
                String[] strArr2 = null;
                int length = strArr.length;
                if (contains(strArr, str2)) {
                    length--;
                }
                if (contains(strArr, str) || str2.equals(str)) {
                    i = 0;
                } else {
                    length++;
                    strArr2 = new String[length];
                    strArr2[0] = str;
                    i = 1;
                }
                if (strArr2 == null) {
                    strArr2 = new String[length];
                }
                while (i2 < strArr.length) {
                    if (!strArr[i2].equals(str2)) {
                        strArr2[i] = strArr[i2];
                    } else {
                        int i3 = i2 + 1;
                        if (i3 < strArr.length) {
                            strArr2[i] = strArr[i3];
                            i2 = i3;
                        }
                    }
                    i2++;
                    i++;
                }
                this.moreKeysByLabel.put(str, strArr2);
                return strArr2;
            }
        }
        return strArr;
    }

    /** Avoids the two Arrays.asList wrappers this used to allocate per long-press lookup. */
    private static boolean contains(String[] haystack, String needle) {
        for (String candidate : haystack) {
            if (candidate == null ? needle == null : candidate.equals(needle)) {
                return true;
            }
        }
        return false;
    }

    public String[] getMultiTapAlternates(String str) {
        return splitKeySpecs(str);
    }

    public String[] getMultiTapSequence(String str) {
        HashMap<String, String[]> map = this.multitapKeysByLabel;
        if (map != null) {
            return map.get(str);
        }
        return null;
    }


    public HashMap<String, String[]> getMultiTapHash() {
        return this.multitapKeysByLabel;
    }
}
