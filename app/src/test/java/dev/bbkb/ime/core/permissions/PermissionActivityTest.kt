package dev.bbkb.ime.core.permissions

import android.Manifest
import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.permissions.PermissionRequestHandler.Result
import dev.bbkb.ime.core.settings.PrefsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** The state table in [PermissionActivity]'s class comment, row by row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionActivityTest {

    private val perm = Manifest.permission.READ_CONTACTS
    private lateinit var app: Application
    private val results = mutableListOf<Pair<String, Result>>()
    private val callback = PermissionRequestHandler.Callback { p, r -> results += p to r }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PrefsManager.getPrefs(app).edit().clear().commit()
        ShadowAlertDialog.reset()
    }

    private fun setExplains(explains: Boolean) =
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(perm, explains)

    private fun setBlocked() = PrefsManager.getPrefs(app).edit().putBoolean(perm, true).commit()

    private fun isBlocked() = PrefsManager.getPrefs(app).getBoolean(perm, false)

    /** Starts a request the way the keyboard does and returns the launched activity. */
    private fun start(explanation: String? = "Why"): ActivityController<PermissionActivity> {
        PermissionRequestHandler(app, callback).requestPermission(perm, explanation)
        val intent: Intent = shadowOf(app).nextStartedActivity
        return launch(intent)
    }

    private fun launch(intent: Intent): ActivityController<PermissionActivity> {
        val controller = Robolectric.buildActivity(PermissionActivity::class.java, intent).setup()
        shadowOf(Looper.getMainLooper()).idle()
        return controller
    }

    private fun dialog(): AlertDialog? = ShadowAlertDialog.getLatestAlertDialog()

    private fun tap(which: Int) {
        dialog()!!.getButton(which).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Answers the system request the activity made. */
    @Suppress("DEPRECATION")
    private fun answer(activity: PermissionActivity, granted: Boolean) {
        val req = shadowOf(activity).lastRequestedPermission!!
        if (granted) shadowOf(app).grantPermissions(perm)
        activity.onRequestPermissionsResult(
            req.requestCode,
            req.requestedPermissions,
            intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun pureDecisions() {
        assertTrue(PermissionActivity.shouldShowDialog(false, false))
        assertTrue(PermissionActivity.shouldShowDialog(false, true))
        assertTrue(PermissionActivity.shouldShowDialog(true, true))
        assertFalse(PermissionActivity.shouldShowDialog(true, false))
        assertEquals(Result.PERMISSION_GRANTED, PermissionActivity.resultOfRequest(true, false))
        assertEquals(Result.PERMISSION_GRANTED, PermissionActivity.resultOfRequest(true, true))
        assertEquals(Result.PERMISSION_DENIED, PermissionActivity.resultOfRequest(false, true))
        assertEquals(Result.PERMISSION_BLOCKED, PermissionActivity.resultOfRequest(false, false))
    }

    @Test
    fun alreadyGrantedStartsNothingAndNeverCallsBack() {
        shadowOf(app).grantPermissions(perm)
        PermissionRequestHandler(app, callback).requestPermission(perm, "Why")
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(results.isEmpty())
    }

    @Test
    fun launchIntentKeepsItsOwnTask() {
        PermissionRequestHandler(app, callback).requestPermission(perm, "Why")
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(PermissionActivity::class.java.name, intent.component!!.className)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(flags, intent.flags and flags)
    }

    @Test
    fun emptyExplanationIsDeniedWithoutADialog() {
        val c = start(explanation = "")
        assertNull(dialog())
        assertEquals(listOf(perm to Result.PERMISSION_DENIED), results)
        assertTrue(c.get().isFinishing)
    }

    @Test
    fun blockedAndNotExplainedShowsTheBlockedDialog() {
        setBlocked()
        setExplains(false)
        val c = start()
        val d = dialog()!!
        assertTrue(d.isShowing)
        assertTrue("nothing is reported until a button is tapped", results.isEmpty())
        val message = shadowOf(d).message.toString()
        assertTrue(message.startsWith("Why"))
        assertTrue(message.contains(app.getString(dev.bbkb.ime.R.string.permission_blocked_explanation)))
        assertFalse(shadowOf(d).isCancelable)
        assertFalse(shadowOf(d).isCancelableOnTouchOutside)

        tap(AlertDialog.BUTTON_NEGATIVE)
        assertEquals(listOf(perm to Result.PERMISSION_BLOCKED), results)
        assertTrue(c.get().isFinishing)
        assertNull(shadowOf(c.get()).lastRequestedPermission)
        assertNull(shadowOf(c.get()).nextStartedActivity)
    }

    @Test
    fun blockedDialogOpensTheAppSettingsAfterReporting() {
        setBlocked()
        setExplains(false)
        val c = start()
        tap(AlertDialog.BUTTON_POSITIVE)
        assertEquals(listOf(perm to Result.PERMISSION_BLOCKED), results)
        val next = shadowOf(c.get()).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, next.action)
        assertEquals("package:${app.packageName}", next.dataString)
        assertTrue(next.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(c.get().isFinishing)
        // Leaving for the settings page reports nothing more.
        c.userLeaving()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, results.size)
    }

    @Test
    fun blockedButExplainedStillShowsTheDialog() {
        setBlocked()
        setExplains(true)
        start()
        assertTrue(dialog()!!.isShowing)
        assertTrue(results.isEmpty())
    }

    @Test
    fun denyButtonIsDeniedAndLeavesPrefsAlone() {
        val c = start()
        assertTrue(dialog()!!.isShowing)
        tap(AlertDialog.BUTTON_NEGATIVE)
        assertEquals(listOf(perm to Result.PERMISSION_DENIED), results)
        assertFalse(isBlocked())
        assertTrue(c.get().isFinishing)
        assertNull(shadowOf(c.get()).lastRequestedPermission)
    }

    @Test
    fun dialogIgnoresBackAndOutsideTaps() {
        start()
        val shadow = shadowOf(dialog())
        assertFalse(shadow.isCancelable)
        assertFalse(shadow.isCancelableOnTouchOutside)
    }

    @Test
    fun leavingTheDialogIsOther() {
        val c = start()
        c.userLeaving()
        assertEquals(listOf(perm to Result.OTHER), results)
        assertTrue(c.get().isFinishing)
    }

    @Test
    fun askThenGrantIsGranted() {
        val c = start()
        tap(AlertDialog.BUTTON_POSITIVE)
        assertEquals(listOf(perm), shadowOf(c.get()).lastRequestedPermission!!.requestedPermissions.toList())
        assertTrue(results.isEmpty())
        answer(c.get(), granted = true)
        assertEquals(listOf(perm to Result.PERMISSION_GRANTED), results)
        assertTrue(c.get().isFinishing)
    }

    @Test
    fun askThenRefuseWhileExplainedIsDenied() {
        val c = start()
        tap(AlertDialog.BUTTON_POSITIVE)
        setExplains(true)
        answer(c.get(), granted = false)
        assertEquals(listOf(perm to Result.PERMISSION_DENIED), results)
        assertFalse(isBlocked())
    }

    @Test
    fun askThenRefuseWithoutExplanationIsBlockedAndRemembered() {
        val c = start()
        tap(AlertDialog.BUTTON_POSITIVE)
        setExplains(false)
        answer(c.get(), granted = false)
        assertEquals(listOf(perm to Result.PERMISSION_BLOCKED), results)
        assertTrue(isBlocked())
    }

    @Test
    fun leavingDuringTheSystemRequestIsNotAnAnswer() {
        val c = start()
        tap(AlertDialog.BUTTON_POSITIVE)
        c.userLeaving()
        assertTrue(results.isEmpty())
        answer(c.get(), granted = true)
        assertEquals(listOf(perm to Result.PERMISSION_GRANTED), results)
    }

    @Test
    fun finishingWithoutAnAnswerIsOtherExactlyOnce() {
        val c = start()
        tap(AlertDialog.BUTTON_POSITIVE)
        c.get().finish()
        c.pause().stop().destroy()
        assertEquals(listOf(perm to Result.OTHER), results)
    }

    @Test
    fun answerThenDestroyCallsBackOnce() {
        val c = start()
        tap(AlertDialog.BUTTON_NEGATIVE)
        c.pause().stop().destroy()
        assertEquals(listOf(perm to Result.PERMISSION_DENIED), results)
    }

    @Test
    fun aNewerRequestReplacesTheWaitingOne() {
        val first = start()
        val second = start()
        assertEquals(listOf(perm to Result.OTHER), results)
        first.get().finish()
        first.pause().stop().destroy()
        assertEquals(1, results.size)
        tap(AlertDialog.BUTTON_NEGATIVE)
        assertEquals(listOf(perm to Result.OTHER, perm to Result.PERMISSION_DENIED), results)
        assertTrue(second.get().isFinishing)
    }

    @Test
    fun aStaleIntentFinishesQuietly() {
        // An activity recreated after process death carries a token nobody is waiting for.
        PermissionRequestHandler(app, callback).requestPermission(perm, "Why")
        val intent = shadowOf(app).nextStartedActivity
        start().also { tap(AlertDialog.BUTTON_NEGATIVE) }
        results.clear()
        ShadowAlertDialog.reset()
        val stale = launch(intent)
        assertNull(dialog())
        assertTrue(stale.get().isFinishing)
        stale.pause().stop().destroy()
        assertTrue(results.isEmpty())
    }

    @Test
    fun recreatedDuringTheSystemRequestKeepsWaitingForTheAnswer() {
        val c = start()
        tap(AlertDialog.BUTTON_POSITIVE)
        val req = shadowOf(c.get()).lastRequestedPermission!!
        ShadowAlertDialog.reset()
        val recreated = c.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("no second rationale after a rotation mid-request", dialog())
        assertFalse(recreated.get().isFinishing)
        assertTrue(results.isEmpty())
        // The registry redelivers the system answer to the new instance.
        shadowOf(app).grantPermissions(perm)
        @Suppress("DEPRECATION")
        recreated.get().onRequestPermissionsResult(
            req.requestCode, req.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(perm to Result.PERMISSION_GRANTED), results)
    }

    @Test
    fun rotatingDuringTheRationaleShowsItAgain() {
        val c = start()
        ShadowAlertDialog.reset()
        c.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dialog()!!.isShowing)
        assertTrue(results.isEmpty())
    }
}
