package com.dataproxy.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dataproxy.network.NetworkInterfaceLister
import com.dataproxy.service.ProxyService
import com.dataproxy.ui.theme.Accent
import com.dataproxy.ui.theme.SurfaceMid
import com.dataproxy.ui.theme.TextMuted
import com.dataproxy.ui.theme.TextPrimary
import com.dataproxy.ui.theme.TextSecondary
import com.dataproxy.ui.theme.Warning as WarningColor
import com.dataproxy.ui.viewmodel.MainViewModel
import com.dataproxy.util.TrustedNetwork

@Composable
fun TrustedNetworksScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val networks by viewModel.trustedNetworks.collectAsStateWithLifecycle()
    val currentSsid by viewModel.currentWifiSsid.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TrustedNetwork?>(null) }
    var addingManual by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .padding(top = 4.dp, bottom = 12.dp),
    ) {
        TopBar(title = "Trusted networks", onBack = onBack)
        Spacer(Modifier.height(8.dp))

        if (currentSsid != null && networks.none { it.ssid == currentSsid }) {
            Button(
                onClick = {
                    editing = TrustedNetwork(
                        ssid = currentSsid!!,
                        address = "0.0.0.0",
                        port = ProxyService.DEFAULT_PORT,
                    )
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Trust \"$currentSsid\"", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = { addingManual = true },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add network manually")
        }
        Spacer(Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (networks.isEmpty()) {
                Text(
                    text = "No trusted networks yet",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            networks.forEach { network ->
                TrustedNetworkRow(
                    network = network,
                    isCurrent = network.ssid == currentSsid,
                    onEdit = { editing = network },
                    onDelete = { viewModel.removeTrustedNetwork(network.ssid) },
                )
            }
        }
    }

    val editTarget = editing ?: if (addingManual) TrustedNetwork("", "0.0.0.0", ProxyService.DEFAULT_PORT) else null
    if (editTarget != null) {
        TrustedNetworkEditDialog(
            initial = editTarget,
            allowSsidEdit = addingManual,
            onDismiss = { editing = null; addingManual = false },
            onSave = { network ->
                if (networks.any { it.ssid == network.ssid }) {
                    viewModel.updateTrustedNetwork(network)
                } else {
                    viewModel.addTrustedNetwork(network)
                }
                editing = null
                addingManual = false
            },
        )
    }
}

@Composable
private fun TrustedNetworkRow(
    network: TrustedNetwork,
    isCurrent: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isCurrent) Accent.copy(alpha = 0.08f) else SurfaceMid)
            .border(
                width = if (isCurrent) 1.dp else 0.dp,
                color = if (isCurrent) Accent else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onEdit)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (network.lastBindError != null) {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = "Bind failed: ${network.lastBindError}",
                tint = WarningColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = network.ssid,
                color = TextPrimary,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
            )
            Text(
                text = "${network.address}:${network.port}",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, contentDescription = "Remove", tint = TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun TrustedNetworkEditDialog(
    initial: TrustedNetwork,
    allowSsidEdit: Boolean,
    onDismiss: () -> Unit,
    onSave: (TrustedNetwork) -> Unit,
) {
    var ssid by remember { mutableStateOf(initial.ssid) }
    var address by remember { mutableStateOf(initial.address) }
    var port by remember { mutableStateOf(initial.port) }
    val candidates = remember { NetworkInterfaceLister.list() }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceMid,
        titleContentColor = TextPrimary,
        title = { Text(if (allowSsidEdit) "Add network" else "Edit \"${initial.ssid}\"") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (allowSsidEdit) {
                    androidx.compose.material3.OutlinedTextField(
                        value = ssid,
                        onValueChange = { ssid = it },
                        label = { Text("Network name (SSID)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                candidates.forEach { cand ->
                    AddressRow(
                        candidate = cand,
                        selected = cand.address == address,
                        enabled = true,
                        onClick = { address = cand.address },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                PortField(port = port, enabled = true, onChange = { port = it })
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (ssid.isNotBlank()) onSave(TrustedNetwork(ssid.trim(), address, port, initial.lastBindError)) },
            ) { Text("Save", color = Accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        },
    )
}
