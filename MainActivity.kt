package com.camscan.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.camscan.app.databinding.ActivityMainBinding
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            startScan()
        } else {
            binding.statusText.text = "Sem permissao de localizacao, a varredura de rede continua," +
                " mas a lista de redes Wi-Fi proximas nao vai aparecer."
            startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnScan.setOnClickListener {
            binding.btnScan.isEnabled = false
            binding.resultsText.text = ""
            ensurePermissionsAndScan()
        }
    }

    private fun ensurePermissionsAndScan() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        if (needed.isEmpty()) {
            startScan()
        } else {
            requestPermissions.launch(needed.toTypedArray())
        }
    }

    private fun startScan() {
        lifecycleScope.launch {
            try {
                runFullScan()
            } catch (e: Exception) {
                binding.statusText.text = "Erro durante a varredura: ${e.message}"
            } finally {
                binding.btnScan.isEnabled = true
                binding.progressBar.visibility = android.view.View.GONE
            }
        }
    }

    private suspend fun runFullScan() = coroutineScope {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.progress = 0
        binding.statusText.text = "Descobrindo sua rede..."

        val netInfo = NetworkScanner.getNetInfo(this@MainActivity)
        if (netInfo == null) {
            binding.statusText.text = "Nao foi possivel detectar a rede Wi-Fi. Conecte-se a uma rede e tente de novo."
            return@coroutineScope
        }

        val hosts = NetworkScanner.hostsInRange(netInfo.myIp, netInfo.prefix)
        binding.statusText.text = "Seu IP: ${netInfo.myIp}  |  Roteador: ${netInfo.gateway ?: "?"}  |  Enderecos a varrer: ${hosts.size}"

        // Dispara a descoberta ONVIF (WS-Discovery) em paralelo com a varredura de portas
        val onvifDiscoveryDeferred = async { NetworkScanner.onvifWsDiscovery() }

        binding.statusText.text = "Varrendo portas tipicas de camera/DVR/NVR em ${hosts.size} enderecos..."
        val openPortsByHost = NetworkScanner.scanPorts(
            ips = hosts,
            ports = NetworkScanner.CAMERA_PORTS
        ) { done, total ->
            lifecycleScope.launch(Dispatchers.Main) {
                val pct = if (total > 0) (done * 100 / total) else 0
                binding.progressBar.progress = pct
            }
        }

        val liveHosts = openPortsByHost.keys.toList()
        binding.statusText.text = "${liveHosts.size} dispositivo(s) com portas abertas. Testando RTSP/ONVIF/HTTP..."

        val onvifDiscovery = onvifDiscoveryDeferred.await()

        val deviceResults = mutableListOf<DeviceResult>()
        for (ip in liveHosts) {
            val ports = openPortsByHost[ip] ?: mutableListOf()

            var rtspOpen = false
            var rtspPort: Int? = null
            for (p in ports.filter { it in setOf(554, 555, 8554, 10554) }) {
                if (NetworkScanner.probeRtsp(ip, p)) {
                    rtspOpen = true
                    rtspPort = p
                    break
                }
            }

            val httpFindings = mutableListOf<String>()
            for (p in ports.filter { NetworkScanner.WEB_PORTS.contains(it) }) {
                httpFindings.addAll(NetworkScanner.probeHttp(ip, p))
            }

            var onvifInfo: String? = null
            for (p in ports.filter { NetworkScanner.ONVIF_PORTS.contains(it) }) {
                val info = NetworkScanner.probeOnvifDeviceInfo(ip, p)
                if (info != null) {
                    onvifInfo = info
                    break
                }
            }

            val result = NetworkScanner.classify(
                ip = ip,
                openPorts = ports,
                rtspOpen = rtspOpen,
                rtspPort = rtspPort,
                onvifInfo = onvifInfo,
                onvifDiscovery = onvifDiscovery[ip],
                httpFindings = httpFindings
            )
            deviceResults.add(result)
        }

        binding.statusText.text = "Procurando redes Wi-Fi proximas com hotspot de camera..."
        triggerWifiScanIfPossible()
        delay(2500)
        val wifiFindings = try {
            NetworkScanner.scanWifi(this@MainActivity)
        } catch (e: Exception) {
            emptyList()
        }

        renderReport(netInfo, deviceResults, wifiFindings)
        binding.statusText.text = "Varredura concluida."
    }

    private fun triggerWifiScanIfPossible() {
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiManager.startScan()
        } catch (e: Exception) {
            // Android pode bloquear por limite de chamadas (throttling); os resultados do cache ainda sao usados.
        }
    }

    private fun renderReport(
        netInfo: NetworkScanner.NetInfo,
        devices: List<DeviceResult>,
        wifi: List<WifiFinding>
    ) {
        val sb = StringBuilder()
        val sorted = devices.sortedByDescending { it.score }

        sb.append("=== TODOS OS DISPOSITIVOS COM PORTAS ABERTAS ===\n")
        if (sorted.isEmpty()) {
            sb.append("Nenhum dispositivo com portas tipicas de camera/DVR respondeu.\n")
        }
        for (d in sorted) {
            sb.append("[${d.level()}] ${d.ip}  portas: ${d.openPorts.sorted().joinToString(",")}\n")
            for (r in d.reasons) sb.append("    - $r\n")
            sb.append("\n")
        }

        val dvrs = devices.filter { it.isDvr }
        sb.append("=== GRAVADORES (DVR/NVR) ===\n")
        if (dvrs.isEmpty()) {
            sb.append("Nenhum gravador DVR/NVR (portas 8000/37777/34567) encontrado.\n")
        } else {
            for (d in dvrs) {
                sb.append("${d.ip}  marca provavel: ${d.dvrBrand ?: "(nao identificada)"}  portas: ${d.openPorts.sorted().joinToString(",")}\n")
            }
            sb.append("Dica: confira no app do fabricante (Hik-Connect, iDMSS/gDMSS, XMEye) quantos canais o gravador tem.\n")
        }
        sb.append("\n")

        sb.append("=== REDES WI-FI PROXIMAS SUSPEITAS ===\n")
        if (wifi.isEmpty()) {
            sb.append("Nenhuma rede Wi-Fi com nome/transmissor tipico de camera encontrada (ou permissao de localizacao negada).\n")
        } else {
            for (w in wifi) {
                val nome = w.ssid.ifEmpty { "(oculta)" }
                sb.append("$nome  (${w.bssid})  pontuacao ${w.level}\n")
                for (r in w.reasons) sb.append("    - $r\n")
            }
        }
        sb.append("\n")

        sb.append("=== COMO INTERPRETAR ===\n")
        sb.append("ALTO = muito provavel ser camera/DVR (RTSP, ONVIF ou porta de controle do fabricante).\n")
        sb.append("MEDIO = indicios fortes, vale conferir o aparelho.\n")
        sb.append("BAIXO = indicio fraco; pode ser camera, mas tambem outro aparelho IoT.\n")
        sb.append("Portas 8000 (Hikvision), 37777 (Dahua/Intelbras) e 34567+8899 (XMEye) indicam um GRAVADOR,\n")
        sb.append("que pode estar ligado a varias cameras por cabo, nao so Wi-Fi.\n")
        sb.append("\n")
        sb.append("=== LIMITES DESTA VERSAO ANDROID ===\n")
        sb.append("- O Android nao deixa apps lerem a tabela ARP/MAC dos outros dispositivos da rede (restricao de\n")
        sb.append("  privacidade do sistema), entao a identificacao usa portas, ONVIF e paginas web, nao o MAC do cabo.\n")
        sb.append("- A varredura de Wi-Fi e limitada pelo proprio Android (poucas varreduras a cada 2 minutos) e so\n")
        sb.append("  funciona com a permissao de localizacao concedida.\n")
        sb.append("- Complemente com busca fisica: lanterna para ver o reflexo da lente, detector de RF/camera.\n")

        binding.resultsText.text = sb.toString()
    }
}
