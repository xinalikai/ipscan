package com.example.ipscan.ui

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ipscan.model.ScanDevice
import com.example.ipscan.net.LocalNetwork
import com.example.ipscan.net.LocalNetworkInfo
import com.example.ipscan.net.parseCidr
import com.example.ipscan.net.toIpv4OrNull
import com.example.ipscan.scan.NetworkScanner
import com.example.ipscan.scan.ScanEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Состояние экрана сканера. */
data class ScannerUiState(
    val subnet: String = "",
    val localIp: String? = null,
    val transport: String? = null,
    val devices: List<ScanDevice> = emptyList(),
    val scanning: Boolean = false,
    val phase: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    val finished: Boolean = false,
    val permissionMissing: Boolean = false,
) {
    val progress: Float?
        get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

class ScannerViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = NetworkScanner(application)
    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var localNetwork: LocalNetworkInfo? = null

    init {
        refreshNetwork()
        checkPermission()
    }

    /** Заново определяет сеть телефона (может смениться после переподключения). */
    fun refreshNetwork() {
        localNetwork = LocalNetwork.detect(getApplication())
        _state.update { current ->
            current.copy(
                subnet = localNetwork?.subnet?.cidr ?: current.subnet,
                localIp = localNetwork?.ip,
                transport = localNetwork?.transport,
            )
        }
    }

    /**
     * Обновляет состояние при возврате в приложение: разрешение могли выдать в
     * настройках, а сеть — сменить. Пользовательский ввод подсети не затирается.
     */
    fun onResume() {
        checkPermission()
        val detected = LocalNetwork.detect(getApplication()) ?: return
        val previous = localNetwork
        localNetwork = detected
        _state.update { current ->
            val customized = previous != null && current.subnet != previous.subnet.cidr
            current.copy(
                localIp = detected.ip,
                transport = detected.transport,
                subnet = if (customized) current.subnet else detected.subnet.cidr,
            )
        }
    }

    fun checkPermission() {
        val missing = Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK &&
            getApplication<Application>().checkSelfPermission(PERMISSION_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        _state.update { it.copy(permissionMissing = missing) }
    }

    fun onLocalNetworkPermission(granted: Boolean) {
        _state.update { it.copy(permissionMissing = !granted) }
        if (granted) refreshNetwork()
    }

    fun onSubnetChange(value: String) {
        _state.update { it.copy(subnet = value) }
    }

    fun startScan() {
        if (_state.value.scanning) return
        val current = _state.value
        if (current.permissionMissing) {
            _state.update { it.copy(error = "Без разрешения на локальную сеть сканирование невозможно") }
            return
        }
        val subnet = parseCidr(current.subnet)
        if (subnet == null) {
            _state.update { it.copy(error = "Подсеть должна быть в формате 192.168.1.0/24 (маска /8…/30)") }
            return
        }
        if (subnet.addressCount > MAX_HOSTS) {
            _state.update { it.copy(error = "Слишком большая подсеть: максимум $MAX_HOSTS адресов") }
            return
        }
        localNetwork = LocalNetwork.detect(getApplication())
        val network = localNetwork
        if (network == null) {
            _state.update {
                it.copy(
                    error = "Нет подключения к Wi-Fi или Ethernet",
                    localIp = null,
                    transport = null,
                )
            }
            return
        }

        scanJob?.cancel()
        _state.update {
            it.copy(
                scanning = true,
                devices = emptyList(),
                error = null,
                phase = "Поиск устройств",
                done = 0,
                total = 0,
                finished = false,
                localIp = network.ip,
                transport = network.transport,
            )
        }
        // Вся работа со сетью — на IO, чтобы главный поток не трогал сокеты и файлы.
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                scanner.scan(subnet, network.gateway, network.ip).collect { event ->
                    when (event) {
                        is ScanEvent.Progress -> _state.update {
                            it.copy(phase = event.phase, done = event.done, total = event.total)
                        }

                        is ScanEvent.Found -> _state.update { state ->
                            state.copy(devices = upsert(state.devices, event.device))
                        }

                        is ScanEvent.Finished -> _state.update {
                            it.copy(
                                scanning = false,
                                phase = "Готово",
                                done = event.count,
                                total = event.count,
                                finished = true,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        scanning = false,
                        error = "Сканирование прервано: ${error.localizedMessage ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(scanning = false, phase = "Остановлено") }
    }

    private fun upsert(devices: List<ScanDevice>, device: ScanDevice): List<ScanDevice> =
        (devices.filterNot { it.ip == device.ip } + device)
            .sortedBy { it.ip.toIpv4OrNull() ?: Long.MAX_VALUE }

    companion object {
        /** ACCESS_LOCAL_NETWORK появился в Android 17 (API 37). */
        const val LOCAL_NETWORK_SDK = 37
        const val PERMISSION_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val MAX_HOSTS = 1024
    }
}
