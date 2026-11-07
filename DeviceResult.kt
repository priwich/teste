package com.camscan.app

data class DeviceResult(
    val ip: String,
    var openPorts: MutableList<Int> = mutableListOf(),
    var rtspPort: Int? = null,
    var onvifInfo: String? = null,
    var onvifDiscoveryInfo: String? = null,
    var httpFindings: MutableList<String> = mutableListOf(),
    var reasons: MutableList<String> = mutableListOf(),
    var score: Int = 0,
    var isDvr: Boolean = false,
    var dvrBrand: String? = null
) {
    fun level(): String = when {
        score >= 4 -> "ALTO"
        score >= 2 -> "MEDIO"
        score >= 1 -> "BAIXO"
        else -> "-"
    }

    fun addReason(points: Int, text: String) {
        score += points
        reasons.add(text)
    }
}

data class WifiFinding(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val reasons: MutableList<String> = mutableListOf()
)
