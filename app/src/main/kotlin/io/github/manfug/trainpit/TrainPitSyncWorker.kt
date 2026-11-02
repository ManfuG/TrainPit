package io.github.manfug.trainpit

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.manfug.trainpit.data.RoomLocalRepository
import io.github.manfug.trainpit.sync.FirebaseGateway
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

class TrainPitSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString("uid") ?: return Result.failure()
        val gateway = FirebaseGateway()
        if (gateway.auth.currentUser?.uid != uid) return Result.success()
        val profile = RoomLocalRepository.cachedProfile(applicationContext, uid) ?: return Result.success()
        if (gateway.auth.currentUser?.uid != uid) return Result.success()
        val local = RoomLocalRepository.open(applicationContext, profile)
        return try {
            gateway.synchronize(local)
            Result.success()
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) Result.retry() else throw cancelled
        } catch (_: Exception) {
            // The transactional outbox/cursors preserve progress; no private payload is logged.
            Result.retry()
        } finally {
            local.close()
        }
    }

    companion object {
        fun schedule(context: Context, uid: String) {
            val request = PeriodicWorkRequestBuilder<TrainPitSyncWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
                .setInputData(Data.Builder().putString("uid", uid).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("trainpit-sync-$uid", ExistingPeriodicWorkPolicy.KEEP, request)
        }
        fun cancel(context: Context, uid: String) { WorkManager.getInstance(context).cancelUniqueWork("trainpit-sync-$uid") }
    }
}
