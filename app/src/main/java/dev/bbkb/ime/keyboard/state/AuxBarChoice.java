package dev.bbkb.ime.keyboard.state;

/**
 * What the bar above the keys shows: <b>one remembered choice</b>, the suggestion strip or the menu
 * (the unified input menu), and the things that can cover it.
 *
 * <p>Owner decision, 2026-11-05 (plan: bbkb-attic {@code docs/2026-11_aux-bar-choice_plan.md}):
 * the menu comes up when the user asks for it — the hamburger on the strip, or the "Show or hide
 * the input menu" key — and while a board is open, because the board's controls live on it. When
 * neither holds, the bar shows the field's own bar. Before this, nothing remembered the user's
 * choice: whatever moved the bar last won, so closing a board left whatever the board had raised.
 *
 * <p>The choice is a single flag because the other half of it, "the field's own bar", is not the
 * user's to remember: it follows the field and the settings ({@link FieldBar}), and is worked out
 * live by whoever asks.
 *
 * <p>Pure logic, no Android: the whole table is exercised on the JVM by {@code AuxBarChoiceTest}.
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

    /**
     * The menu was put away — the menu's keyboard key, the show-menu key again, the menu taken down
     * for any other reason, a new field — so the bar goes back to the field's own bar.
     */
    public void chooseFieldBar() {
        menuChosen = false;
    }

    /** {@link #shows(boolean, FieldBar, boolean, boolean)} for this choice. */
    public Shows shows(FieldBar fieldBar, boolean boardOpen, boolean menuEnabled) {
        return shows(menuChosen, fieldBar, boardOpen, menuEnabled);
    }

    /**
     * What the bar shows: the menu while a board is open (if the menu is enabled; with it off a
     * board opens under whatever the bar shows), the menu when the user chose it, otherwise the
     * field's own bar.
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
