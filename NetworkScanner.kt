package com.camscan.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.util.UUID

object NetworkScanner {

    // Portas tipicas de cameras / DVR / NVR (mesmas usadas na versao PowerShell)
    val CAMERA_PORTS = listOf(80, 81, 443, 554, 555, 2020, 5000, 7070, 8000, 8001, 8080, 8081, 8090, 8554, 8888, 8899, 9000, 10554, 37777, 34567, 1935, 6668)
    val VIDEO_PORTS = setOf(554, 555, 8554, 10554, 8899, 37777, 34567, 1935, 7070)
    val WEB_PORTS = setOf(80, 81, 8000, 8001, 8080, 8081, 8090, 8888, 9000)
    val ONVIF_PORTS = setOf(5000, 80, 8000, 8080, 8081, 8899, 2020)

    private val RE_WEBCAM = Regex("(?i)(camera|ipcam|webcam|\\bdvr\\b|\\bnvr\\b|hikvision|dahua|onvif|netsurveillance|surveillance|video server|reolink|foscam|intelbras|ezviz|tapo|mjpg|liveview|live view|ipc\\b)")
    private val RE_EMBEDDED = Regex("(?i)(app-webs|uc-httpd|dnvrs-webs|hikvision-webs|goahead|boa/|thttpd|mini_httpd|jaws|ipcam|webs)")
    private val RE_WIFI_STRONG = Regex("(?i)(ipcam|ip-cam|ip_cam|camera|wificam|wifi-cam|hdcam|spcam|bvcam|ppcs|yyp2p|ycc365|minicam|mini-cam|spycam|ai-thinker|esp32-cam|esp-cam|hikvision|dahua|reolink|ezviz|imou|wyze|chuangmi|isa-camera|xiaomi-camera|mijia|imilab|miap|yoosee|icsee|gw_ap|gw-ap)")
    private val RE_WIFI_START = Regex("(?i)^(ipc|ipcam|cam|mv|a9|q9|dvr|nvr|esp|esp32|vr|dcam|hdcam|gw[-_]ap|gw)[-_ ]?[0-9a-f]{4,}$")

    // Prefixos de MAC/BSSID de fabricantes conhecidos de cameras/IoT (offline, sem precisar de internet)
    private val OUI: Map<String, List<String>> = mapOf(
        "Hikvision" to listOf("44:19:B6", "4C:BD:8F", "BC:AD:28", "C0:56:E3", "28:57:BE", "54:C4:15", "A4:14:37", "18:68:CB", "24:0F:9B", "C4:2F:90", "BC:BA:C2"),
        "Dahua" to listOf("3C:EF:8C", "4C:11:BF", "90:02:A9", "E0:50:8B", "9C:14:63", "38:AF:29", "A0:BD:1D", "14:A7:8B", "BC:32:5F"),
        "Amcrest" to listOf("9C:8E:CD"),
        "Yoosee (Gwelltimes)" to listOf("4C:B0:08"),
        "Wyze" to listOf("2C:AA:8E", "D0:3F:27"),
        "Axis" to listOf("00:40:8C", "AC:CC:8E", "B8:A4:4F"),
        "Reolink" to listOf("EC:71:DB", "9C:8E:99"),
        "Espressif (ESP32-CAM / IoT)" to listOf("24:6F:28", "30:AE:A4", "84:0D:8E", "A4:CF:12", "24:0A:C4", "7C:9E:BD", "3C:71:BF", "B4:E6:2D", "8C:AA:B5", "10:52:1C", "EC:FA:BC", "CC:50:E3", "68:C6:3A")
    )

    data class NetInfo(val myIp: String, val prefix: Int, val gateway: String?)

