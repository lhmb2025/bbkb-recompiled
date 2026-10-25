package dev.bbkb.ime.core.ime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;

/**
 * The application context, answering {@link #getResources()} and {@link #getAssets()} with the
 * IME service's instead.
 *
 * <p>The keyboard, slide board, input-menu bar and arrow bar are built on a themed wrapper of
 * the application context. On a rotation the service's configuration is updated before it
 * rebuilds its input view, but the application's can still be the old one at that moment, so
 * everything built then came out at the old orientation's width: the keyboard stayed narrow
 * after rotating to landscape until the next restart (the original keyboard has the same bug).
 * Reading resources through the service keeps sizes in step with the window the views are drawn
 * in. Everything else (theme base, system services, files) is still the application context's,
 * so styling is unchanged.
 */
public final class ImeResourcesContext extends ContextWrapper {

    private final Context ime;

    public ImeResourcesContext(Context ime) {
        super(ime.getApplicationContext());
        this.ime = ime;
    }

    @Override
    public Resources getResources() {
        return ime.getResources();
    }

    @Override
    public AssetManager getAssets() {
        return ime.getAssets();
    }
}
