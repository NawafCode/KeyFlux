package com.keyflux

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class SettingsProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.keyflux.provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/settings")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val context = context ?: return null
        val sp = context.getSharedPreferences("keyflux_shared_prefs", Context.MODE_PRIVATE)
        val cursor = MatrixCursor(arrayOf("key", "value", "type"))
        for ((key, value) in sp.all) {
            if (key.endsWith("_type")) continue
            if (value != null) {
                val decryptedValue: String
                val type: String
                if (value is String) {
                    decryptedValue = CryptoHelper.decrypt(value)
                    type = sp.getString(key + "_type", null) ?: when {
                        key == "keyflux_clip_days" || key == "keyflux_clip_size" -> "int"
                        else -> "boolean"
                    }
                } else {
                    // Legacy plaintext value
                    decryptedValue = value.toString()
                    type = when (value) {
                        is Boolean -> "boolean"
                        is Int -> "int"
                        is Long -> "long"
                        is Float -> "float"
                        else -> "string"
                    }
                }
                cursor.addRow(arrayOf(key, decryptedValue, type))
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.dir/vnd.com.keyflux.settings"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val context = context ?: return null
        val sp = context.getSharedPreferences("keyflux_shared_prefs", Context.MODE_PRIVATE)
        values?.let {
            val key = it.getAsString("key") ?: return null
            val value = it.getAsString("value") ?: return null
            val type = it.getAsString("type") ?: "string"
            val encryptedVal = CryptoHelper.encrypt(value)
            sp.edit().apply {
                putString(key, encryptedVal)
                putString(key + "_type", type)
                apply()
            }
            try {
                context.contentResolver.notifyChange(uri, null)
            } catch (e: Exception) {
                // ignore if not permitted
            }
        }
        return uri
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val context = context ?: return 0
        val sp = context.getSharedPreferences("keyflux_shared_prefs", Context.MODE_PRIVATE)
        sp.edit().clear().apply()
        try {
            context.contentResolver.notifyChange(uri, null)
        } catch (e: Exception) {}
        return 1
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        insert(uri, values)
        return 1
    }
}
