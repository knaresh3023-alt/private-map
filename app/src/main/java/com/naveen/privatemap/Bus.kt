package com.naveen.privatemap

data class Drop(
    val area: String,
    val address: String,
    val dropKm: Double?,
    val pickupKm: Double?,
    val source: String
) {
    val key: String get() = "$source|$area|$address|$dropKm"
}

interface DropListener {
    fun onDrop(d: Drop)
    fun onPopupGone()
}

object Bus {
    @Volatile var listener: DropListener? = null
    @Volatile var resetFlag = false
}
