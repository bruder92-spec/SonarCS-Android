package com.sonar.android.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("sonar_settings")

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    val config: Flow<AppConfig> = store.data.map { p ->
        AppConfig(
            modelPath   = p[KEY_MODEL_PATH]   ?: "",
            dictOilGas  = p[KEY_DICT_OIL_GAS] ?: false,
            dictLegal   = p[KEY_DICT_LEGAL]   ?: false,
            dictEconomy = p[KEY_DICT_ECONOMY] ?: false
        )
    }

    suspend fun save(config: AppConfig) {
        store.edit { p ->
            p[KEY_MODEL_PATH]   = config.modelPath
            p[KEY_DICT_OIL_GAS] = config.dictOilGas
            p[KEY_DICT_LEGAL]   = config.dictLegal
            p[KEY_DICT_ECONOMY] = config.dictEconomy
        }
    }

    companion object {
        private val KEY_MODEL_PATH   = stringPreferencesKey("model_path")
        private val KEY_DICT_OIL_GAS = booleanPreferencesKey("dict_oil_gas")
        private val KEY_DICT_LEGAL   = booleanPreferencesKey("dict_legal")
        private val KEY_DICT_ECONOMY = booleanPreferencesKey("dict_economy")
    }
}
