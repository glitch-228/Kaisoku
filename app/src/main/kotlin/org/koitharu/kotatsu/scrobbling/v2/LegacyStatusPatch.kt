package org.koitharu.kotatsu.scrobbling.v2

import org.json.JSONObject
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

/** Provider status representations, without progress/rating/notes/date fields. */
object LegacyStatusPatch {
    fun statuses(service: ScrobblerService): Set<String> = when (service) {
        ScrobblerService.ANILIST -> setOf("CURRENT", "COMPLETED", "PAUSED", "DROPPED", "PLANNING", "REPEATING")
        ScrobblerService.MAL -> setOf("reading", "completed", "on_hold", "dropped", "plan_to_read", "rereading")
        ScrobblerService.KITSU -> setOf("current", "completed", "on_hold", "dropped", "planned", "reconsuming")
        ScrobblerService.SHIKIMORI -> setOf("planned", "watching", "completed", "on_hold", "dropped", "rewatching")
        ScrobblerService.MANGABAKA -> setOf("reading", "completed", "paused", "dropped", "plan_to_read", "rereading")
    }

    fun fields(service: ScrobblerService, status: String): Map<String, Any> {
        require(status in statuses(service)) { "Unsupported tracker status" }
        return when (service) {
            ScrobblerService.MAL -> mapOf("status" to if (status == "rereading") "reading" else status,
                "is_rereading" to (status == "rereading"))
            ScrobblerService.KITSU -> mapOf("status" to if (status == "reconsuming") "current" else status,
                "reconsuming" to (status == "reconsuming"))
            ScrobblerService.MANGABAKA -> mapOf("state" to status)
            else -> mapOf("status" to status)
        }
    }

    fun json(service: ScrobblerService, status: String) = JSONObject(fields(service, status))
}
