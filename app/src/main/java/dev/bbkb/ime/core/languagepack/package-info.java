/**
 * Downloadable language packs: the catalogue, the download queue, and the one installer.
 *
 * <p>This package sits between the transport foundation in
 * {@link dev.bbkb.ime.core.distribution} (which knows how to find out what is published, fetch a
 * file and prove it is the right one) and {@code com.blackberry.nuanceshim.languagepack} (which
 * knows what an installed pack <em>is</em> on disk and in the registry). It owns everything in
 * between: which packs exist, which are installed, what is downloading, and the single code path
 * that turns a {@code .ldb} file into an installed dictionary.
 *
 * <h2>The decisions this layer encodes</h2>
 *
 * <ul>
 *   <li><b>Every pack is downloadable individually.</b> There is no bundle, no "all packs" and no
 *       automatic download. A pack arrives because the user asked for it, from the Language packs
 *       screen or from the prompt that appears when they enable a language whose dictionary is
 *       missing.</li>
 *   <li><b>A downloaded pack installs under the <em>manifest's</em> locale, never one parsed out
 *       of the file name.</b> Four packs can only be placed this way — {@code es_419},
 *       {@code zh_TW}, {@code zh_HK} and {@code en_ZH} all carry their region in the catalogue
 *       and not in the file name, so the filename parser reads them as plain {@code es}, {@code
 *       zh} and {@code en} and would drop three of them on top of a different dictionary. The
 *       file-name parse survives for exactly one caller: the "+" picker, where there is no
 *       manifest entry to consult.</li>
 *   <li><b>One installer.</b> {@link dev.bbkb.ime.core.languagepack.PackInstallService} is the
 *       only thing that writes {@code no_backup/nuance/}; the picker and the downloader both go
 *       through it, so "installed" means the same thing however the file arrived.</li>
 *   <li><b>Downloads outlive the screen.</b>
 *       {@link dev.bbkb.ime.core.languagepack.PackDownloadManager} is a process-wide singleton
 *       holding a {@code StateFlow}, so rotating the phone or walking to another settings screen
 *       does not cancel or restart anything. It runs <b>one download at a time</b>; further
 *       requests queue.</li>
 *   <li><b>No keyboard-side notification.</b> The offer to download a missing dictionary is a
 *       Settings affordance only. The IME's own
 *       {@code LanguagePackLocaleMonitor.NOTIFICATION_NOT_INSTALLED} stays informational.</li>
 * </ul>
 *
 * <h2>The pieces</h2>
 *
 * <dl>
 *   <dt>{@link dev.bbkb.ime.core.languagepack.PackInstallService}</dt>
 *   <dd>{@code installFromFile(file, locale, displayName, version, group)} — writes
 *       {@code no_backup/nuance/<locale>/<locale>.ldb} plus {@code version.txt}, registers the
 *       pack in {@code CustomPackRegistryStore} (or parks it as a variant through
 *       {@code LanguageVariantStore} when {@code group} is set), reloads the registry, offers a
 *       runtime subtype when {@code method.xml} has none, and posts
 *       {@code ACTION_LANGUAGE_PACK_CHANGED}.</dd>
 *
 *   <dt>{@link dev.bbkb.ime.core.languagepack.PackCatalog}</dt>
 *   <dd>The manifest's {@code Packs} merged with what is installed and what is downloading, as
 *       rows sorted by display name with each language's regional variants nested under it. Pure:
 *       {@code PackCatalog.from(manifest, installed, downloads)} touches no disk and no network,
 *       which is what makes it testable against the real {@code dist/manifest.json}.</dd>
 *
 *   <dt>{@link dev.bbkb.ime.core.languagepack.InstalledPacks}</dt>
 *   <dd>A snapshot of "what is on this phone": the shipped locales, the ones installed under
 *       {@code nuance/}, and the variant groups. {@code read(context)} builds it off disk;
 *       everything else takes it as a value.</dd>
 *
 *   <dt>{@link dev.bbkb.ime.core.languagepack.PackDownloadManager}</dt>
 *   <dd>The queue and its {@code StateFlow<Map<String, PackState>>}. Download → verify → install
 *       → delete the download file, with cancellation and errors mapped to the
 *       {@code DistributionException} the foundation raised.</dd>
 *
 *   <dt>{@link dev.bbkb.ime.core.languagepack.PackOffer}</dt>
 *   <dd>The pure decision behind "you enabled a language whose dictionary is missing": given a
 *       locale, the catalogue and two predicates, either a pack to offer or nothing.</dd>
 * </dl>
 *
 * <h2>What a consumer must know</h2>
 *
 * <ul>
 *   <li><b>Threading.</b> {@code installFromFile} and the manager's work are {@code suspend} /
 *       coroutine based and do their disk and network work on {@code Dispatchers.IO}.
 *       {@code InstalledPacks.read} is blocking disk I/O and must stay off the main thread.</li>
 *   <li><b>Failure is never partial.</b> A failed install leaves nothing behind: the pack
 *       directory is removed and no registry entry is written. A failed download leaves nothing
 *       at all — that is {@code Downloader}'s guarantee, not this package's.</li>
 *   <li><b>Cancellation is not failure</b>, exactly as in the foundation: a cancelled download
 *       drops back to {@code Available} rather than to {@code Failed}.</li>
 * </ul>
 */
package dev.bbkb.ime.core.languagepack;
