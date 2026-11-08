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
        ExamRegistrationEntity::class, ExamStatusEventEntity::class, ExamTrainingSessionEntity::class,
        WordLookupEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "opic_ih_coach.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

        /** v2: exam debrief columns. Keeps all existing learning data. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MigrationSql.V1_TO_V2.forEach(db::execSQL)
            }
        }

        /** v3: multi-exam schedule tables. Adds only; every v2 row is kept. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MigrationSql.V2_TO_V3.forEach(db::execSQL)
            }
        }

        /** v4: script glossary lookups. Adds one table; every v3 row is kept. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MigrationSql.V3_TO_V4.forEach(db::execSQL)
            }
        }
    }
}
