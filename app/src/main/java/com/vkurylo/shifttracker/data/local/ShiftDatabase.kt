package com.vkurylo.shifttracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [JobEntity::class, ShiftEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ShiftDatabase : RoomDatabase() {
    abstract fun jobDao(): JobDao
    abstract fun shiftDao(): ShiftDao

    companion object {
        const val NAME = "shift_tracker.db"
    }
}
