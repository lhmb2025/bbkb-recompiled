/**
 * Downloadable language packs: catalogue ({@link dev.bbkb.ime.core.languagepack.PackCatalog}),
 * queue ({@link dev.bbkb.ime.core.languagepack.PackDownloadManager}, a process-wide singleton that
 * runs one download at a time and outlives the screen) and the one installer
 * ({@link dev.bbkb.ime.core.languagepack.PackInstallService}, the only writer of
 * {@code no_backup/nuance/}). Packs download only on user request, never in bulk, and install under
 * the manifest's locale, never one parsed from the file name. Cancellation returns a pack to
 * {@code Available}, not {@code Failed}; a failed install leaves nothing behind.
 */
package dev.bbkb.ime.core.languagepack;
