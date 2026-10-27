package com.pluginbrowser.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.pluginbrowser.MainActivity
import com.pluginbrowser.R
import com.pluginbrowser.data.DownloadItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 下载前台服务。
 *
 * 自己不下载，只做两件事：让进程在后台活着不被回收、把引擎的进度画到通知栏。
 * 引擎是单例，服务被回收后重新起来会调 resumeUnfinished() 接着下。
 */
class DownloadService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: DownloadEngine

    private val ticker = object : Runnable {
        override fun run() {
            val active = activeTasks()
            if (active.isEmpty() && !engine.hasActive()) {
                finish()
                return
            }
            notificationManager().notify(NOTIF_ID, buildNotification(active))
            handler.postDelayed(this, 800)
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = DownloadEngine.get(this)
        createChannel()
        // 必须在 onStartCommand 之后 5 秒内挂上前台，否则 ANR 崩溃
        // 少数 ROM 会拒绝 dataSync 类型的前台服务，起不来就直接退，别把进程带走
        val started = runCatching {
            startForeground(NOTIF_ID, buildNotification(emptyList()))
        }.isSuccess
        if (!started) {
            stopSelf()
            return
        }
        // 先把没下完的任务接回来，再开始轮询，否则第一轮就会判定「没任务」把服务关掉
        scope.launch {
            engine.resumeUnfinished()
            handler.post(ticker)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            intent.getLongExtra(EXTRA_ID, -1L).takeIf { it > 0 }?.let { engine.cancel(it) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        scope.cancel()
        super.onDestroy()
    }

    /* ---------------- 通知 ---------------- */

    private fun activeTasks(): List<DownloadEngine.TaskState> =
        engine.snapshot().values
            .filter { it.status == DownloadItem.STATUS_RUNNING || it.status == DownloadItem.STATUS_PENDING }
            .sortedBy { it.id }

    private fun buildNotification(active: List<DownloadEngine.TaskState>): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (active.isEmpty()) {
            builder.setContentTitle(getString(R.string.download_notification_preparing))
                .setProgress(0, 0, true)
            return builder.build()
        }

        val first = active.first()
        val extra = if (active.size > 1) "  ·  还有 ${active.size - 1} 个" else ""
        builder.setContentTitle(first.name)
        builder.setContentText(statusText(first) + extra)
        if (first.total > 0) {
            builder.setProgress(100, first.percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        builder.addAction(
            0,
            getString(R.string.downloads_cancel_action),
            cancelIntent(first.id),
        )
        return builder.build()
    }

    private fun statusText(task: DownloadEngine.TaskState): String = buildString {
        if (task.total > 0) {
            append(task.percent).append("%  ·  ")
            append(formatBytes(task.downloaded)).append(" / ").append(formatBytes(task.total))
        } else {
            append(formatBytes(task.downloaded))
        }
        if (task.speed > 0) append("  ·  ").append(formatBytes(task.speed)).append("/s")
    }

    /** 全部下完：撤掉前台通知，补一条「已下载」的普通通知 */
    private fun finish() {
        val done = engine.takeFinished()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager().cancel(NOTIF_ID)
        if (done.isNotEmpty()) {
            val text = if (done.size == 1) {
                done.first()
            } else {
                getString(R.string.download_notification_done_many, done.size)
            }
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(getString(R.string.download_notification_done))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            notificationManager().notify(DONE_ID, notification)
        }
        stopSelf()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun cancelIntent(id: Long): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getService(
            this,
            id.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.download_notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_CANCEL = "com.pluginbrowser.download.CANCEL"
        const val EXTRA_ID = "download_id"

        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 1001
        private const val DONE_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun cancel(context: Context, id: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_ID, id)
            }
            runCatching { context.startService(intent) }
        }
    }
}
