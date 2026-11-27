package app.skladfusion

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import app.skladfusion.data.Repo
import java.util.concurrent.TimeUnit

class App : Application() {

    lateinit var repo: Repo
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        Server.init(this)
        repo = Repo(this)
        // Задания: после синхронизации — уведомление о новом и докачка образцов для работы без сети.
        repo.board.afterSync = {
            BoardNotify.check(this)
            repo.board.prefetch()
        }
        BoardNotify.schedule(this)

        // Пока приложение на экране — синхронизация каждые 20 секунд (новые заказы, кто что взял).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                loop = scope.launch {
                    while (isActive) {
                        runCatching { repo.syncNow() }
                        runCatching { repo.board.sync() }
                        Updater.check()
                        delay(20_000)
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                loop?.cancel()
                repo.kick() // успеть отправить последнее
                repo.board.kick()
            }
        })

        // В фоне — раз в 15 минут, только при наличии сети: дошлёт очередь, если телефон убрали в карман без связи.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as App).repo.syncNow()
        return Result.success()
    }
}

val Context.repo: Repo get() = (applicationContext as App).repo
