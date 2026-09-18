package org.koitharu.kotatsu.core.parser.mihon

/** Shared by live source results and backup imports; never change existing identities. */
internal fun mihonStableId(source: String, value: String): Long {
    var hash = 1125899906842597L
    source.forEach { hash = 31 * hash + it.code }
    value.forEach { hash = 31 * hash + it.code }
    return hash
}
