package com.opicih.coach.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ScriptEntity::class, ScriptRevisionEntity::class, ImportSourceEntity::class, PracticeAttemptEntity::class,
        ErrorPatternEntity::class, ChunkEntity::class, DailyLogEntity::class, MockTestEntity::class,
        ExamEntity::class, StoryEntity::class, QuestionEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "opic_ih_coach.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v2: exam debrief columns. Keeps all existing learning data. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exams ADD COLUMN topicsSeen TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE exams ADD COLUMN stuckPoints TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE exams ADD COLUMN feeling INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE exams ADD COLUMN selfLevelUsed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE exams ADD COLUMN debriefAt INTEGER")
            }
        }
    }
}
