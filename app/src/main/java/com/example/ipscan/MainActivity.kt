package com.example.ipscan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ipscan.ui.ScannerScreen
import com.example.ipscan.ui.ScannerViewModel
import com.example.ipscan.ui.theme.IPSCANTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: ScannerViewModel by viewModels()
    private var permissionRequested = false

    private val localNetworkPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onLocalNetworkPermission(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IPSCANTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ScannerScreen(
                    state = state,
                    onSubnetChange = viewModel::onSubnetChange,
                    onScan = viewModel::startScan,
                    onStop = viewModel::cancelScan,
                    onRequestPermission = ::requestLocalNetworkPermission,
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (!permissionRequested && viewModel.state.value.permissionMissing) {
                    permissionRequested = true
                    requestLocalNetworkPermission()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    private fun requestLocalNetworkPermission() {
        localNetworkPermission.launch(ScannerViewModel.PERMISSION_LOCAL_NETWORK)
    }
}
