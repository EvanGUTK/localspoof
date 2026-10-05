package dev.localspoof.android

internal data class MobileVehicleUi(val year: String = "", val make: String = "", val model: String = "",
    val id: String = "", val field: String = "year", val choices: List<Pair<String, String>> = emptyList(),
    val busy: Boolean = false, val error: String? = null, val referenceMessage: String? = null)
