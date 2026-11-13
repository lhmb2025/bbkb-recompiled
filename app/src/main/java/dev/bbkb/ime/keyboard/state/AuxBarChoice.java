package dev.bbkb.ime.keyboard.state;

/**
 * What the bar above the keys shows: one remembered choice (strip or input menu). The menu shows
 * when the user asks for it (hamburger or the show-menu key) and while a board is open, since the
 * board's controls live on it; otherwise the field's own bar ({@link FieldBar}), which follows the
 * field and settings and is worked out live. Pure logic, tested by {@code AuxBarChoiceTest}.
 */
public final class AuxBarChoice {

    /** The bar a field shows when the user has chosen nothing. */
    public enum FieldBar {
        /** No bar: "Show the suggestion bar" hides it, or the strip cannot be drawn here. */
        NONE,
        /** The suggestion strip. */
        STRIP,
        /** The menu: a field with no strip (passwords, suggestions off, words over the keys). */
        MENU,
    }

    /** What the bar shows, temporary bars (arrow, accent, language chips, autofill) aside. */
    public enum Shows {
        NONE,
        STRIP,
        MENU,
    }

    private boolean menuChosen;

    /** Whether the user raised the menu and has not put it away yet. */
    public boolean isMenuChosen() {
        return menuChosen;
    }

    /** The hamburger, or the show-menu key, raised the menu. */
    public void chooseMenu() {
        menuChosen = true;
    }

    /** The menu was put away (by any means, or a new field), so the bar goes back to the field's own bar. */
    public void chooseFieldBar() {
        menuChosen = false;
    }

    /** {@link #shows(boolean, FieldBar, boolean, boolean)} for this choice. */
    public Shows shows(FieldBar fieldBar, boolean boardOpen, boolean menuEnabled) {
        return shows(menuChosen, fieldBar, boardOpen, menuEnabled);
    }

    /**
     * The menu while a board is open (if the menu is enabled; otherwise the board opens under whatever
     * the bar shows), the menu when the user chose it, otherwise the field's own bar.
     */
    public static Shows shows(boolean menuChosen, FieldBar fieldBar, boolean boardOpen, boolean menuEnabled) {
        if (boardOpen && menuEnabled) {
            return Shows.MENU;
        }
        if (menuChosen) {
            return Shows.MENU;
        }
        switch (fieldBar) {
            case MENU:
                return Shows.MENU;
            case STRIP:
                return Shows.STRIP;
            case NONE:
            default:
                return Shows.NONE;
        }
    }
}