    /** Descobre IP local, prefixo da sub-rede e gateway via Wi-Fi atual. */
    fun getNetInfo(context: Context): NetInfo? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val linkProps = cm.getLinkProperties(network) ?: return null
        val linkAddr: LinkAddress = linkProps.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val ip = linkAddr.address.hostAddress ?: return null
        val prefix = linkAddr.prefixLength
        val gateway = linkProps.routes.firstOrNull { it.gateway != null && it.gateway is Inet4Address }?.gateway?.hostAddress
        return NetInfo(ip, prefix, gateway)
    }

    private fun ipToLong(ip: String): Long {
        val b = InetAddress.getByName(ip).address
        var result = 0L
        for (x in b) result = (result shl 8) or (x.toLong() and 0xFF)
        return result
    }

    private fun longToIp(n: Long): String {
        val buf = ByteBuffer.allocate(4)
        buf.put(((n shr 24) and 0xFF).toByte())
        buf.put(((n shr 16) and 0xFF).toByte())
        buf.put(((n shr 8) and 0xFF).toByte())
        buf.put((n and 0xFF).toByte())
        return InetAddress.getByAddress(buf.array()).hostAddress ?: ""
    }

    /** Monta a lista de IPs a varrer. Limita a faixa a no maximo um /24 (256 enderecos) para caber no tempo de um celular. */
    fun hostsInRange(myIp: String, prefix: Int): List<String> {
        val effPrefix = if (prefix in 24..30) prefix else 24
        val size = 1L shl (32 - effPrefix)
        val myLong = ipToLong(myIp)
        val net = (myLong / size) * size
        val result = mutableListOf<String>()
        for (i in 1 until (size - 1)) {
            val candidate = longToIp(net + i)
            if (candidate != myIp) result.add(candidate)
        }
        return result
    }

    private suspend fun tryConnect(ip: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Varre as portas de camera/DVR em todos os IPs do range, com concorrencia limitada. */
    suspend fun scanPorts(
        ips: List<String>,
        ports: List<Int>,
        timeoutMs: Int = 300,
        maxConcurrency: Int = 96,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Map<String, MutableList<Int>> = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(maxConcurrency)
        val openPorts = java.util.concurrent.ConcurrentHashMap<String, MutableList<Int>>()
        var done = 0
        val total = ips.size * ports.size
        val jobs = ips.flatMap { ip -> ports.map { port -> ip to port } }.map { (ip, port) ->
            async {
                semaphore.withPermit {
                    val open = tryConnect(ip, port, timeoutMs)
                    synchronized(this@NetworkScanner) {
                        done += 1
                        onProgress(done, total)
                    }
                    if (open) {
                        openPorts.getOrPut(ip) { mutableListOf() }.add(port)
                    }
                }
            }
        }
        jobs.awaitAll()
        openPorts
    }

    /** Testa se a porta responde ao protocolo RTSP (OPTIONS). */
    suspend fun probeRtsp(ip: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), 1200)
                s.soTimeout = 1500
                val msg = "OPTIONS rtsp://$ip:$port/ RTSP/1.0\r\nCSeq: 1\r\n\r\n"
                s.getOutputStream().write(msg.toByteArray())
                s.getOutputStream().flush()
                val buf = ByteArray(600)
                val n = s.getInputStream().read(buf)
                if (n > 0) {
                    val text = String(buf, 0, n)
                    return@withContext text.startsWith("RTSP/")
                }
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Faz um GET simples e procura assinaturas de camera/DVR/NVR no cabecalho Server e no corpo da pagina. */
    suspend fun probeHttp(ip: String, port: Int): List<String> = withContext(Dispatchers.IO) {
        val findings = mutableListOf<String>()
        try {
            val url = URL("http://$ip:$port/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            val server = conn.getHeaderField("Server") ?: ""
            val auth = conn.getHeaderField("WWW-Authenticate") ?: ""
            var body = ""
            try {
                val stream = if (conn.responseCode in 200..399) conn.inputStream else conn.errorStream
                if (stream != null) {
                    body = BufferedReader(InputStreamReader(stream)).readText().take(6000)
                }
            } catch (_: Exception) {
            }
            val combined = "$server $auth $body"
            RE_WEBCAM.find(combined)?.let { findings.add("Pagina web da porta $port menciona camera/DVR/NVR (${it.value})") }
            if (RE_EMBEDDED.containsMatchIn(server)) findings.add("Servidor web embarcado tipico de camera/IoT na porta $port: $server")
            conn.disconnect()
        } catch (e: Exception) {
            // porta fechou ou nao e HTTP - ignora
        }
        findings
    }

    /** Pergunta via ONVIF (SOAP GetDeviceInformation) o fabricante/modelo do dispositivo. */
    suspend fun probeOnvifDeviceInfo(ip: String, port: Int): String? = withContext(Dispatchers.IO) {
        try {
            val soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Body><GetDeviceInformation xmlns=\"http://www.onvif.org/ver10/device/wsdl\"/></s:Body></s:Envelope>"
            val url = URL("http://$ip:$port/onvif/device_service")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/soap+xml")
            conn.outputStream.write(soap.toByteArray())
            val stream = if (conn.responseCode in 200..399) conn.inputStream else conn.errorStream
            val xml = stream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: ""
            conn.disconnect()
            val mf = Regex("(?is)<[^/>][^>]*Manufacturer[^>]*>([^<]*)<").find(xml)?.groupValues?.get(1)?.trim() ?: ""
            val md = Regex("(?is)<[^/>][^>]*Model[^>]*>([^<]*)<").find(xml)?.groupValues?.get(1)?.trim() ?: ""
            val combined = "$mf $md".trim()
            if (combined.isNotEmpty()) combined else null
        } catch (e: Exception) {
            null
        }
    }

    /** Descoberta ONVIF WS-Discovery via multicast UDP: cameras IP costumam responder mesmo sem senha. */
    suspend fun onvifWsDiscovery(timeoutMs: Int = 4000): Map<String, String> = withContext(Dispatchers.IO) {
        val found = mutableMapOf<String, String>()
        var socket: DatagramSocket? = null
        try {
            val probe = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" " +
                "xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" " +
                "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" " +
                "xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">" +
                "<e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID>" +
                "<w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>" +
                "<w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>" +
                "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"
            socket = DatagramSocket()
            socket.soTimeout = 600
            val bytes = probe.toByteArray()
            val target = InetAddress.getByName("239.255.255.250")
            socket.send(DatagramPacket(bytes, bytes.size, target, 3702))
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    val info = Regex("(?i)onvif://www\\.onvif\\.org/(name|hardware)/([^\\s<]+)")
                        .findAll(text).map { java.net.URLDecoder.decode(it.groupValues[2], "UTF-8") }.joinToString(" / ")
                    found[packet.address.hostAddress ?: ""] = info
                } catch (e: Exception) {
                    // timeout de leitura, continua ate acabar o prazo
                }
            }
        } catch (e: Exception) {
            // rede nao suporta multicast ou foi bloqueada
        } finally {
            socket?.close()
        }
        found
    }

    /** Aplica a mesma pontuacao da versao desktop: ALTO/MEDIO/BAIXO, e marca DVR/NVR com a marca provavel. */
    fun classify(
        ip: String,
        openPorts: List<Int>,
        rtspOpen: Boolean,
        rtspPort: Int?,
        onvifInfo: String?,
        onvifDiscovery: String?,
        httpFindings: List<String>
    ): DeviceResult {
        val r = DeviceResult(ip = ip, openPorts = openPorts.toMutableList())
        val videoPortsOpen = openPorts.filter { VIDEO_PORTS.contains(it) }
        if (videoPortsOpen.isNotEmpty()) r.addReason(2, "Porta(s) tipica(s) de video aberta(s): ${videoPortsOpen.joinToString(",")}")
        if (openPorts.contains(6668)) r.addReason(1, "Porta 6668 aberta (Tuya / Smart Life)")
        if (rtspOpen && rtspPort != null) {
            r.rtspPort = rtspPort
            r.addReason(5, "Responde ao protocolo RTSP na porta $rtspPort (transmite video)")
        }
        var isNvr = false
        if (onvifInfo != null) {
            r.onvifInfo = onvifInfo
            r.addReason(5, "ONVIF informou fabricante/modelo: $onvifInfo")
            if (Regex("(?i)\\b(nvr|dvr|recorder)\\b").containsMatchIn(onvifInfo)) isNvr = true
        }
        if (openPorts.contains(5000) && openPorts.contains(554)) {
            r.addReason(2, "Portas 554 (RTSP) + 5000 (ONVIF) abertas: combinacao tipica das cameras Yoosee / iCSee (Gwelltimes)")
        }
        if (openPorts.contains(8000)) {
            r.addReason(2, "Porta 8000 aberta: porta de controle SADP tipica de DVR/NVR Hikvision (ou camera clone)")
            isNvr = true
            if (r.dvrBrand == null) r.dvrBrand = "Hikvision (ou clone)"
        }
        if (openPorts.contains(37777)) {
            r.addReason(2, "Porta 37777 aberta: porta de controle tipica de DVR/NVR Dahua (ou camera/marca clone, ex. Intelbras)")
            isNvr = true
            if (r.dvrBrand == null) r.dvrBrand = "Dahua / Intelbras (ou clone)"
        }
        if (openPorts.contains(34567) && openPorts.contains(8899)) {
            r.addReason(2, "Portas 34567 (controle) + 8899 (ONVIF): combinacao tipica de DVR/NVR/camera generica com app XMEye")
            isNvr = true
            if (r.dvrBrand == null) r.dvrBrand = "XMEye / generico"
        } else if (openPorts.contains(34567)) {
            r.addReason(1, "Porta 34567 aberta: porta de controle tipica de DVR/NVR/camera generica (app XMEye)")
            isNvr = true
            if (r.dvrBrand == null) r.dvrBrand = "XMEye / generico"
        }
        if (isNvr) {
            r.isDvr = true
            if (r.dvrBrand == null) r.dvrBrand = onvifInfo
            r.addReason(3, "Indicios de NVR/DVR (gravador de varias cameras), nao de uma camera isolada")
        }
        if (onvifDiscovery != null) {
            r.onvifDiscoveryInfo = onvifDiscovery
            r.addReason(5, "Respondeu a descoberta ONVIF (camera IP): $onvifDiscovery")
        }
        for (f in httpFindings) {
            r.httpFindings.add(f)
            r.addReason(2, f)
        }
        return r
    }

    /** Escaneia redes Wi-Fi proximas procurando hotspots proprios de camera (ex.: IPC-XXXX) e BSSID de fabricante. */
    fun scanWifi(context: Context): List<WifiFinding> {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val results = try {
            wifiManager.scanResults
        } catch (e: SecurityException) {
            emptyList()
        }
        val findings = mutableListOf<WifiFinding>()
        for (res in results) {
            val ssid = res.SSID ?: ""
            val bssid = res.BSSID ?: ""
            var score = 0
            val reasons = mutableListOf<String>()
            if (ssid.isEmpty()) {
                val level = com.camscan.app.WifiSignalHelper.levelPercent(res.level)
                if (level >= 60) {
                    score += 1
                    reasons.add("Rede oculta (sem nome) com sinal forte")
                }
            } else if (RE_WIFI_STRONG.containsMatchIn(ssid)) {
                score += 4
                reasons.add("Nome tipico de camera: $ssid")
            } else if (RE_WIFI_START.containsMatchIn(ssid)) {
                score += 3
                reasons.add("Nome no formato de hotspot de camera (ex.: IPC-XXXX, MV1234): $ssid")
            }
            val prefix = if (bssid.length >= 8) bssid.substring(0, 8).uppercase() else ""
            for ((vendor, prefixes) in OUI) {
                if (prefixes.any { it.equals(prefix, ignoreCase = true) }) {
                    if (vendor.contains("Espressif")) {
                        score += 1
                        reasons.add("Chip Espressif (ESP32-CAM / IoT) no transmissor")
                    } else {
                        score += 3
                        reasons.add("Transmissor de fabricante de cameras: $vendor")
                    }
                }
            }
            if (score >= 1) {
                findings.add(WifiFinding(ssid = ssid, bssid = bssid, level = score, reasons = reasons))
            }
        }
        return findings.sortedByDescending { it.level }
    }
}
