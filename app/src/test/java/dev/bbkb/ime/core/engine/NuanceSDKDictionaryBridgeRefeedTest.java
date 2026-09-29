package dev.bbkb.ime.core.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.blackberry.nuanceshim.NuanceSDK;

import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * When the suggestion request must NOT clear and replay the composing text into the engine.
 *
 * <p>The typing path feeds the engine jamo, base letters plus explicit accents, romaji, stroke
 * numbers or Cangjie keys; the composing text holds the composed result. Replaying that result as
 * plain symbols between two keystrokes destroyed the engine's state: on the KEY2 (2026-09-28)
 * Korean final consonants never attached and Telex produced "viẹt" for "việt". Robolectric
 * instruments the nuanceshim package so the native-backed {@code NuanceSDK} class can be mocked.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, instrumentedPackages = {"com.blackberry.nuanceshim"})
public class NuanceSDKDictionaryBridgeRefeedTest {

    private NuanceSDK sdk;
    private ComposingTextTracker tracker;

    @Before
    public void setUp() {
        sdk = mock(NuanceSDK.class);
        tracker = mock(ComposingTextTracker.class);
    }

    @Test
    public void plainLatinTypingIsReplayed() {
        when(tracker.hasInputMethodConverter()).thenReturn(false);
        when(sdk.isChineseStrokeMode()).thenReturn(false);
        when(sdk.isChineseCangjieMode()).thenReturn(false);
        assertFalse(NuanceSDKDictionaryBridge.engineOwnsComposingState(tracker, sdk));
    }

    @Test
    public void aConverterOwnsTheEngineState() {
        when(tracker.hasInputMethodConverter()).thenReturn(true);
        assertTrue(NuanceSDKDictionaryBridge.engineOwnsComposingState(tracker, sdk));
    }

    @Test
    public void strokeModeOwnsTheEngineState() {
        when(sdk.isChineseStrokeMode()).thenReturn(true);
        assertTrue(NuanceSDKDictionaryBridge.engineOwnsComposingState(tracker, sdk));
    }

    @Test
    public void cangjieModeOwnsTheEngineState() {
        when(sdk.isChineseCangjieMode()).thenReturn(true);
        assertTrue(NuanceSDKDictionaryBridge.engineOwnsComposingState(tracker, sdk));
    }
}
