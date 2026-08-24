package com.norfold.app.data

import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * The consent gate in front of automatic crash upload.
 *
 * Norfold's posture is that nothing leaves the device unasked: `allowBackup="false"`, both backup rule
 * files exclude everything, and the diagnostics log has always been local-with-a-manual-share. Adding a
 * service that uploads stack traces by itself would contradict all of that unless the upload is gated on
 * a real answer, which is what this is. There are three layers and each one covers a different window:
 *
 *  1. `firebase_crashlytics_collection_enabled=false` in the manifest. Crashlytics installs its handler
 *     from a merged-in ContentProvider, which runs *before* `Application.onCreate` — so the manifest is
 *     the only thing that can speak before our first line of Kotlin does. Without it, a crash during
 *     early startup on a first launch would upload before the user had ever seen a settings screen.
 *  2. [apply], called from `NorfoldApplication.onCreate` on every start, which writes the persisted
 *     enabled flag from the local diagnostics preference. Crashlytics stores that flag itself, so this is
 *     what makes the manifest default a *starting point* rather than a permanent off.
 *  3. [setEnabled], called when the preference changes, so turning diagnostics off stops upload
 *     immediately and discards anything already queued rather than only stopping the next report.
 *
 * The switch it follows is the existing local-logging preference — now labelled "Enable diagnostics and
 * crash reporting" — deliberately and not for convenience: that switch already means "record what went
 * wrong", the Settings copy beside it now says upload is part of that, and inventing a second
 * nearly-identical toggle would leave a user who turned logging off believing they had also turned upload
 * off when they had not. One answer, one meaning.
 *
 * Nothing here logs content. No note text, title, workspace name, task name, message body or file name
 * is ever passed to Crashlytics — a stack trace plus the app version is the whole payload. That rule is
 * pinned by CrashReportingGateTest, which greps this file, because it is the kind of thing that erodes
 * one convenient `setCustomKey` at a time.
 */
object CrashReportingGate {

    /**
     * Aligns Crashlytics with the stored preference. Safe to call unconditionally: with no
     * `google-services.json` there is no `FirebaseApp`, `getInstance()` throws, and the gate stays shut —
     * which is the correct outcome for a build that has no Firebase project to report to anyway.
     */
    fun apply(context: Context) {
        setEnabled(DiagnosticsStore(context.applicationContext).state().enabled)
    }

    /**
     * Turning the gate off deletes reports already written but not yet sent. A user who withdraws consent
     * means the crash from ten minutes ago too, not merely the next one — leaving a queued report to
     * upload on the following launch would make the switch a promise about the future only.
     */
    fun setEnabled(enabled: Boolean) {
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCrashlyticsCollectionEnabled(enabled)
            if (!enabled) crashlytics.deleteUnsentReports()
        }
    }

    /**
     * Hands a caught throwable to Crashlytics without going through an uncaught crash. Collection state
     * is still the SDK's to honour — a disabled gate drops this on the floor, which is intended.
     *
     * The message is a fixed description of *where* the failure happened, never data from the failure.
     * `recordException` uploads the throwable's own message and stack, which can legitimately contain a
     * file name or a URL from a library, and that is the floor of what a crash report is; what this app
     * must not do is add to it.
     */
    fun recordHandled(where: String, throwable: Throwable) {
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                log(where)
                recordException(throwable)
            }
        }
    }
}
