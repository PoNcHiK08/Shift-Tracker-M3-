package com.vkurylo.shifttracker.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [JobEntity::class, ShiftEntity::class, BonusEntity::class, PayoutEntity::class, JobPaySettingsEntity::class],
    version = 8,
    exportSchema = true,
    autoMigrations = [
        // v2: optional second (timed) rate on jobs and shift snapshots.
        AutoMigration(from = 1, to = 2),
        // v3: bonuses table.
        AutoMigration(from = 2, to = 3),
        // v4: a currency per job (existing jobs: PLN).
        AutoMigration(from = 3, to = 4),
        // v5: pay settings history per job (no rows = hourly, as before).
        AutoMigration(from = 4, to = 5),
        // v6: deduction percentage in the pay settings history (0 = none).
        AutoMigration(from = 5, to = 6),
        // v7: manual, see MIGRATION_6_7.
        // v8: bonuses.whole_month (existing bonuses: false, a bonus on its day) and the empty payouts table.
        // Nothing else changes; no row is touched or marked as changed.
        AutoMigration(from = 7, to = 8),
    ],
)
abstract class ShiftDatabase : RoomDatabase() {
    abstract fun jobDao(): JobDao
    abstract fun shiftDao(): ShiftDao
    abstract fun bonusDao(): BonusDao
    abstract fun payoutDao(): PayoutDao
    abstract fun paySettingsDao(): PaySettingsDao
    abstract fun syncDao(): SyncDao

    companion object {
        const val NAME = "shift_tracker.db"

        /**
         * Tables that had sync metadata added by [MIGRATION_6_7]: the ones that existed at version 6. It must not
         * grow: `payouts` is created at version 8, already with its sync columns.
         */
        internal val SYNCED_TABLES = listOf("jobs", "shifts", "bonuses")

        /**
         * v7: sync metadata (`sync_id`, `updated_at`, `deleted`, `dirty`) on jobs, shifts and bonuses.
         *
         * Manual because every existing row needs its own random `sync_id` before the unique index on it
         * can be created, which an auto-migration can't do. It only adds columns: ids, `job_id` links and
         * every existing value stay exactly as they were. Existing rows count as changed now (so they
         * upload on the first sync) and as not deleted.
         */
        val MIGRATION_6_7: Migration = Migration6To7(WallClock.System)
    }
}

internal class Migration6To7(private val clock: WallClock) : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val now = clock.nowMillis()
        for (table in ShiftDatabase.SYNCED_TABLES) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `sync_id` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 1")
            val ids = ArrayList<Long>()
            db.query("SELECT `id` FROM `$table`").use { cursor -> while (cursor.moveToNext()) ids += cursor.getLong(0) }
            for (id in ids) {
                db.execSQL("UPDATE `$table` SET `sync_id` = ?, `updated_at` = ? WHERE `id` = ?", arrayOf<Any>(newSyncId(), now, id))
            }
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_sync_id` ON `$table` (`sync_id`)")
        }
    }
}
