package com.opicih.coach.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ScriptEntity::class, ScriptRevisionEntity::class, ImportSourceEntity::class, PracticeAttemptEntity::class,
        ErrorPatternEntity::class, ChunkEntity::class, DailyLogEntity::class, MockTestEntity::class,
        ExamEntity::class, StoryEntity::class, QuestionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "opic_ih_coach.db").build()
    }
}
