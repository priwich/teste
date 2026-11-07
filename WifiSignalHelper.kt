package com.camscan.app

object WifiSignalHelper {
    /** Converte RSSI (dBm, normalmente entre -100 e -30) para uma porcentagem aproximada de 0 a 100. */
    fun levelPercent(rssiDbm: Int): Int {
        val minRssi = -100
        val maxRssi = -30
        val clamped = rssiDbm.coerceIn(minRssi, maxRssi)
        return (((clamped - minRssi).toDouble() / (maxRssi - minRssi).toDouble()) * 100).toInt()
    }
}
