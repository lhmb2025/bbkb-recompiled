package dev.bbkb.ime.keyboard.inputboard.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Patterns
import dev.bbkb.ime.core.shared.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URL

/**
 * Modern coroutine-based Open Graph metadata and image fetcher.
 * Replaces deprecated AsyncTask implementations (FetchOpenGraphTask and DownloadImageTask).
 */
object OpenGraphFetcher {
    
    private const val TAG = "OpenGraphFetcher"
    private const val USER_AGENT = "Mozilla/5.0 AppleWebKit/534.30 (KHTML, like Gecko) Chrome/12.0.742.122 Safari/534.30"
    
    /**
     * Fetches Open Graph metadata from ClipboardItem URL and invokes callback on completion.
     * This is ClipboardItem Java-friendly wrapper around the suspend function.
     * 
     * @param metadata OpenGraphMetadata object with URL set
     * @param callback Callback to invoke when metadata is fetched
     * @param scope CoroutineScope to launch the coroutine in
     */
    @JvmStatic
    fun fetchMetadata(
        metadata: OpenGraphMetadata,
        callback: ClipboardImageLoadCallback?,
        scope: CoroutineScope
    ) {
        scope.launch {
            try {
                fetchMetadataInternal(metadata)
                
                // Invoke callback on main thread
                withContext(Dispatchers.Main) {
                    val imageUrl = metadata.getImageUrl()
                    if (imageUrl != null && Patterns.WEB_URL.matcher(imageUrl).matches()) {
                        callback?.onImageReady()
                    } else {
                        Logger.debug(TAG, "No valid image URL found in Open Graph metadata")
                    }
                }
            } catch (e: Exception) {
                Logger.debug(TAG, "Error fetching Open Graph metadata: ${e.message}")
            }
        }
    }
    
    /**
     * Fetches Open Graph metadata from ClipboardItem URL (suspend function).
     * 
     * @param metadata OpenGraphMetadata object with URL set
     * @return The same metadata object, populated with fetched data
     */
    suspend fun fetchMetadataInternal(metadata: OpenGraphMetadata): OpenGraphMetadata = withContext(Dispatchers.IO) {
        val url = metadata.getUrl() ?: return@withContext metadata
        // null = not an https URL (or not a URL at all); previews are fetched over https only.
        val normalizedUrl = UrlUtils.httpsUrlOrNull(url) ?: return@withContext metadata

        try {
            Logger.debug(TAG, "Fetching Open Graph metadata from: $normalizedUrl")
            
            val document = Jsoup.connect(normalizedUrl)
                .userAgent(USER_AGENT)
                .get()
            
            metadata.parse(document)
            Logger.debug(TAG, "Successfully fetched Open Graph metadata")
        } catch (e: Exception) {
            Logger.debug(TAG, "Unable to fetch Open Graph metadata from: $url - ${e.message}")
        }
        
        metadata
    }
    
    /**
     * Downloads and scales an image from ClipboardItem URL, then invokes callback.
     * This is ClipboardItem Java-friendly wrapper around the suspend function.
     * 
     * @param metadata OpenGraphMetadata with image URL
     * @param targetWidth Target width for scaled bitmap
     * @param targetHeight Target height for scaled bitmap
     * @param callback Callback to invoke when image is downloaded
     * @param scope CoroutineScope to launch the coroutine in
     */
    @JvmStatic
    fun downloadImage(
        metadata: OpenGraphMetadata,
        targetWidth: Int,
        targetHeight: Int,
        callback: ClipboardImageLoadCallback?,
        scope: CoroutineScope
    ) {
        scope.launch {
            try {
                val bitmap = downloadImageInternal(metadata, targetWidth, targetHeight)
                
                // Update metadata and invoke callback on main thread
                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        metadata.setImage(bitmap)
                        Logger.debug(TAG, "Image downloaded and scaled successfully")
                    } else {
                        Logger.debug(TAG, "Image download yielded null")
                    }
                    callback?.onImageReady()
                }
            } catch (e: Exception) {
                Logger.debug(TAG, "Error downloading image: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback?.onImageReady()
                }
            }
        }
    }
    
    /**
     * Downloads and scales an image from ClipboardItem URL (suspend function).
     * 
     * @param metadata OpenGraphMetadata with image URL set
     * @param targetWidth Target width for scaled bitmap
     * @param targetHeight Target height for scaled bitmap
     * @return Scaled bitmap or null if download fails
     */
    suspend fun downloadImageInternal(
        metadata: OpenGraphMetadata,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        val imageUrl = metadata.getImageUrl() ?: return@withContext null
        // null = not an https URL. og:image comes from the fetched page, so a file:/jar:/ftp: value
        // must never reach URL.openStream().
        val normalizedUrl = UrlUtils.httpsUrlOrNull(imageUrl) ?: return@withContext null
        
        try {
            Logger.debug(TAG, "Downloading image from: $normalizedUrl")
            
            val inputStream = URL(normalizedUrl).openStream()
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            
            if (originalBitmap != null) {
                Logger.debug(TAG, "Downloaded image, original size: ${originalBitmap.byteCount} bytes")
                
                // Scale the bitmap
                val scaledBitmap = Bitmap.createScaledBitmap(
                    originalBitmap,
                    targetWidth,
                    targetHeight,
                    true
                )
                
                // Recycle original if it's ClipboardItem different instance
                if (scaledBitmap != originalBitmap) {
                    originalBitmap.recycle()
                }
                
                Logger.debug(TAG, "Scaled image to ${targetWidth}x${targetHeight}")
                scaledBitmap
            } else {
                Logger.debug(TAG, "Failed to decode bitmap from stream")
                null
            }
        } catch (e: Exception) {
            Logger.debug(TAG, "Unable to fetch image from: $imageUrl - ${e.message}")
            null
        }
    }
}
