package com.sakura.player.follow

import android.content.Context
import com.sakura.player.data.SettingsPrefs
import com.sakura.player.scraper.DomainFinder
import kotlinx.coroutines.*

object UpdateChecker {
    private var checkJob: Job? = null

    fun schedule(ctx: Context, scope: CoroutineScope) {
        checkJob?.cancel()
        checkJob = scope.launch(Dispatchers.IO) {
            val domain = SettingsPrefs.activeDomain.ifEmpty {
                DomainFinder.findDomain().also { SettingsPrefs.activeDomain = it }
            }
            if (domain.isEmpty()) return@launch

            FollowManager.checkUpdates(domain) { videoId, newEps ->
                // Update will be picked up by frontend polling
            }
        }
    }

    fun cancelCheck() {
        checkJob?.cancel()
    }
}
