/**
 * Editor communication layer: the only holder of an {@code InputConnection}. Reads text around
 * the cursor, commits and corrects text, runs batch edits, and derives field capabilities.
 * Imports only {@code CapsModeUtils} from its parent; nothing here reaches into {@code InputLogic}.
 * Main thread only: background callbacks (NuanceSDK JNI) must post via {@code UIUpdateHandler}.
 */
package dev.bbkb.ime.core.textinput.connection;
