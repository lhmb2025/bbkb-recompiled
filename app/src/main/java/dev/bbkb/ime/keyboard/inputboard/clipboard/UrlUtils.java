package dev.bbkb.ime.keyboard.inputboard.clipboard;

import androidx.annotation.Nullable;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.regex.Pattern;



public final class UrlUtils {

    /**
     * An image URL: an optional https scheme, a dotted host (optional port), then a path ending
     * in an image extension. The host is required so a bare filename ("holiday.png") or a relative
     * path is not handed to the image downloader. The scheme stays optional because rows reach
     * here via Patterns.WEB_URL, which accepts bare hosts; ensureScheme adds https:// later.
     * Only https counts: the keyboard fetches link previews over https only (see httpsUrlOrNull).
     */
    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile(
            "(?i)(?:https://)?[^\\s/:?#@]+\\.[^\\s/:?#@]+(?::\\d+)?/\\S*\\.(?:jpg|png|gif|bmp)");

    public static boolean isImageUrl(String str) {
        return IMAGE_URL_PATTERN.matcher(str).matches();
    }

    /**
     * Returns {@code str} unchanged if java.net.URL already accepts it, {@code "https://" + str} if
     * that yields a URL with a host (a bare host such as "example.com"), and otherwise {@code null}
     * so callers skip the fetch instead of requesting nonsense like "https://not a url at all".
     */
    @Nullable
    public static String ensureScheme(@Nullable String str) {
        if (str == null) {
            return null;
        }
        if (isValidUrl(str)) {
            return str;
        }
        // Blank, whitespace-bearing, or carrying a scheme the JVM has no handler for ("bogus://x"):
        // not a host we can prefix.
        if (str.isEmpty() || str.contains("://") || str.matches(".*\\s.*")) {
            return null;
        }
        String withScheme = "https://" + str;
        try {
            String host = new URL(withScheme).getHost();
            return (host == null || host.isEmpty()) ? null : withScheme;
        } catch (MalformedURLException unused) {
            return null;
        }
    }

    /**
     * The URL the link-preview stack may fetch, or {@code null} if it must not fetch anything.
     *
     * <p>{@link #ensureScheme} accepts any scheme java.net.URL has a handler for, so a web page whose
     * {@code og:image} said {@code file:///...} would have made the keyboard open a local file with
     * URL.openStream(). Previews are fetched from pasted, untrusted text and from whatever the
     * fetched page names, so only https with a host is allowed: no file:, jar:, ftp:, mailto:, and
     * no cleartext http (which targetSdk 36 blocks at runtime anyway).
     */
    @Nullable
    public static String httpsUrlOrNull(@Nullable String str) {
        String url = ensureScheme(str);
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

    private static boolean isValidUrl(String str) {
        try {
            new URL(str);
            return true;
        } catch (MalformedURLException unused) {
            return false;
        }
    }
}
