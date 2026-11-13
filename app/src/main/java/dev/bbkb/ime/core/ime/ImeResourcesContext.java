package dev.bbkb.ime.core.ime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;

/**
 * The application context with the IME service's {@link #getResources()} and {@link #getAssets()}.
 * On rotation the application's configuration can lag the service's while the input view is
 * rebuilt, so views sized from it kept the old orientation's width. Everything else stays the
 * application context's.
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
