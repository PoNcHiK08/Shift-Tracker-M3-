package com.vkurylo.shifttracker.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [JobEntity::class, ShiftEntity::class, BonusEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        // v2: optional second (timed) rate on jobs and shift snapshots.
        AutoMigration(from = 1, to = 2),
        // v3: bonuses table.
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class ShiftDatabase : RoomDatabase() {
    abstract fun jobDao(): JobDao
    abstract fun shiftDao(): ShiftDao
    abstract fun bonusDao(): BonusDao

    companion object {
        const val NAME = "shift_tracker.db"
    }
}
