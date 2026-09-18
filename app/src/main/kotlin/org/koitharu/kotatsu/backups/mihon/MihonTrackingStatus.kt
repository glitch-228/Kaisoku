package org.koitharu.kotatsu.backups.mihon

// Mihon stores service-specific status numbers; Kaisoku stores each service's wire name.
internal fun mihonTrackingStatus(service: Int, status: Int): String? = when (service) {
    1 -> when (status) {
        1, 7 -> "reading"
        2 -> "completed"
        3 -> "on_hold"
        4 -> "dropped"
        6 -> "plan_to_read"
        else -> null
    }
    2 -> when (status) {
        1 -> "CURRENT"
        2 -> "COMPLETED"
        3 -> "PAUSED"
        4 -> "DROPPED"
        5 -> "PLANNING"
        6 -> "REPEATING"
        else -> null
    }
    3 -> when (status) {
        1 -> "current"
        2 -> "completed"
        3 -> "on_hold"
        4 -> "dropped"
        5 -> "planned"
        else -> null
    }
    4 -> when (status) {
        1 -> "watching"
        2 -> "completed"
        3 -> "on_hold"
        4 -> "dropped"
        5 -> "planned"
        6 -> "rewatching"
        else -> null
    }
    else -> null
}
