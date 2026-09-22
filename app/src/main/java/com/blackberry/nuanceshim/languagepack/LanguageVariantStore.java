package com.blackberry.nuanceshim.languagepack;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonWriter;

import dev.bbkb.ime.core.shared.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Regional dictionary variants grouped under the engine locale they can actually load as.
 *
 * <p><b>Why this exists.</b> Four packs in the BlackBerry catalogue - {@code de_CH}, {@code fr_CH},
 * {@code it_CH}, {@code nl_BE} - have no entry of their own in the ET9 core's 105-entry locale
 * table. They are perfectly good dictionaries; they simply have no locale to be addressed by. The
 * engine can only load such a pack <em>as</em> its base language, in the slot the shipped German /
 * French / Italian / Dutch pack occupies.
 *
 * <p><b>How the swap works.</b> The engine is handed
 * {@code noBackupFilesDir} once at construction and then finds dictionaries by convention: for
 * locale {@code de} it reads whatever {@code *.ldb} sits in {@code no_backup/nuance/de/}. There is
 * no register/unregister call to make - {@code NuanceSDK.registerLdb} has no callers anywhere in
 * this app. So activating a variant means <b>putting its file in that directory</b> and asking the
 * engine to set the language again.
 *
 * <p>Inactive members are parked in {@code no_backup/lang_variants/&lt;locale&gt;/&lt;tag&gt;.ldb},
 * a SIBLING of {@code nuance/} rather than a child. That matters:
 * {@link LanguagePackInstaller#getInstalledLocales} treats every directory directly under
 * {@code nuance/} holding a {@code .ldb} and a {@code version.txt} as an installed locale, so
 * parking variants inside {@code nuance/} would invent locales that do not exist.
 *
 * <p><b>The {@code shipped} member.</b> A preinstalled pack is read from the APK's assets and may
 * have no directory under {@code nuance/} at all. It is represented by the reserved tag
 * {@link #TAG_SHIPPED}, and activating it DELETES the group's {@code nuance/} directory so the
 * engine falls back to assets. That keeps "go back to the pack the app came with" always available,
 * and is why a group never has to copy a multi-megabyte asset out just to have something to return
 * to.
 *
 * <p>Learned words are unaffected: the DLM ({@code alphadlm.bin}) is keyed by language, not by
 * variant, so switching between German variants keeps your learned German.
 */
public final class LanguageVariantStore {

    private static final String TAG = "LangVariants";

    private static final String FILE_NAME = "nuance_language_variants.json";

    /** Where parked (inactive) variant files live. A sibling of {@code nuance/}, never a child. */
    private static final String VARIANT_DIR = "lang_variants";

    /** The pack the app shipped with: no file of ours, the engine reads it from assets. */
    public static final String TAG_SHIPPED = "shipped";

    /**
     * In-process signal that the file behind some locale's dictionary has changed.
     *
     * <p>Posted by the Language packs screen after a swap, consumed by a running {@code
     * BlackBerryIME}, which re-reads the dictionary. No subscriber means the IME is not running and
     * will read the new file when it next starts, which needs no signal.
     */
    public static final String ACTION_LANGUAGE_PACK_CHANGED = "language_pack_changed";

    /** One selectable dictionary within a group. */
    public static final class Member {
        public final String tag;
        public final String name;
        /** Epoch millis; {@code 0} for {@link #TAG_SHIPPED}, so it is never "most recent". */
        public final long installedAt;

        Member(String tag, String name, long installedAt) {
            this.tag = tag;
            this.name = name;
            this.installedAt = installedAt;
        }

        public boolean isShipped() {
            return TAG_SHIPPED.equals(tag);
        }
    }

    /** All the dictionaries that can occupy one engine locale's slot, and which one does. */
    public static final class Group {
        public final String locale;
        public final String activeTag;
        public final List<Member> members;

        Group(String locale, String activeTag, List<Member> members) {
            this.locale = locale;
            this.activeTag = activeTag;
            this.members = Collections.unmodifiableList(members);
        }
    }

    private LanguageVariantStore() {
    }

    static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    static File variantDir(Context context, String locale) {
        return new File(new File(context.getNoBackupFilesDir(), VARIANT_DIR), locale);
    }

    static File variantFile(Context context, String locale, String tag) {
        return new File(variantDir(context, locale), tag + ".ldb");
    }

    // ── reading ────────────────────────────────────────────────────────────────

    /** Every group, keyed by engine locale. Empty when nothing has been grouped. */
    public static synchronized Map<String, Group> read(Context context) {
        final Map<String, Group> out = new LinkedHashMap<>();
        final File f = file(context);
        if (!f.isFile()) {
            return out;
        }
        try (JsonReader r = new JsonReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            r.beginObject();
            while (r.hasNext()) {
                if (!"groups".equals(r.nextName())) {
                    r.skipValue();
                    continue;
                }
                r.beginArray();
                while (r.hasNext()) {
                    final Group g = readGroup(r);
                    if (g != null) {
                        out.put(g.locale, g);
                    }
                }
                r.endArray();
            }
            r.endObject();
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            // A hand-edited or truncated file must not take the language pack UI down with it.
            Logger.errorWithException(TAG, e, "Unreadable variant store; ignoring it");
            return new LinkedHashMap<>();
        }
        return out;
    }

    private static Group readGroup(JsonReader r) throws IOException {
        String locale = null;
        String active = null;
        final List<Member> members = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            final String key = r.nextName();
            if ("locale".equals(key)) {
                locale = r.nextString();
            } else if ("active".equals(key)) {
                active = r.nextString();
            } else if ("members".equals(key)) {
                r.beginArray();
                while (r.hasNext()) {
                    String tag = null;
                    String name = null;
                    long at = 0L;
                    r.beginObject();
                    while (r.hasNext()) {
                        final String mk = r.nextName();
                        if ("tag".equals(mk)) {
                            tag = r.nextString();
                        } else if ("name".equals(mk)) {
                            name = r.nextString();
                        } else if ("installedAt".equals(mk)) {
                            at = r.nextLong();
                        } else {
                            r.skipValue();
                        }
                    }
                    r.endObject();
                    if (tag != null && name != null) {
                        members.add(new Member(tag, name, at));
                    }
                }
                r.endArray();
            } else {
                r.skipValue();
            }
        }
        r.endObject();
        if (locale == null || members.isEmpty()) {
            return null;
        }
        return new Group(locale, active != null ? active : TAG_SHIPPED, members);
    }

    /** The group for {@code locale}, or {@code null} when that locale has no variants. */
    public static Group groupFor(Context context, String locale) {
        return read(context).get(locale);
    }

    // ── writing ────────────────────────────────────────────────────────────────

    private static boolean write(Context context, Map<String, Group> groups) {
        final File f = file(context);
        final File tmp = new File(f.getParentFile(), FILE_NAME + ".tmp");
        try (JsonWriter w = new JsonWriter(
                new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            w.beginObject();
            w.name("groups").beginArray();
            for (Group g : groups.values()) {
                w.beginObject();
                w.name("locale").value(g.locale);
                w.name("active").value(g.activeTag);
                w.name("members").beginArray();
                for (Member m : g.members) {
                    w.beginObject();
                    w.name("tag").value(m.tag);
                    w.name("name").value(m.name);
                    w.name("installedAt").value(m.installedAt);
                    w.endObject();
                }
                w.endArray();
                w.endObject();
            }
            w.endArray();
            w.endObject();
        } catch (IOException e) {
            Logger.errorWithException(TAG, e, "Failed to write variant store");
            tmp.delete();
            return false;
        }
        // Rename over the live file: a crash mid-write must not lose the record of which parked
        // file is which, because the parked files are the only copy of a side-loaded variant.
        if (!tmp.renameTo(f)) {
            Logger.error(TAG, "Failed to replace variant store");
            tmp.delete();
            return false;
        }
        return true;
    }

    // ── the operations ─────────────────────────────────────────────────────────

    /**
     * Park {@code sourceLdb} as a member of {@code locale}'s group and make it active.
     *
     * <p>The first variant added to a locale also creates the group's {@link #TAG_SHIPPED} member,
     * so there is always a way back to what the app came with.
     *
     * @param sourceLdb the .ldb to adopt; it is COPIED, the caller still owns the original
     * @return {@code true} when the variant is parked, recorded and active
     */
    public static synchronized boolean installVariant(Context context, String locale, String tag,
            String displayName, File sourceLdb) {
        if (TAG_SHIPPED.equals(tag)) {
            Logger.error(TAG, "'" + TAG_SHIPPED + "' is reserved for the pack the app shipped with");
            return false;
        }
        if (!CustomPackRegistryStore.isValidLocaleIdentifier(locale)
                || !CustomPackRegistryStore.isValidLocaleIdentifier(tag)) {
            Logger.error(TAG, "Refusing variant with an unusable locale/tag");
            return false;
        }
        final File parked = variantFile(context, locale, tag);
        if (!parked.getParentFile().isDirectory() && !parked.getParentFile().mkdirs()) {
            Logger.error(TAG, "Cannot create variant directory for " + locale);
            return false;
        }
        try {
            copy(sourceLdb, parked);
        } catch (IOException e) {
            Logger.errorWithException(TAG, e, "Failed to park variant " + tag);
            parked.delete();
            return false;
        }

        final Map<String, Group> groups = read(context);
        final Group existing = groups.get(locale);
        final List<Member> members = existing == null ? new ArrayList<>() : new ArrayList<>(existing.members);
        if (existing == null) {
            // Seed the group with the way back before anything displaces it.
            members.add(new Member(TAG_SHIPPED, shippedNameFor(locale), 0L));
        }
        for (int i = members.size() - 1; i >= 0; i--) {
            if (Objects.equals(members.get(i).tag, tag)) {
                members.remove(i);
            }
        }
        members.add(new Member(tag, displayName, System.currentTimeMillis()));
        groups.put(locale, new Group(locale, tag, members));
        if (!write(context, groups)) {
            parked.delete();
            return false;
        }
        return activate(context, locale, tag);
    }

    /**
     * Make {@code tag} the dictionary the engine loads for {@code locale}.
     *
     * <p>This is the file swap: the chosen member's parked .ldb is copied into
     * {@code no_backup/nuance/&lt;locale&gt;/}, or, for {@link #TAG_SHIPPED}, that directory is
     * removed so the engine falls back to the APK's assets. The caller is responsible for asking
     * the engine to set the language again afterwards - this class does no engine work.
     */
    public static synchronized boolean activate(Context context, String locale, String tag) {
        final Map<String, Group> groups = read(context);
        final Group g = groups.get(locale);
        if (g == null) {
            Logger.error(TAG, "No variant group for " + locale);
            return false;
        }
        Member chosen = null;
        for (Member m : g.members) {
            if (Objects.equals(m.tag, tag)) {
                chosen = m;
                break;
            }
        }
        if (chosen == null) {
            Logger.error(TAG, "No member '" + tag + "' in group " + locale);
            return false;
        }

        final File slot = LanguagePackInstaller.getLanguagePackDirectory(context, locale);
        if (chosen.isShipped()) {
            // Remove OUR directory so the engine reads the preinstalled pack from assets again.
            // Nothing is lost: every non-shipped member is parked under lang_variants/.
            LanguagePackInstaller.deleteRecursively(slot);
        } else {
            final File parked = variantFile(context, locale, tag);
            if (!parked.isFile()) {
                Logger.error(TAG, "Parked file missing for " + locale + "/" + tag);
                return false;
            }
            if (!slot.isDirectory() && !slot.mkdirs()) {
                Logger.error(TAG, "Cannot create pack directory for " + locale);
                return false;
            }
            try {
                copy(parked, new File(slot, locale + ".ldb"));
                // getInstalledLocales() requires BOTH a .ldb and a version.txt before it counts
                // this directory as an installed pack.
                writeText(new File(slot, "version.txt"), "1.0");
            } catch (IOException e) {
                Logger.errorWithException(TAG, e, "Failed to activate " + tag);
                return false;
            }
        }
        groups.put(locale, new Group(locale, tag, g.members));
        return write(context, groups);
    }

    /**
     * Forget a variant, deleting its parked file.
     *
     * <p>If it was active, the most recently installed remaining member takes over - falling back
     * to {@link #TAG_SHIPPED}, which is always present. Removing the last real variant removes the
     * group entirely.
     */
    public static synchronized boolean removeVariant(Context context, String locale, String tag) {
        if (TAG_SHIPPED.equals(tag)) {
            Logger.error(TAG, "The shipped member cannot be removed");
            return false;
        }
        final Map<String, Group> groups = read(context);
        final Group g = groups.get(locale);
        if (g == null) {
            return true;
        }
        final List<Member> members = new ArrayList<>(g.members);
        boolean removed = false;
        for (int i = members.size() - 1; i >= 0; i--) {
            if (Objects.equals(members.get(i).tag, tag)) {
                members.remove(i);
                removed = true;
            }
        }
        if (!removed) {
            return true;
        }
        variantFile(context, locale, tag).delete();

        boolean stillHasVariant = false;
        for (Member m : members) {
            if (!m.isShipped()) {
                stillHasVariant = true;
                break;
            }
        }
        if (!stillHasVariant) {
            // Back to just the shipped pack: drop the group and restore the assets fallback.
            groups.put(locale, new Group(locale, TAG_SHIPPED, members));
            write(context, groups);
            activate(context, locale, TAG_SHIPPED);
            final Map<String, Group> after = read(context);
            after.remove(locale);
            LanguagePackInstaller.deleteRecursively(variantDir(context, locale));
            return write(context, after);
        }
        String nextActive = g.activeTag;
        if (Objects.equals(g.activeTag, tag)) {
            nextActive = mostRecent(members).tag;
        }
        groups.put(locale, new Group(locale, nextActive, members));
        if (!write(context, groups)) {
            return false;
        }
        return activate(context, locale, nextActive);
    }

    /** The member a fresh install would pick: newest by install time, shipped only as a fallback. */
    static Member mostRecent(List<Member> members) {
        final List<Member> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparingLong((Member m) -> m.installedAt).reversed());
        return sorted.get(0);
    }

    /**
     * A readable name for the "go back to what shipped" entry.
     *
     * <p>Derived from the locale rather than from the manifest: {@code LanguagePackInstaller}
     * exposes no display name, and {@code Locale.getDisplayName()} already answers this in the
     * user's own language ("German", "Allemand"), which is what the row needs to read.
     */
    static String shippedNameFor(String locale) {
        final java.util.Locale l =
                dev.bbkb.ime.core.locale.LocaleUtils.constructLocaleFromString(locale);
        if (l == null) {
            return locale;
        }
        final String name = l.getDisplayName();
        return (name == null || name.isEmpty()) ? locale : name;
    }

    private static void copy(File from, File to) throws IOException {
        try (FileInputStream in = new FileInputStream(from);
                FileOutputStream out = new FileOutputStream(to)) {
            final byte[] buf = new byte[64 * 1024];
            for (int n = in.read(buf); n != -1; n = in.read(buf)) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    private static void writeText(File to, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(to)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }
}
