/**
 * JNI shim for the closed Nuance XT9 engine. This package cannot be renamed: the prebuilt
 * {@code libnative-lib.so} exports 54 {@code Java_com_blackberry_nuanceshim_NuanceSDK_*} symbols and
 * looks up {@code NuanceSDK}, {@code KeyInfo} and {@code WordInfo} by name via {@code FindClass}.
 * A rename fails at the first keystroke, not at build time. Our own C in {@code app/src/main/cpp}
 * binds Xt9Kdb, Xt9KdbVariant, Xt9Trace and Et9Probe here too.
 * {@code PackageIdentityTest} allow-lists only this package for the {@code com.blackberry} prefix.
 */
package com.blackberry.nuanceshim;
