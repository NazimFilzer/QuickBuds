package com.spizganed.quickbuds.bluetooth

/** A requested switch value is not firmware state until a read-back agrees. */
internal class ConfirmedToggle {
    data class Snapshot(val confirmed: Boolean?, val requested: Boolean?) {
        val displayed: Boolean get() = requested ?: confirmed ?: false
        val pending: Boolean get() = requested != null
    }
    private var confirmed: Boolean? = null
    private var requested: Boolean? = null
    private var revision = 0L

    @Synchronized fun snapshot() = Snapshot(confirmed, requested)
    @Synchronized fun request(value: Boolean): Long? {
        if (confirmed == null || requested != null) return null
        requested = value
        return ++revision
    }
    @Synchronized fun confirm(value: Boolean) {
        confirmed = value
        if (requested == value) requested = null
    }
    @Synchronized fun expire(token: Long): Boolean {
        if (revision != token || requested == null) return false
        requested = null
        return true
    }
    @Synchronized fun reset() {
        confirmed = null
        requested = null
        revision++
    }
}
