package local.files.explorer.core

/** Wording and arithmetic for "will this fit?" checks (pure, unit-tested). */
object SpaceCheck {
    fun message(what: String, needed: Long, free: Long) =
        "Not enough space to $what: needs ${Format.size(needed)}, ${Format.size(free.coerceAtLeast(0))} free."

    /** Null if [needed] (+[margin]) fits into [free]; otherwise the user-facing message. Unknown free space (null) passes. */
    fun check(what: String, needed: Long, free: Long?, margin: Long = 0): String? =
        if (free == null || needed <= 0 || needed + margin <= free) null else message(what, needed, free)
}
