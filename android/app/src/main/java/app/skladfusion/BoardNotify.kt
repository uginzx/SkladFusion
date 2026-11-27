package app.skladfusion

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Уведомления о заданиях. Web Push доски в приложении не работает, FCM не подключаем:
 * раз в 15 минут (WorkManager) и каждые 20 с, пока приложение открыто, телефон забирает дельту доски
 * и показывает локальное уведомление о новых заданиях складу и чужих сообщениях.
 */
object BoardNotify {
    private const val CHANNEL = "board"
    const val EXTRA_TASK = "board_task"
    private const val ID_TASKS = 7001
    private const val ID_MSGS = 7002

    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "board",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BoardWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    private fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Задания склада", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Новые задания и сообщения по ним"
            })
        }
    }

    fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun open(ctx: Context, task: String?, code: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_TASK, task ?: "")
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Проверить, что нового, и показать уведомление. Вызывается после синхронизации доски. */
    fun check(ctx: Context) {
        val board = ctx.repo.board
        val fresh = board.takeFresh()
        if (!allowed(ctx)) return
        channel(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        if (fresh.tasks.isNotEmpty()) {
            val one = fresh.tasks.singleOrNull()
            val total = board.newCount()
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_box)
                .setContentTitle(if (one != null) (if (one.prio) "Срочное задание" else "Новое задание") else "Новых заданий: ${fresh.tasks.size}")
                .setContentText(one?.title ?: fresh.tasks.joinToString(" · ") { it.title })
                .setStyle(NotificationCompat.BigTextStyle().bigText(fresh.tasks.joinToString("\n") { (if (it.prio) "⚑ " else "") + it.title }))
                .setNumber(total)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open(ctx, one?.id, ID_TASKS))
            runCatching { nm.notify(ID_TASKS, b.build()) }
        }
        if (fresh.messages.isNotEmpty()) {
            val users = board.users()
            val last = fresh.messages.last()
            val sameTask = fresh.messages.all { it.first.id == last.first.id }
            val text = { m: Pair<app.skladfusion.data.BoardTask, app.skladfusion.data.BoardComment> ->
                (users[m.second.by] ?: "Кто-то") + ": " + m.second.text.ifBlank { "фото" }
            }
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_box)
                .setContentTitle(if (sameTask) "Сообщение: ${last.first.title}" else "Новых сообщений: ${fresh.messages.size}")
                .setContentText(text(last))
                .setStyle(NotificationCompat.BigTextStyle().bigText(fresh.messages.takeLast(6).joinToString("\n") { (if (sameTask) "" else "«${it.first.title}» ") + text(it) }))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open(ctx, if (sameTask) last.first.id else null, ID_MSGS))
            runCatching { nm.notify(ID_MSGS, b.build()) }
        }
    }

    /** Кладовщик открыл задания — уведомления больше не нужны. */
    fun clear(ctx: Context) {
        NotificationManagerCompat.from(ctx).apply { cancel(ID_TASKS); cancel(ID_MSGS) }
    }
}

class BoardWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // sync() сам дошлёт очередь, заберёт изменения и вызовет afterSync (уведомление, докачка образцов).
        applicationContext.repo.board.sync()
        return Result.success()
    }
}
