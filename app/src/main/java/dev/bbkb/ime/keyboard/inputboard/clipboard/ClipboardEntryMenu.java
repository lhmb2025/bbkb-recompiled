package dev.bbkb.ime.keyboard.inputboard.clipboard;

import android.content.Context;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;

import androidx.annotation.StringRes;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.widget.PopupMenu;

import dev.bbkb.ime.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The menu a clipboard row opens, from a long-press on the row or a tap on its overflow button —
 * the same menu, anchored to the overflow button either way.
 *
 * <p>Built as an AppCompat {@link PopupMenu} on the IME's Material 3 theme. The input view's own
 * theme has no Material parent, so the popup must be given
 * {@code Theme_BlackberryKeyboard_IME} explicitly or it inflates unstyled (or not at all).
 */
final class ClipboardEntryMenu {

    static final int ACTION_PIN = 1;

    static final int ACTION_UNPIN = 2;

    static final int ACTION_COPY = 3;

    static final int ACTION_SHARE = 4;

    static final int ACTION_SHOW_FULL_TEXT = 5;

    static final int ACTION_DELETE = 6;

    interface Handler {
        void onAction(int action);
    }

    private ClipboardEntryMenu() {
    }

    /**
     * The actions a row offers, in menu order. A masked (Password Keeper) row offers only Copy and
     * Delete: pinning would keep a password past the purge, and Share or Show full text would
     * reveal it. Show full text appears only while the row's text is cut off.
     */
    static List<Integer> actionsFor(ClipEntry entry, boolean masked, boolean truncated) {
        List<Integer> actions = new ArrayList<>(5);
        if (!masked) {
            actions.add(entry.isPinned() ? ACTION_UNPIN : ACTION_PIN);
        }
        actions.add(ACTION_COPY);
        if (!masked) {
            actions.add(ACTION_SHARE);
            if (truncated) {
                actions.add(ACTION_SHOW_FULL_TEXT);
            }
        }
        actions.add(ACTION_DELETE);
        return actions;
    }

    /** The popup for {@code actions}, anchored to {@code anchor}; the caller shows it. */
    static PopupMenu build(View anchor, List<Integer> actions, Handler handler) {
        Context themed = new ContextThemeWrapper(anchor.getContext(), R.style.Theme_BlackberryKeyboard_IME);
        PopupMenu popup = new PopupMenu(themed, anchor, Gravity.END);
        Menu menu = popup.getMenu();
        for (int i = 0; i < actions.size(); i++) {
            int action = actions.get(i);
            menu.add(Menu.NONE, action, i, titleOf(action));
        }
        popup.setOnMenuItemClickListener(item -> {
            handler.onAction(item.getItemId());
            return true;
        });
        return popup;
    }

    @StringRes
    static int titleOf(int action) {
        switch (action) {
            case ACTION_PIN:
                return R.string.clipboard_menu_pin;
            case ACTION_UNPIN:
                return R.string.clipboard_menu_unpin;
            case ACTION_COPY:
                return R.string.clipboard_menu_copy;
            case ACTION_SHARE:
                return R.string.clipboard_menu_share;
            case ACTION_SHOW_FULL_TEXT:
                return R.string.clipboard_menu_show_full_text;
            case ACTION_DELETE:
                return R.string.clipboard_menu_delete;
            default:
                throw new IllegalArgumentException("unknown clipboard action " + action);
        }
    }
}
