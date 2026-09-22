/**
 * The JNI shim for the closed Nuance XT9 engine. <b>This package cannot be renamed.</b>
 *
 * <p>Everything else this app owns moved out of the old BlackBerry class root and into
 * {@code dev.bbkb.ime} when the app took its own identity. This package deliberately did not,
 * and it is not an oversight: the JNI naming convention derives a native symbol from the Java
 * class's fully-qualified name, so the package name is part of the binary ABI between these
 * classes and two shared objects we cannot regenerate from source.
 *
 * <p>The prebuilt engine, {@code app/src/main/jniLibs/arm64-v8a/libnative-lib.so}, is a blob:
 * it <b>exports</b> 54 {@code Java_com_blackberry_nuanceshim_NuanceSDK_*} entry points, and it
 * <b>imports</b> three of our types by name via {@code FindClass}, which the strings
 * {@code com/blackberry/nuanceshim/NuanceSDK}, {@code .../KeyInfo} and {@code .../WordInfo} in
 * its data section spell out. Rename the package and every one of those bindings becomes an
 * {@code UnsatisfiedLinkError} (for the exports) or a {@code ClassNotFoundException} inside
 * native code (for the {@code FindClass} imports) — at the first keystroke, not at build time.
 *
 * <p>Our own C in {@code app/src/main/cpp/**} is the same story from the other side. It defines
 * {@code Java_com_blackberry_nuanceshim_*} functions for {@link com.blackberry.nuanceshim.Xt9Kdb},
 * {@link com.blackberry.nuanceshim.Xt9KdbVariant}, {@link com.blackberry.nuanceshim.Xt9Trace} and
 * {@link com.blackberry.nuanceshim.Et9Probe}. Those we could re-spell, but they would then have
 * to live in a different package from the blob-bound classes they sit beside, which buys nothing.
 *
 * <p>So the package stays whole. {@code languagepack} and the plain data holders have no native
 * binding of their own and could technically move, but splitting the package to chase a name is
 * churn with a real hazard attached, and nothing outside reads a package name here.
 *
 * <p>{@code PackageIdentityTest} allow-lists exactly this package, and nothing else, for the
 * {@code com.blackberry} prefix.
 */
package com.blackberry.nuanceshim;
