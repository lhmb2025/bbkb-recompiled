package dev.bbkb.ime.core;




public final class Constants {
    public static boolean isValidCoordinate(int coordinate) {
        return coordinate >= 0;
    }

    public static boolean isLetterCode(int code) {
        return code >= 32;
    }

    public static String printableCode(int code) {
        if (code == -21) {
            return "unspec";
        }
        if (code == -16) {
            return "diacritics";
        }
        if (code == -14) {
            return "alpha";
        }
        if (code == 32) {
            return "space";
        }
        switch (code) {
            case -45:
                return "showKeyboardSettings";
            case -44:
                return "passwordKeeperBack";
            case -43:
                return "hideSymbolKeyboardPkb";
            case -42:
                return "fcc";
            case -41:
                return "languageQuickSwitch";
            case -40:
                return "cangjieQuickSwitch";
            case -39:
                return "cangjieRegularSwitch";
            case -38:
                return "passwordKeeperToggleLock";
            case -37:
                return "passwordKeeperForceBar";
            case -36:
                return "passwordKeeperAdd";
            case -35:
                return "passwordKeeperFill";
            case -34:
                return "control";
            case -33:
                return "arrowDown";
            case -32:
                return "arrowUp";
            case -31:
                return "arrowRight";
            case -30:
                return "arrowLeft";
            case -29:
                return "rapidInput";
            default:
                switch (code) {
                    case -27:
                        return "voiceDictation";
                    case -26:
                        return "inputSettings";
                    case -25:
                        return "clipboard";
                    case -24:
                        return "hideInputMenu";
                    case -23:
                        return "showInputMenu";
                    default:
                        switch (code) {
                            case -12:
                                return "shiftEnter";
                            case -11:
                                return "emoji";
                            case -10:
                                return "languageSwitch";
                            case -9:
                                return "actionPrevious";
                            case -8:
                                return "actionNext";
                            case -7:
                                return "shortcut";
                            case -6:
                                return "settings";
                            case -5:
                                return "delete";
                            case -4:
                                return "text";
                            case -3:
                                return "symbol";
                            case -2:
                                return "capslock";
                            case -1:
                                return "shift";
                            default:
                                switch (code) {
                                    case 9:
                                        return "tab";
                                    case 10:
                                        return "enter";
                                    default:
                                        return code < 32 ? String.format("\\u%02X", Integer.valueOf(code)) : code < 256 ? String.format("%c", Integer.valueOf(code)) : code < 65536 ? String.format("\\u%04X", Integer.valueOf(code)) : String.format("\\U%05X", Integer.valueOf(code));
                                }
                        }
                }
        }
    }
}
