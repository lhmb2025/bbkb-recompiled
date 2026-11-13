package com.blackberry.nuanceshim.languagepack;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonWriter;

import androidx.core.util.AtomicFile;

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
 * Regional dictionary variants ({@code de_CH}, {@code fr_CH}, {@code it_CH}, {@code nl_BE}) that
 * have no entry in the engine's locale table, so they load in their base language's slot by
 * copying the file into {@code no_backup/nuance/&lt;locale&gt;/} and setting the language again.
 * Inactive members are parked under {@code no_backup/lang_variants/}, a sibling of {@code nuance/}
 * because {@link LanguagePackInstaller#getInstalledLocales} counts every child there as a locale.
 * {@link #TAG_SHIPPED} means "use the APK asset" (the slot directory is deleted). The DLM is per language.
 */
public final class LanguageVariantStore {

    private static final String TAG = "LangVariants";

    private static final String FILE_NAME = "nuance_language_variants.json";

    /** Where parked (inactive) variant files live. A sibling of {@code nuance/}, never a child. */
    private static final String VARIANT_DIR = "lang_variants";

    /** The pack the app shipped with: no file of ours, the engine reads it from assets. */
    public static final String TAG_SHIPPED = "shipped";

    /** Posted after a swap so a running IME re-reads the dictionary; one that is not running needs no signal. */
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

    /** Atomic replace: the parked files are the only copy of a side-loaded variant, so this record must survive a crash. */
    private static boolean write(Context context, Map<String, Group> groups) {
        final File f = file(context);
        final AtomicFile atomic = new AtomicFile(f);
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            final JsonWriter w = new JsonWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
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
            // Flushed, not closed: finishWrite syncs the stream before it closes it.
            w.flush();
            atomic.finishWrite(out);
        } catch (IOException e) {
            atomic.failWrite(out);
            Logger.errorWithException(TAG, e, "Failed to write variant store");
            return false;
        } catch (RuntimeException e) {
            atomic.failWrite(out);
            throw e;
        }
        // finishWrite only logs a failed rename, which leaves the store in the .new file.
        final File pending = new File(f.getPath() + ".new");
        if (pending.exists()) {
            Logger.error(TAG, "Failed to replace variant store");
            //noinspection ResultOfMethodCallIgnored
            pending.delete();
            return false;
        }
        return true;
    }

    /**
     * Parks a copy of {@code sourceLdb} in {@code locale}'s group and makes it active. The first
     * variant also adds the {@link #TAG_SHIPPED} member so there is always a way back.
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
     * Swaps {@code tag}'s parked file into the locale's pack directory (or deletes it for
     * {@link #TAG_SHIPPED}). The caller must set the engine language again afterwards.
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
            // Every non-shipped member is parked under lang_variants/, so nothing is lost.
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
                // getInstalledLocales() needs both a .ldb and a version.txt.
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
     * Deletes a variant's parked file. An active one is replaced by the newest remaining member;
     * removing the last real variant removes the group.
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

    /** Name for the shipped member, localized via {@code Locale.getDisplayName()} (the manifest has no display name). */
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
