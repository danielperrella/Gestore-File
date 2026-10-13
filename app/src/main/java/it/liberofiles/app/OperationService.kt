package it.liberofiles.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import it.liberofiles.core.*

class OperationService : Service() {
    private val graph get() = (application as FilesApplication).graph
    private val channel = "file-operations"
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStart = 0
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Operazioni sui file", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(title: String, detail: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, OperationService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_app).setContentTitle(title)
            .setContentText(detail).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Annulla", cancel).build()).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        if (intent?.action == "cancel") { graph.cancellation?.cancel(); graph.pending.clear(); return START_NOT_STICKY }
        startForeground(100, notification(getString(R.string.app_name), "Preparazione operazione"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (graph.running.compareAndSet(false, true)) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GestoreFile:operations").apply { acquire(6 * 60 * 60 * 1000L) }
            graph.operations.execute {
                try {
                    while (true) {
                        val work = graph.pending.poll() ?: break
                        val cancel = Cancellation(); graph.cancellation = cancel
                        graph.publish(JobState(true, work.title, revision = graph.state.revision))
                        var lastProgress = 0L
                        val progress: (Progress) -> Unit = { p ->
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgress >= 150) {
                                lastProgress = now
                                graph.publish(graph.state.copy(progress = p))
                                getSystemService(NotificationManager::class.java).notify(100, notification(work.title, "${p.phase}: ${p.file}"))
                            }
                        }
                        try {
                            val outcome = work.execute(cancel, progress)
                            graph.publish(JobState(false, work.title, message = outcome.message, report = outcome.report, revision = graph.state.revision + 1))
                        } catch (e: Exception) {
                            graph.publish(JobState(false, work.title, message = e.message ?: e.javaClass.simpleName, failed = true, revision = graph.state.revision + 1))
                            // Don't continue queued writes after an unexpected storage failure.
                            graph.pending.clear()
                            break
                        }
                    }
                } finally {
                    graph.cancellation = null
                    wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
                    graph.running.set(false)
                    graph.main.post {
                        if (graph.pending.isEmpty() && !graph.running.get()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(lastStart) }
                        else if (!graph.running.get()) startService(Intent(this, OperationService::class.java))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        graph.cancellation?.cancel(); graph.pending.clear(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { wakeLock?.let { if (it.isHeld) it.release() }; super.onDestroy() }
}
