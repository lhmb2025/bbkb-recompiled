/**
 * OTA app updates and downloadable language packs: the shared plumbing under both.
 *
 * <p>This package is the <em>foundation only</em>. It knows how to find out what is published,
 * how to fetch a file, and how to prove the file is the right one. It deliberately contains no
 * UI, no notifications, no installer hand-off and no language-pack installation: the update UI
 * and the pack catalogue are built on top of it and own all of that.
 *
 * <h2>The decisions this layer encodes</h2>
 *
 * <ul>
 *   <li>Hosting is <b>GitHub Releases</b> of the public repo {@code lhmb2025/bbkb-recompiled}.</li>
 *   <li>The catalogue is a <b>static JSON manifest</b> fetched over HTTPS. There is
 *       <b>no manifest signature</b>. Integrity is exactly two things: HTTPS to a host named in
 *       the app, and a SHA-256 per file that the manifest states and {@link
 *       dev.bbkb.ime.core.distribution.Downloader} verifies before reporting success. Both legs
 *       matter — that is why a redirect may never downgrade HTTPS to cleartext, and why a
 *       download with no expected hash must never be installed.</li>
 *   <li>App updates are checked <b>on demand and once a day in the background</b>,
 *       <b>notify only</b>. Downloading and installing are user-initiated, always.</li>
 *   <li>There are roughly <b>100 Nuance LDB language packs</b>, each downloadable individually.</li>
 * </ul>
 *
 * <h2>The manifest (schema 1)</h2>
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
 * <h3>The channel rule</h3>
 *
 * <p>{@code app} is keyed by Gradle build type and the published manifest carries
 * <b>{@code release} only — there is no debug channel</b>. The client reads
 * {@code app[BuildConfig.BUILD_TYPE]}, so:
 *
 * <ul>
 *   <li>on a release build the lookup finds {@code release};</li>
 *   <li>on a debug build the lookup finds <b>nothing</b>, and that means exactly "no update
 *       channel for this build". It is an ordinary, expected state and must be shown as such,
 *       never as an error.</li>
 * </ul>
 *
 * <p>Always resolve the channel through {@link
 * dev.bbkb.ime.core.distribution.DistributionConfig#channel(android.content.Context)} rather
 * than reading {@code BuildConfig.BUILD_TYPE} directly, so the debug-only
 * {@code pref_distribution_channel_override} is honoured:
 *
 * <pre>{@code
 * val manifest = ManifestSource(context).fetch(force = false).getOrElse { return }
 * val update = manifest.appFor(DistributionConfig.channel(context))
 *     ?.takeIf { it.isNewerThan(BuildConfig.VERSION_CODE) }
 *     ?.takeIf { it.isInstallableOn(Build.VERSION.SDK_INT) }
 * }</pre>
 *
 * <p>An update is available <b>iff {@code versionCode > BuildConfig.VERSION_CODE}</b> — strictly
 * greater. Nothing compares version <em>names</em>.
 *
 * <h3>Packs</h3>
 *
 * <p>{@code packs.items[].locale} is the identifier the app's own language-pack code uses:
 * {@code language} or {@code language_COUNTRY} with an underscore ({@code en_US}, {@code es_419},
 * {@code jv}) — not a BCP-47 tag. {@code group} is present only on a variant that loads
 * <em>in place of</em> a base language (the Swiss and Belgian packs) and holds the base
 * language's locale. A pack's download URL is {@code baseUrl + file}, which
 * {@link dev.bbkb.ime.core.distribution.PackEntry#url} builds for you.
 *
 * <h3>Forward compatibility</h3>
 *
 * <p><b>Unknown JSON fields are ignored</b> at every level, so schema 1 can grow keys. A
 * manifest whose {@code schema} is greater than 1 is <b>rejected</b> with
 * {@link dev.bbkb.ime.core.distribution.UnsupportedSchemaException} — the app is too old, and
 * the only honest user-facing answer is "update the app by hand". An individual malformed
 * {@code app} channel or {@code packs} item is dropped rather than failing the whole parse.
 *
 * <h2>The pieces</h2>
 *
 * <dl>
 *   <dt>{@link dev.bbkb.ime.core.distribution.DistributionManifest} and friends
 *       ({@code AppBuild}, {@code Packs}, {@code PackEntry})</dt>
 *   <dd>The immutable data model, plus {@code appFor(buildType)}, {@code packFor(locale)},
 *       {@code AppBuild.isNewerThan(versionCode)} and {@code PackEntry.url(packs)}.</dd>
 *
 *   <dt>{@link dev.bbkb.ime.core.distribution.DistributionManifestParser}</dt>
 *   <dd>{@code parse(json)} / {@code tryParse(json)}. Pure, thread-safe.</dd>
 *
 *   <dt>{@link dev.bbkb.ime.core.distribution.DistributionConfig}</dt>
 *   <dd>The manifest URL, the channel, the {@code User-Agent}, and where files land. Home of the
 *       two debug-only preferences, {@code pref_distribution_manifest_url} and
 *       {@code pref_distribution_channel_override}, both inert on release builds.</dd>
 *
 *   <dt>{@link dev.bbkb.ime.core.distribution.ManifestSource}</dt>
 *   <dd>{@code suspend fun fetch(force: Boolean): Result<DistributionManifest>} — HTTPS with
 *       {@code ETag} re-validation and a 6-hour on-disk cache at
 *       {@code files/distribution/manifest.json}. Every success states whether it came off disk
 *       ({@code fromCache}) and when ({@code fetchedAt}); a cached manifest is never passed off
 *       as fresh.</dd>
 *
 *   <dt>{@link dev.bbkb.ime.core.distribution.Downloader}</dt>
 *   <dd>{@code suspend fun download(url, expectedSha256, expectedSize, destination, onProgress):
 *       Result<File>} — streams to {@code <destination>.tmp}, verifies size then SHA-256, and
 *       only then renames into place, so a file that exists is a file that was verified.
 *       Follows cross-host redirects by hand (GitHub assets need it), retries a failed
 *       connection twice, and deletes the partial file on mismatch or cancellation. Downloads
 *       land in {@code cacheDir/downloads/}; {@code clearStale(maxAgeMs)} prunes them.</dd>
 *
 *   <dt>{@link dev.bbkb.ime.core.distribution.Sha256},
 *       {@link dev.bbkb.ime.core.distribution.NetworkState}</dt>
 *   <dd>Streaming lowercase-hex SHA-256; "is there a network worth trying" (a hint, not a
 *       guarantee).</dd>
 * </dl>
 *
 * <h2>What a consumer must know</h2>
 *
 * <ul>
 *   <li><b>Threading.</b> {@code fetch} and {@code download} are {@code suspend} functions that
 *       do all their work on {@code Dispatchers.IO}; call them from anywhere. {@code onProgress}
 *       is invoked <em>on the IO dispatcher</em> — keep it cheap and marshal to the main thread
 *       yourself. The non-suspending helpers ({@code cached()}, {@code clearStale},
 *       {@code Sha256.of(File)}) do blocking disk I/O and must stay off the main thread.</li>
 *   <li><b>Cancellation is not failure.</b> Cancelling the calling coroutine propagates a
 *       {@code CancellationException} and deletes any partial file. A {@code Result.failure}
 *       always describes an operation that actually ran to a conclusion.</li>
 *   <li><b>Errors.</b> Every failure is a
 *       {@link dev.bbkb.ime.core.distribution.DistributionException}, a sealed subtype of
 *       {@code IOException}: {@code OfflineException}, {@code HttpStatusException},
 *       {@code ManifestFormatException}, {@code UnsupportedSchemaException},
 *       {@code IntegrityException}, {@code TooManyRedirectsException},
 *       {@code InsecureUrlException}, {@code TransportException}. Each carries the values a
 *       message needs, so nothing has to parse a message string.</li>
 *   <li><b>Where files land.</b> {@code files/distribution/} for the manifest cache and its
 *       metadata (durable — it is the only offline answer to "what packs exist");
 *       {@code cacheDir/downloads/} for downloaded APKs and {@code .ldb} files (reclaimable).</li>
 *   <li><b>Permissions.</b> This package's manifest additions are {@code INTERNET} and
 *       {@code ACCESS_NETWORK_STATE}, and nothing else. Installing an APK needs
 *       {@code REQUEST_INSTALL_PACKAGES} and a {@code FileProvider}; posting a notification on
 *       API 33+ needs {@code POST_NOTIFICATIONS}. Those belong to the feature that does them,
 *       not here.</li>
 * </ul>
 */
package dev.bbkb.ime.core.distribution;
