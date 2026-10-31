package io.github.hangillab.hangil

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import io.github.hangillab.hangil.core.domain.BackgroundRecordingState
import io.github.hangillab.hangil.core.domain.RecordingController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Native promotion and lifetime only; one app-level controller owns every append. */
@AndroidEntryPoint
class VehicleRecordingService : Service() {
    @Inject lateinit var recording: RecordingController

    @Inject lateinit var host: VehicleRecordingServiceHost
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runId: String? = null
    private var work: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val requested = intent?.getStringExtra(EXTRA_RUN)?.takeIf { it.isNotBlank() }
        val session = intent?.getStringExtra(EXTRA_SESSION)?.takeIf { it.isNotBlank() }
        if (requested == null || session == null) {
            if (work == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (work != null) {
            // Duplicates and old intents never replace or stop a currently owned native lifetime.
            return START_NOT_STICKY
        }
        val pending = recording.background.value as? BackgroundRecordingState.Starting
        if (pending?.runId != requested || pending.sessionId != session) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < 34) {
            recording.startFailed(requested)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.background_recording_channel), NotificationManager.IMPORTANCE_LOW),
            )
            val open =
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val notification =
                Notification
                    .Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_recording_notification)
                    .setContentTitle(getString(R.string.background_recording_notification_title))
                    .setContentText(getString(R.string.background_recording_notification_body))
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setContentIntent(
                        PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                    ).build()
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (_: Exception) {
            recording.startFailed(requested)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        runId = requested
        lateinit var current: Job
        current =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    recording.recordInService(requested, session)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    recording.startFailed(requested)
                } finally {
                    if (runId == requested && work === current) {
                        runId = null
                        work = null
                        if (host.detach(requested)) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
        work = current
        try {
            host.attach(requested) { if (runId == requested && work === current) current.cancel() }
            current.start()
        } catch (_: Exception) {
            work = null
            runId = null
            current.cancel()
            recording.startFailed(requested)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RUN = "recording_run"
        const val EXTRA_SESSION = "recording_session"
        private const val CHANNEL = "vehicle_recording"
        private const val NOTIFICATION = 1001
    }
}
