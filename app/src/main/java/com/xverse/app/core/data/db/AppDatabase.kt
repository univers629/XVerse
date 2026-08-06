package com.xverse.app.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/** 枚举 ↔ 字符串 转换 */
class Converters {
    @TypeConverter
    fun downloadStatusToString(s: DownloadStatus): String = s.name

    @TypeConverter
    fun stringToDownloadStatus(s: String): DownloadStatus = DownloadStatus.valueOf(s)

    @TypeConverter
    fun ruleTypeToString(t: RuleType): String = t.name

    @TypeConverter
    fun stringToRuleType(s: String): RuleType = RuleType.valueOf(s)
}

@Database(
    entities = [HistoryRecord::class, DownloadTask::class, FilterRule::class],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun filterRuleDao(): FilterRuleDao

    companion object {
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE history ADD COLUMN displayName TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE history ADD COLUMN mediaUrl TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE downloads ADD COLUMN thumbPath TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE history ADD COLUMN thumbPath TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE downloads ADD COLUMN contentUri TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "xverse.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
