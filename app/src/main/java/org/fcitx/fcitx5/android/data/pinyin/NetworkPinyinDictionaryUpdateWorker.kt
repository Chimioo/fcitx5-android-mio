/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.fcitx.fcitx5.android.core.reloadPinyinDict
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import timber.log.Timber
import java.util.concurrent.TimeUnit

class NetworkPinyinDictionaryUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val updated = PinyinDictManager.syncDueNetworkDictionaries(
            includeWifiOnlyDictionaries = PinyinDictManager.canUpdateWifiOnlyDictionaries(
                applicationContext
            )
        )
            .getOrElse {
                Timber.w(it, "Failed to update network pinyin dictionaries in background")
                return Result.retry()
            }
        if (updated.isNotEmpty()) {
            FcitxDaemon.getFirstConnectionOrNull()?.runIfReady {
                reloadPinyinDict()
            }
        }
        NetworkPinyinDictionaryUpdateScheduler.updateSchedule(applicationContext)
        return Result.success()
    }
}

object NetworkPinyinDictionaryUpdateScheduler {

    fun updateSchedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val subscriptions = PinyinDictManager.listNetworkDictionaries()
        if (subscriptions.isEmpty()) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val networkType = if (subscriptions.all { it.wifiOnly }) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        val request = PeriodicWorkRequestBuilder<NetworkPinyinDictionaryUpdateWorker>(
            1L,
            TimeUnit.DAYS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(networkType)
                    .build()
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private const val WORK_NAME = "network_pinyin_dictionary_update"
}
