package dev.sparkynox.sparkytube.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            UpdateManager.backgroundCheck(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
