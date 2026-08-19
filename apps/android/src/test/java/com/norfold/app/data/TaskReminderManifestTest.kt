package com.norfold.app.data

import android.app.AlarmManager
import android.content.Intent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest half of task reminders, which no other test can see.
 *
 * A reminder receiver that is declared for the wrong action string is not a compile error and not a
 * unit-test failure: the broadcast simply never arrives, and the symptom is a reminder that does not
 * fire — indistinguishable from the bug this whole path exists to fix. Two of these strings are
 * spelled differently in the manifest than in Kotlin, and one is a platform constant that must be
 * matched exactly, so they are asserted rather than trusted.
 */
class TaskReminderManifestTest {
    private val manifest: String by lazy {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("apps/android/src/main/AndroidManifest.xml"),
        )
        (candidates.firstOrNull(File::isFile) ?: error("AndroidManifest.xml not found")).readText()
    }

    @Test
    fun receiverIsDeclaredAndNotExported() {
        // Not exported matters: nothing another app sends should be able to make Norfold post a
        // reminder, and the alarm and the system broadcasts both reach a non-exported receiver.
        val declaration = Regex("""<receiver[^>]*?\.data\.TaskReminderReceiver"[\s\S]*?</receiver>""")
            .find(manifest)
            ?.value
            ?: error("TaskReminderReceiver is not declared in the manifest")
        assertTrue("TaskReminderReceiver must not be exported", declaration.contains("""android:exported="false""""))
    }

    @Test
    fun deliverActionMatchesTheReceiversOwnConstant() {
        assertTrue(
            "Manifest is missing the alarm's own action, ${TaskReminderReceiver.ActionDeliver}",
            manifest.contains("""<action android:name="${TaskReminderReceiver.ActionDeliver}" />"""),
        )
    }

    @Test
    fun timeChangeActionIsTheSameStringTheReceiverChecks() {
        // ACTION_TIME_CHANGED reads as if it should be "...TIME_CHANGED"; its value is "...TIME_SET".
        // If a future edit "corrects" the manifest to match the constant's name, this fails.
        assertEquals("android.intent.action.TIME_SET", Intent.ACTION_TIME_CHANGED)
        assertTrue(
            "Manifest is missing ${Intent.ACTION_TIME_CHANGED}",
            manifest.contains("""<action android:name="${Intent.ACTION_TIME_CHANGED}" />"""),
        )
    }

    @Test
    fun exactAlarmPermissionChangeActionMatchesThePlatformConstant() {
        // Granting exact alarms in system settings must upgrade the pending alarm from a window to
        // an exact time. Hand-typed in the manifest, so it is checked against the constant.
        assertTrue(
            "Manifest is missing ${AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED}",
            manifest.contains(
                """<action android:name="${AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED}" />""",
            ),
        )
    }

    @Test
    fun rebootAndUpdateBroadcastsAreBothDeclared() {
        // AlarmManager keeps nothing across a restart, and an app update stops the process the same
        // way. Either one missing means reminders silently stop until the next manual launch.
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED).forEach { action ->
            assertTrue("Manifest is missing $action", manifest.contains("""<action android:name="$action" />"""))
        }
    }

    @Test
    fun exactAlarmPermissionIsTheUserGrantedOne() {
        // USE_EXACT_ALARM is pre-granted, and Play policy reserves it for alarm-clock, timer, and
        // calendar apps. Norfold is a productivity app, so declaring it is a policy violation even
        // though it would work. SCHEDULE_EXACT_ALARM is the one the user grants.
        assertTrue(
            "SCHEDULE_EXACT_ALARM must be declared",
            manifest.contains("""android:name="android.permission.SCHEDULE_EXACT_ALARM""""),
        )
        assertTrue(
            "USE_EXACT_ALARM must not be declared — Play policy reserves it for clock and calendar apps",
            !manifest.contains("android.permission.USE_EXACT_ALARM"),
        )
    }

    @Test
    fun bootPermissionIsDeclared() {
        assertTrue(
            "RECEIVE_BOOT_COMPLETED must be declared, or one reboot disarms every future reminder",
            manifest.contains("""android:name="android.permission.RECEIVE_BOOT_COMPLETED""""),
        )
    }
}
