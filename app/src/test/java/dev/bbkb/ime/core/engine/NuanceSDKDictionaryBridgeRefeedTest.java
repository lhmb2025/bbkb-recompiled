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
 * When the suggestion request must not clear and replay the composing text into the engine: the
 * typing path feeds jamo, base letters plus accents, romaji, strokes or Cangjie keys, and replaying
 * the composed result breaks engine state (Korean finals never attach, Telex gives "viẹt"). The
 * nuanceshim package is instrumented so {@code NuanceSDK} can be mocked.
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
