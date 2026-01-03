package com.example.nightguard

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Rejestracja DataStore jako właściwość rozszerzająca Context
val Context.dataStore by preferencesDataStore(name = "settings")

class DataStorage(private val context: Context) {
    private val gson = Gson()

    companion object {
        // Klucze do zapisywania danych
        val THEME_KEY = intPreferencesKey("theme_mode") // 0: System, 1: Light, 2: Dark
        val CONTACTS_KEY = stringPreferencesKey("trusted_contacts")
    }

    // --- LOGIKA MOTYWU ---
    // Pobiera strumień danych o wybranym motywie
    val themeFlow: Flow<Int> = context.dataStore.data.map { it[THEME_KEY] ?: 0 }

    suspend fun saveTheme(mode: Int) {
        context.dataStore.edit { it[THEME_KEY] = mode }
    }

    // --- LOGIKA KONTAKTÓW ---
    // Pobiera strumień listy kontaktów (deserializacja z JSON)
    val contactsFlow: Flow<List<TrustedContact>> = context.dataStore.data.map { pref ->
        val json = pref[CONTACTS_KEY] ?: return@map emptyList()
        val type = object : TypeToken<List<TrustedContact>>() {}.type
        gson.fromJson(json, type)
    }

    suspend fun saveContacts(contacts: List<TrustedContact>) {
        val json = gson.toJson(contacts)
        context.dataStore.edit { it[CONTACTS_KEY] = json }
    }
}