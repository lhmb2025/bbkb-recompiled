package dev.bbkb.ime.keyboard.inputboard.clipboard;

import android.content.ClipData;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.shared.Logger;

import java.util.HashMap;
import java.util.Map;




public class ClipboardWebImageProvider implements ClipboardHistoryManager.OnClipEvictedListener {

    private final Map<String, OpenGraphMetadata> mCache = new HashMap();

    private final int mImageWidth;

    private final int mImageHeight;

    /**
     * Audit IB-6: both fetch jobs used to be launched into GlobalScope, which has no
     * cancellation handle and outlives the input view - the IME kept sockets and a
     * decoded Bitmap alive past onFinishInputView. This scope is cancelled from
     * {@link #release()}. OpenGraphFetcher's bodies switch to Dispatchers.IO for the
     * blocking work themselves, so a main-immediate scope only dispatches the
     * continuation.
     */
    private final kotlinx.coroutines.CompletableJob mJob =
            kotlinx.coroutines.SupervisorKt.SupervisorJob(null);

    private final kotlinx.coroutines.CoroutineScope mScope =
            kotlinx.coroutines.CoroutineScopeKt.CoroutineScope(
                    mJob.plus(kotlinx.coroutines.Dispatchers.getMain().getImmediate()));


    public ClipboardWebImageProvider(Context context) {
        Resources resources = context.getResources();
        this.mImageWidth = (int) resources.getDimension(R.dimen.config_clipboard_view_clip_data_image_width);
        this.mImageHeight = (int) resources.getDimension(R.dimen.config_clipboard_view_clip_data_image_height);
    }

    public OpenGraphMetadata loadPreview(OpenGraphMetadata c1014l, ClipboardImageLoadCallback interfaceC1003a) {
        ClipboardImageLoadCallback interfaceC1003aM6979b = createDownloadCallback(c1014l, interfaceC1003a);
        if (UrlUtils.isImageUrl(c1014l.getUrl())) {
            c1014l.setImageUrl(c1014l.getUrl());
            interfaceC1003aM6979b.onImageReady();
        } else {
            // Use modern coroutine-based fetcher instead of deprecated AsyncTask
            OpenGraphFetcher.fetchMetadata(c1014l, interfaceC1003aM6979b, mScope);
        }
        return c1014l;
    }

    private ClipboardImageLoadCallback createDownloadCallback(final OpenGraphMetadata c1014l, final ClipboardImageLoadCallback interfaceC1003a) {
        return new ClipboardImageLoadCallback() {
            @Override // dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardImageLoadCallback
            public void onImageReady() {
                Logger.debug("ClipboardWebProvider", "Downloading image");
                // Use modern coroutine-based image downloader instead of deprecated AsyncTask
                OpenGraphFetcher.downloadImage(
                    c1014l, 
                    ClipboardWebImageProvider.this.mImageWidth, 
                    ClipboardWebImageProvider.this.mImageHeight, 
                    interfaceC1003a, 
                    mScope
                );
                ClipboardWebImageProvider.this.mCache.put(c1014l.getUrl(), c1014l);
            }
        };
    }

    public OpenGraphMetadata getCachedMetadata(String str) {
        return this.mCache.get(str);
    }

    public void evictFromCache(String str) {
        String strTrim;
        OpenGraphMetadata c1014l;
        Bitmap bitmapM7076d;
        if (str == null || (c1014l = this.mCache.get((strTrim = str.trim()))) == null || (bitmapM7076d = c1014l.getImage()) == null) {
            return;
        }
        this.mCache.remove(strTrim);
        bitmapM7076d.recycle();
        Logger.debug("ClipboardWebProvider", "Removed an image successfully from cache");
    }

    /**
     * Cancels any in-flight preview fetches and releases the cached bitmaps. Called
     * from {@link ClipboardController#destroy()} via {@link ClipboardView#release()}.
     */
    public void release() {
        this.mJob.cancel(new java.util.concurrent.CancellationException("clipboard released"));
        for (OpenGraphMetadata metadata : this.mCache.values()) {
            Bitmap bitmap = metadata.getImage();
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        this.mCache.clear();
    }

    @Override
    public void onClipEvicted(ClipData clipData) {
        evictFromCache(ClipboardItem.getTextFromClipData(clipData));
    }
}
