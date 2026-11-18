package com.ikar.assistant.data

import androidx.room.TypeConverter

class MemoryContentConverter {
    @TypeConverter
    fun toStored(value: String): String = MemoryCrypto.encrypt(value)

    @TypeConverter
    fun fromStored(value: String): String = MemoryCrypto.decrypt(value)
}
