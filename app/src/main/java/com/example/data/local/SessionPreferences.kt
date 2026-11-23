package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AppThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "linkdraw_prefs")

data class AppUserPreferences(
  val activeUserId: String? = null,
  val activeProjectId: String? = null,
  val themeMode: AppThemeMode = AppThemeMode.LIGHT,
  val gridSnap: Boolean = true,
  val gridSize: Int = 24,
  val defaultFlowAnimation: Boolean = false,
)

class SessionPreferences(private val context: Context) {

  private object Keys {
    val ACTIVE_USER_ID = stringPreferencesKey("active_user_id")
    val ACTIVE_PROJECT_ID = stringPreferencesKey("active_project_id")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val GRID_SNAP = booleanPreferencesKey("grid_snap")
    val GRID_SIZE = intPreferencesKey("grid_size")
    val FLOW_ANIM = booleanPreferencesKey("flow_anim")
  }

  val preferences: Flow<AppUserPreferences> =
    context.dataStore.data.map { prefs ->
      AppUserPreferences(
        activeUserId = prefs[Keys.ACTIVE_USER_ID]?.takeIf { it.isNotBlank() },
        activeProjectId = prefs[Keys.ACTIVE_PROJECT_ID]?.takeIf { it.isNotBlank() },
        themeMode =
          runCatching { AppThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: AppThemeMode.LIGHT.name) }
            .getOrDefault(AppThemeMode.LIGHT),
        gridSnap = prefs[Keys.GRID_SNAP] ?: true,
        gridSize = (prefs[Keys.GRID_SIZE] ?: 24).coerceIn(8, 96),
        defaultFlowAnimation = prefs[Keys.FLOW_ANIM] ?: false,
      )
    }

  suspend fun setActiveUser(userId: String?) {
    context.dataStore.edit { prefs ->
      if (userId == null) {
        prefs.remove(Keys.ACTIVE_USER_ID)
        prefs.remove(Keys.ACTIVE_PROJECT_ID)
      } else {
        prefs[Keys.ACTIVE_USER_ID] = userId
      }
    }
  }

  suspend fun setActiveProject(projectId: String?) {
    context.dataStore.edit { prefs ->
      if (projectId == null) prefs.remove(Keys.ACTIVE_PROJECT_ID)
      else prefs[Keys.ACTIVE_PROJECT_ID] = projectId
    }
  }

  suspend fun setThemeMode(mode: AppThemeMode) {
    context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
  }

  suspend fun setGridSnap(enabled: Boolean) {
    context.dataStore.edit { it[Keys.GRID_SNAP] = enabled }
  }

  suspend fun setGridSize(size: Int) {
    context.dataStore.edit { it[Keys.GRID_SIZE] = size.coerceIn(8, 96) }
  }

  suspend fun setFlowAnimation(enabled: Boolean) {
    context.dataStore.edit { it[Keys.FLOW_ANIM] = enabled }
  }
}
