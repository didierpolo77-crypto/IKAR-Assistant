package com.ikar.assistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ikar.assistant.security.PendingActionEntity

@Database(
    entities = [MemoryEntry::class, PendingActionEntity::class],
    version = 2,
    exportSchema = false
)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile private var INSTANCE: MemoryDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS pending_actions (
                        id TEXT NOT NULL PRIMARY KEY,
                        actionId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        explanation TEXT NOT NULL,
                        risk TEXT NOT NULL,
                        reversible INTEGER NOT NULL,
                        undoDescription TEXT,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL,
                        confirmedAt INTEGER,
                        completedAt INTEGER,
                        resultSummary TEXT
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_actions_status_expiresAt ON pending_actions(status, expiresAt)")
            }
        }

        fun getInstance(context: Context): MemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    MemoryCrypto.initialize(context.applicationContext)
                    Room.databaseBuilder(
                        context.applicationContext,
                        MemoryDatabase::class.java,
                        "ikar_local_memory.db"
                    )
                        .addMigrations(MIGRATION_1_2)
                        .addCallback(object : RoomDatabase.Callback() {
                            override fun onOpen(db: SupportSQLiteDatabase) {
                                super.onOpen(db)
                                // One-time in-place encryption of V1 plaintext rows.
                                db.beginTransaction()
                                try {
                                    db.query("SELECT id, content FROM memory_entries").use { cursor ->
                                        while (cursor.moveToNext()) {
                                            val id = cursor.getLong(0)
                                            val content = cursor.getString(1)
                                            if (!MemoryCrypto.isEncrypted(content)) {
                                                db.execSQL(
                                                    "UPDATE memory_entries SET content = ? WHERE id = ?",
                                                    arrayOf(MemoryCrypto.encrypt(content), id)
                                                )
                                            }
                                        }
                                    }
                                    db.setTransactionSuccessful()
                                } finally {
                                    db.endTransaction()
                                }
                            }
                        })
                        .build()
                        .also { INSTANCE = it }
                }
            }
        }
    }
}
