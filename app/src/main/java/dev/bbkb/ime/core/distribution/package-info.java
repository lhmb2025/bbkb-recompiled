/**
 * Shared plumbing for OTA app updates and downloadable language packs: find what is published,
 * fetch a file, verify it. No UI, notifications, installer hand-off or pack installation here.
 *
 * <p>Hosting is GitHub Releases of {@code lhmb2025/bbkb-recompiled}; the catalogue is a static JSON
 * manifest with no signature. Integrity is HTTPS to a host named in the app plus a per-file SHA-256
 * that {@link dev.bbkb.ime.core.distribution.Downloader} verifies, so a redirect may never downgrade
 * to cleartext and a download with no expected hash must never be installed.
 *
 * <h2>Manifest (schema 1)</h2>
 *
 * <p>{@code https://raw.githubusercontent.com/lhmb2025/bbkb-recompiled/main/dist/manifest.json}
 *
 * <pre>{@code
 * {
 *   "schema": 1,
 *   "generated": "2026-09-22T00:00:00Z",
 *   "app": {
 *     "release": {"versionCode": 1430, "versionName": "5.0.0-beta.18",
 *                 "url": "https://github.com/.../bbkb-5.0.0-beta.18.apk",
 *                 "sha256": "<hex>", "size": 12345678, "minSdk": 23, "notes": "..."}
 *   },
 *   "packs": {
 *     "version": "1902.01",
 *     "baseUrl": "https://github.com/.../releases/download/packs-1902.01/",
 *     "items": [
 *       {"locale": "af", "name": "Afrikaans",
 *        "file": "Blackberry_1305_r1-2_AFlsUN_xt9_ALM3.ldb", "sha256": "<hex>", "size": 4400000},
 *       {"locale": "de_CH", "name": "German (Switzerland)",
 *        "file": "Blackberry_1305_r1-20_DEusUNCH_xt9_ALM3.ldb", "sha256": "<hex>",
 *        "size": 3843297, "group": "de"}
 *     ]
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code app} is keyed by build type and only {@code release} is published: on a debug build
 *       the lookup finds nothing, which means "no update channel", never an error. Resolve it via
 *       {@link dev.bbkb.ime.core.distribution.DistributionConfig#channel(android.content.Context)}
 *       so the debug-only {@code pref_distribution_channel_override} applies.</li>
 *   <li>An update is available iff {@code versionCode > BuildConfig.VERSION_CODE}; names are never
 *       compared.</li>
 *   <li>{@code locale} is the app's {@code language[_COUNTRY]} id ({@code es_419}), not BCP-47.
 *       {@code group} appears only on a variant that loads in place of a base language and holds
 *       that base locale. The URL is {@code baseUrl + file}.</li>
 *   <li>Unknown fields are ignored; {@code schema > 1} is rejected with
 *       {@link dev.bbkb.ime.core.distribution.UnsupportedSchemaException}; a malformed channel or
 *       pack item is dropped rather than failing the parse.</li>
 * </ul>
 *
 * <h2>For consumers</h2>
 *
 * <ul>
 *   <li>{@code fetch} and {@code download} suspend and work on {@code Dispatchers.IO};
 *       {@code onProgress} runs there too. {@code cached()}, {@code clearStale} and
 *       {@code Sha256.of(File)} block on disk I/O and must stay off main.</li>
 *   <li>Cancellation propagates {@code CancellationException} and deletes partial files; a
 *       {@code Result.failure} is always a {@link dev.bbkb.ime.core.distribution.DistributionException}
 *       (a sealed {@code IOException}) carrying the values a message needs.</li>
 *   <li>The manifest cache lives in {@code files/distribution/} (durable: the only offline answer
 *       to what packs exist); downloads in {@code cacheDir/downloads/} (reclaimable).</li>
 *   <li>This package needs only {@code INTERNET} and {@code ACCESS_NETWORK_STATE}; install and
 *       notification permissions belong to the features that use them.</li>
 * </ul>
 */
package dev.bbkb.ime.core.distribution;
