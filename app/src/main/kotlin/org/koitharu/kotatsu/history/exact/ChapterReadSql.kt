package org.koitharu.kotatsu.history.exact

/** Shared list predicates: explicit read flags and a resume percentage are different data. */
object ChapterReadSql {
    private const val ACTIVE = "EXISTS(SELECT 1 FROM chapter_read_policy ep WHERE ep.manga_id=manga.manga_id AND ep.source=manga.source AND ep.enabled=1 AND ep.deleted=0)"
    private const val COUNT_FROM = "FROM chapter_read_count ec JOIN chapter_read_policy ep ON ep.manga_id=ec.manga_id AND ep.progress_branch=ec.branch_key WHERE ec.manga_id=manga.manga_id AND ep.source=manga.source AND ep.enabled=1 AND ep.deleted=0"

    fun percent(legacy: String): String = "(CASE WHEN $ACTIVE THEN COALESCE((SELECT CASE WHEN ec.total_count>0 THEN 1.0*ec.read_count/ec.total_count ELSE 0 END $COUNT_FROM),0) ELSE ($legacy) END)"

    fun remaining(legacy: String): String = "(CASE WHEN $ACTIVE THEN COALESCE((SELECT MAX(0,ec.total_count-ec.read_count) $COUNT_FROM),0) ELSE ($legacy) END)"

    fun completed(legacy: String): String = "(CASE WHEN $ACTIVE THEN EXISTS(SELECT 1 $COUNT_FROM AND ec.total_count>0 AND ec.read_count>=ec.total_count) ELSE ($legacy) END)"

    fun unread(legacy: String): String = "(CASE WHEN $ACTIVE THEN COALESCE((SELECT ec.read_count $COUNT_FROM),0)=0 ELSE ($legacy) END)"

    fun reading(legacy: String): String = "(CASE WHEN $ACTIVE THEN EXISTS(SELECT 1 $COUNT_FROM AND ec.read_count>0 AND ec.read_count<ec.total_count) ELSE ($legacy) END)"
}
