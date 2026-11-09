package com.tuki.sveta.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

/** All user settings as one JSON document; small enough that field-level keys buy nothing. */
class SettingsStore(context: Context, scope: CoroutineScope) {

    private val store = context.applicationContext.dataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("settings_json")

    private fun decode(prefs: Preferences): Settings =
        prefs[key]?.let { runCatching { json.decodeFromString(Settings.serializer(), it) }.getOrNull() } ?: Settings()

    /** Blocking first read keeps every consumer free of a "not loaded yet" state; the file is tiny. */
    val settings: StateFlow<Settings> = store.data.map(::decode)
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { decode(store.data.first()) })

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs ->
            prefs[key] = json.encodeToString(Settings.serializer(), transform(decode(prefs)))
        }
    }
}
