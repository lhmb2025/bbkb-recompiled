package dev.bbkb.ime.core.device.interceptor

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.profile.DeviceProfileTestSupport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The BBKB helper's component follows the profile: disabled where `<accessibility-helper>off`
 * (so it is not in the system's Accessibility list), the manifest default everywhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyInterceptorComponentTest {

    private lateinit var context: Context
    private lateinit var component: ComponentName

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        component = ComponentName(context, KeyInterceptorService::class.java)
    }

    @After
    fun tearDown() {
        context.packageManager.setComponentEnabledSetting(
            component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        DeviceProfile.initialize(null)
    }

    private fun mapping(off: Boolean) = DeviceInputMapping().apply { accessibilityHelperOff = off }
    private fun state() = context.packageManager.getComponentEnabledSetting(component)

    @Test
    fun aProfileThatSwitchesTheHelperOffDisablesItsComponent() {
        KeyInterceptorComponent.apply(context, mapping(off = true))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state())
    }

    @Test
    fun anyOtherProfileRestoresTheManifestDefault() {
        KeyInterceptorComponent.apply(context, mapping(off = true))
        KeyInterceptorComponent.apply(context, mapping(off = false))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state())
        KeyInterceptorComponent.apply(context, mapping(off = true))
        KeyInterceptorComponent.apply(context, null)
        assertEquals("an unknown phone keeps the helper", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state())
    }

    @Test
    fun theProfileOnThisPhoneDecidesWhetherItIsRequired() {
        DeviceProfile.installForTest(DeviceCapabilities.forShape(
            DeviceCapabilities.DetectedDeviceType.PKB, true, false, false, "qwerty", "4row"))
        assertFalse(KeyInterceptorComponent.isNotRequiredOnThisPhone())
        DeviceProfileTestSupport.installMapping(mapping(off = true))
        assertTrue(KeyInterceptorComponent.isNotRequiredOnThisPhone())
    }
}
