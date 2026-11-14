package com.example.core.wallet

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore by preferencesDataStore(name = "gramvault_settings")

data class AppSettings(
  val autoLockOnBackground: Boolean,
  val liveMonitorEnabled: Boolean = true,
)

class AppSettingsStore(
  context: Context,
  private val defaultAutoLock: Boolean = true,
) {
  private val dataStore = context.applicationContext.appSettingsDataStore

  val settingsFlow: Flow<AppSettings> =
    dataStore.data.map { prefs: Preferences ->
      AppSettings(
        autoLockOnBackground = prefs[KEY_AUTO_LOCK_ON_BG] ?: defaultAutoLock,
        liveMonitorEnabled = prefs[KEY_LIVE_MONITOR_ENABLED] ?: true,
      )
    }

  suspend fun setAutoLockOnBackground(enabled: Boolean) {
    dataStore.edit { prefs -> prefs[KEY_AUTO_LOCK_ON_BG] = enabled }
  }

  suspend fun setLiveMonitorEnabled(enabled: Boolean) {
    dataStore.edit { prefs -> prefs[KEY_LIVE_MONITOR_ENABLED] = enabled }
  }

  companion object {
    private val KEY_AUTO_LOCK_ON_BG = booleanPreferencesKey("auto_lock_on_background")
    private val KEY_LIVE_MONITOR_ENABLED = booleanPreferencesKey("live_monitor_enabled")
  }
}
