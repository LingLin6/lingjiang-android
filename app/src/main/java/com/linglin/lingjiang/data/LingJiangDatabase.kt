package com.linglin.lingjiang.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SessionEntity::class,
        TranscriptSegmentEntity::class,
        RealtimeInsightEntity::class,
        ResearchTaskEntity::class,
        ActionItemEntity::class,
        RiskEntity::class,
        DecisionEntity::class,
        FinalReportEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class LingJiangDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        fun create(context: Context): LingJiangDatabase = Room.databaseBuilder(
            context,
            LingJiangDatabase::class.java,
            "lingjiang.db",
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN speakerLabel TEXT NOT NULL DEFAULT '说话人待确认'")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN speakerConfidence REAL")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN turnIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN intentLabel TEXT NOT NULL DEFAULT '未判断'")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN intentConfidence REAL")
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN intentEvidence TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `research_tasks` (
                        `id` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `query` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `sources` TEXT NOT NULL,
                        `errorMessage` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_research_tasks_sessionId` ON `research_tasks` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_research_tasks_createdAt` ON `research_tasks` (`createdAt`)")
            }
        }
    }
}
