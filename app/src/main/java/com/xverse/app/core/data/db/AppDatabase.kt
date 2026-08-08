package com.xverse.app.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.xverse.app.core.extensions.ExtensionDao
import com.xverse.app.core.extensions.ExtensionEntity

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
    entities = [HistoryRecord::class, DownloadTask::class, FilterRule::class, ExtensionEntity::class],
    version = 9,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun filterRuleDao(): FilterRuleDao
    abstract fun extensionDao(): ExtensionDao

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

        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 扩展表：纯新增，历史数据无需改写
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS extensions (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "version TEXT NOT NULL, " +
                        "manifestVersion INTEGER NOT NULL, " +
                        "description TEXT NOT NULL DEFAULT '', " +
                        "enabled INTEGER NOT NULL DEFAULT 1, " +
                        "optionsPage TEXT NOT NULL DEFAULT '', " +
                        "iconPath TEXT NOT NULL DEFAULT '', " +
                        "contentScriptsJson TEXT NOT NULL DEFAULT '[]', " +
                        "permissionsJson TEXT NOT NULL DEFAULT '[]', " +
                        "homepageUrl TEXT NOT NULL DEFAULT '', " +
                        "author TEXT NOT NULL DEFAULT '', " +
                        "installedAt INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_extensions_enabled ON extensions(enabled)")
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 扩展来源列：存量导入全部来自 Chrome 商店（用户脚本走 manifestVersion==0 区分）
                db.execSQL(
                    "ALTER TABLE extensions ADD COLUMN source TEXT NOT NULL DEFAULT 'CHROME'"
                )
            }
        }

        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 存量修正：manifestVersion==0 的本来就是用户脚本（6→7 时误标成 CHROME）
                db.execSQL(
                    "UPDATE extensions SET source='USERSCRIPT' WHERE manifestVersion=0"
                )
            }
        }

        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 下载任务媒体类型列（photo/video/gif，列表格式徽标用）；存量任务回填空串，
                // UI 侧根据 format/文件名兜底显示
                db.execSQL(
                    "ALTER TABLE downloads ADD COLUMN mediaType TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "xverse.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
