package com.imcys.bilibilias.datastore

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream


/**
 * 见 `userAppSettingsStore` 的说明（2026-09-15 复审 A-M4）：损坏时必须能回落到默认值，
 * 否则每个读 `user.pb` 的收集者（当前登录用户 id 等）都会直接抛异常。
 */
val Context.userUserStore: DataStore<User> by dataStore(
    fileName = "user.pb",
    serializer = UserSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { UserSerializer.defaultValue },
)

object UserSerializer : Serializer<User> {
    override val defaultValue: User = User.getDefaultInstance()
    override suspend fun readFrom(input: InputStream): User {
        try {
            return User.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(t: User, output: OutputStream) = t.writeTo(output)
}