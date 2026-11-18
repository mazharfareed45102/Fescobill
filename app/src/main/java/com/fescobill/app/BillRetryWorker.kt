package com.fescobill.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Keeps trying to download a bill in the background (every 15 minutes, also when the app is
 * closed) while the FESCO website cannot be reached. As soon as it gets through, the complete bill
 * is saved on the phone and a notification says it is ready to open, print or share.
 */
class BillRetryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val digits = inputData.getString(KEY_DIGITS) ?: return Result.success()
        val query = RefNumber.parse(digits)
        if (query == null) {
            cancel(ctx, digits)
            return Result.success()
        }
        val since = inputData.getLong(KEY_SINCE, System.currentTimeMillis())
        if (System.currentTimeMillis() - since > GIVE_UP_MS) {
            Notifications.gaveUp(ctx, query)
            cancel(ctx, digits)
            return Result.success()
        }

        val fetcher = BillFetcher(
            userAgent = Prefs.userAgent(ctx),
            hedgeDelaysMs = longArrayOf(0, 5_000),
            maxAttempts = 4,
            overallTimeoutMs = 120_000,
            routes = NetworkRoutes(ctx)::routes,
        )
        when (val r = fetcher.fetchBlocking(query)) {
            is BillFetcher.Result.Bill -> {
                val month = PitcHtml.billMonth(r.html).orEmpty()
                val page = PageSnapshot.build(r.html, r.url, r.resources)
                BillCache(ctx).putPage(digits, page, r.url, month)
                SavedBills(ctx).markOpened(query)
                cancel(ctx, digits)
                Notifications.billReady(ctx, query, month)
            }
            is BillFetcher.Result.NotFound -> {
                cancel(ctx, digits)
                Notifications.notFound(ctx, query, r.message)
            }
            is BillFetcher.Result.Failed -> Unit // try again at the next run
        }
        return Result.success()
    }

    companion object {
        private const val KEY_DIGITS = "digits"
        private const val KEY_SINCE = "since"
        private const val GIVE_UP_MS = 3L * 24 * 60 * 60 * 1000

        private fun workName(digits: String) = "bill-retry-$digits"

        fun schedule(context: Context, q: BillQuery) {
            val request = PeriodicWorkRequestBuilder<BillRetryWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_DIGITS to q.digits, KEY_SINCE to System.currentTimeMillis()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(workName(q.digits), ExistingPeriodicWorkPolicy.KEEP, request)
            Prefs.setWaiting(context, q.digits, true)
        }

        fun cancel(context: Context, digits: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(digits))
            Prefs.setWaiting(context, digits, false)
        }

        fun liveData(context: Context, digits: String) =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(workName(digits))
    }
}
