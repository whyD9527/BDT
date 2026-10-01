package com.imcys.bilibilias.datastore

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.google.protobuf.InvalidProtocolBufferException
import com.imcys.bilibilias.datastore.User
import java.io.InputStream
import java.io.OutputStream

/** 同样带损坏兜底（2026-09-15 复审 A-M4），见 `userAppSettingsStore` 的说明。 */
val Context.googlePlayerStore: DataStore<GooglePlaySettings> by dataStore(
    fileName = "google_play.pb",
    serializer = GooglePlayerSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { GooglePlayerSerializer.defaultValue },
)

object GooglePlayerSerializer : Serializer<GooglePlaySettings> {
    override val defaultValue: GooglePlaySettings = GooglePlaySettings.getDefaultInstance()
    override suspend fun readFrom(input: InputStream): GooglePlaySettings {
        try {
            return GooglePlaySettings.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(t: GooglePlaySettings, output: OutputStream) = t.writeTo(output)
}