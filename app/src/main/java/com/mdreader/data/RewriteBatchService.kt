package com.mdreader.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mdreader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Keeps a batch rewrite alive with the app in the background: a foreground service with a progress
 * notification and a Stop button. The work is [BatchRewrite.run]; this only hosts it.
 */
class RewriteBatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            return START_NOT_STICKY
        }
        val projectId = intent?.getStringExtra(EXTRA_PROJECT)
        val queue = intent?.getIntArrayExtra(EXTRA_QUEUE)?.toList().orEmpty()
        val project = projectId?.let { LibraryStore(applicationContext).find(it) }
        if (project == null || queue.isEmpty() || job?.isActive == true || queue.any { it !in project.chapters.indices }) {
            if (job?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        BatchRewrite.begin(project, queue)
        startForegroundCompat(notification(BatchRewrite.state.value))
        BatchRewrite.state.onEach { state ->
            if (state.running) notify(notification(state))
        }.launchIn(scope)
        job = scope.launch {
            try {
                BatchRewrite.run(
                    applicationContext, project, queue,
                    settings = RewriteStore.of(applicationContext).settings(),
                    rewrites = RewriteStore.of(applicationContext),
                    vault = Vault.of(applicationContext),
                )
            } finally {
                val last = BatchRewrite.state.value
                stopForeground(STOP_FOREGROUND_REMOVE)
                // The result stays visible after the service is gone.
                notify(resultNotification(last), RESULT_ID)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 ends a data-sync service after about six hours: stop cleanly and say so. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ONGOING_ID, notification)
    }

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun notify(notification: Notification, id: Int = ONGOING_ID) {
        runCatching { manager().notify(id, notification) }
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) {
            manager().createNotificationChannel(
                NotificationChannel(CHANNEL, "Rewriting ahead", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of chapters being rewritten in advance"
                },
            )
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, packageManager.getLaunchIntentForPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(state: BatchRewrite.State): Notification {
        channel()
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RewriteBatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_batch)
            .setContentTitle("Rewriting ${state.projectName} · ${minOf(state.done + 1, maxOf(state.total, 1))} of ${state.total}")
            .setContentText(listOf(state.currentLabel, state.stage.lowercase()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Starting" })
            .setProgress(state.total, state.done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun resultNotification(state: BatchRewrite.State): Notification {
        channel()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_batch)
            .setContentTitle(state.projectName.ifBlank { "Rewriting ahead" })
            .setContentText(state.result ?: "Done")
            .setStyle(NotificationCompat.BigTextStyle().bigText(state.result ?: "Done"))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
    }

    companion object {
        private const val CHANNEL = "batch-rewrite"
        private const val ONGOING_ID = 41
        private const val RESULT_ID = 42
        private const val ACTION_STOP = "com.mdreader.STOP_BATCH"
        private const val EXTRA_PROJECT = "project"
        private const val EXTRA_QUEUE = "queue"

        /** Starts rewriting [queue] (chapter positions of [projectId]) in the background. */
        fun start(context: Context, projectId: String, queue: List<Int>) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RewriteBatchService::class.java)
                    .putExtra(EXTRA_PROJECT, projectId)
                    .putExtra(EXTRA_QUEUE, queue.toIntArray()),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RewriteBatchService::class.java).setAction(ACTION_STOP))
        }
    }
}
