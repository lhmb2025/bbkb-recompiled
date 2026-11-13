package dev.bbkb.ime.keyboard.inputboard.clipboard;

import androidx.annotation.Nullable;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.regex.Pattern;



public final class UrlUtils {

    /**
     * Optional https scheme, a dotted host (so a bare filename is never downloaded), optional port,
     * then a path ending in an image extension. The scheme is optional because Patterns.WEB_URL
     * accepts bare hosts; ensureScheme adds https:// later.
     */
    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile(
            "(?i)(?:https://)?[^\\s/:?#@]+\\.[^\\s/:?#@]+(?::\\d+)?/\\S*\\.(?:jpg|png|gif|bmp)");

    /** @param url the candidate URL */
    public static boolean isImageUrl(String url) {
        return IMAGE_URL_PATTERN.matcher(url).matches();
    }

    /**
     * Returns {@code url} unchanged if java.net.URL accepts it, {@code "https://" + url} if that has a
     * host (a bare host), otherwise null so callers skip the fetch.
     *
     * @param url the candidate URL
     */
    @Nullable
    public static String ensureScheme(@Nullable String url) {
        if (url == null) {
            return null;
        }
        if (isValidUrl(url)) {
            return url;
        }
        // Blank, has whitespace, or has a scheme the JVM cannot handle: not a host we can prefix.
        if (url.isEmpty() || url.contains("://") || url.matches(".*\\s.*")) {
            return null;
        }
        String withScheme = "https://" + url;
        try {
            String host = new URL(withScheme).getHost();
            return (host == null || host.isEmpty()) ? null : withScheme;
        } catch (MalformedURLException unused) {
            return null;
        }
    }

    /**
     * The URL link previews may fetch, or null. Only https with a host: the input is untrusted (a
     * page's {@code og:image} could name {@code file:///...}), and {@link #ensureScheme} accepts any
     * scheme java.net.URL handles.
     *
     * @param rawUrl the candidate URL
     */
    @Nullable
    public static String httpsUrlOrNull(@Nullable String rawUrl) {
        String url = ensureScheme(rawUrl);
        if (url == null) {
            return null;
        }
        try {
            URL parsed = new URL(url);
            String host = parsed.getHost();
            return "https".equalsIgnoreCase(parsed.getProtocol()) && host != null && !host.isEmpty()
                    ? url : null;
        } catch (MalformedURLException unused) {
            return null;
        }
    }

    /** @param url the candidate URL */
    private static boolean isValidUrl(String url) {
        try {
            new URL(url);
            return true;
        } catch (MalformedURLException unused) {
            return false;
        }
    }
}
