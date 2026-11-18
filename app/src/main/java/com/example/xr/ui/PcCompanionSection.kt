package com.example.xr.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CanvasObsidian
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.RoseError
import com.example.ui.theme.SkyPrimary
import com.example.ui.theme.SkyPrimaryDim
import com.example.ui.theme.SpatialGlass
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.xr.XRLauncherCoordinator
import com.example.xr.companion.CompanionConnectionState
import com.example.xr.companion.CompanionLinkStats
import com.example.xr.companion.CompanionProtocol
import com.example.xr.companion.CompanionQualityTier
import com.example.xr.companion.DiscoveredPcHost
import com.example.xr.windows.WindowType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PcCompanionCard(
    coordinator: XRLauncherCoordinator,
    stats: CompanionLinkStats,
    discoveredHosts: List<DiscoveredPcHost>
) {
    var hostInput by remember { mutableStateOf("192.168.1.100") }
    var pinInput by remember { mutableStateOf("") }
    var allowNonPrivate by remember { mutableStateOf(false) }
    var thresholdMs by remember { mutableFloatStateOf(coordinator.pcCompanion.highLatencyThresholdMs) }

    Card(
        colors = CardDefaults.cardColors(containerColor = SpatialGlass),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SkyPrimaryDim, RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Optional PC Companion Link (LAN / USB Only)",
                    style = MaterialTheme.typography.titleMedium,
                    color = SkyPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Completely optional. Offload hand-landmark inference and stream a spatial PC window over local Wi-Fi or USB adb reverse — never internet/cloud.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "State: ${stats.state.label}",
                style = MaterialTheme.typography.labelLarge,
                color = when (stats.state) {
                    CompanionConnectionState.CONNECTED_ACTIVE -> EmeraldActive
                    CompanionConnectionState.FALLBACK_ON_DEVICE -> AmberWarning
                    else -> SkyPrimary
                }
            )
            stats.statusBanner?.let { banner ->
                Spacer(Modifier.height(4.dp))
                Text(banner, style = MaterialTheme.typography.labelMedium, color = AmberWarning)
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { coordinator.nsdDiscovery.startDiscovery() },
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("pc_scan_lan_btn")
                ) {
                    Text("Scan LAN (mDNS)")
                }
                OutlinedButton(
                    onClick = { hostInput = "127.0.0.1" },
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("pc_usb_reverse_btn")
                ) {
                    Text("USB adb reverse")
                }
            }

            if (discoveredHosts.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Discovered PC Hosts:", style = MaterialTheme.typography.labelMedium, color = SkyPrimary)
                discoveredHosts.forEach { host ->
                    Text(
                        "• ${host.serviceName} (${host.hostAddress}:${host.controlPort})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmeraldActive,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { hostInput = host.hostAddress }
                            .padding(vertical = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = hostInput,
                onValueChange = { hostInput = it },
                label = { Text("PC LAN IP or 127.0.0.1 (USB)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("pc_host_input")
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = pinInput,
                onValueChange = { if (it.length <= 6 && it.all { ch -> ch.isDigit() }) pinInput = it },
                label = { Text("6-Digit Pairing Code (shown on PC)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("pc_pin_input")
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    "Allow non-private IP override (unsafe outside LAN)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = allowNonPrivate,
                    onCheckedChange = { allowNonPrivate = it }
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (stats.state == CompanionConnectionState.CONNECTED_ACTIVE) {
                    Button(
                        onClick = { coordinator.disconnectCompanion() },
                        colors = ButtonDefaults.buttonColors(containerColor = RoseError, contentColor = CanvasObsidian),
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("pc_disconnect_btn")
                    ) {
                        Text("Disconnect PC")
                    }
                    OutlinedButton(
                        onClick = { coordinator.windowManager.openOrFocusWindow(WindowType.PC_DESKTOP) },
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("pc_open_window_btn")
                    ) {
                        Text("Open PC Window")
                    }
                } else {
                    Button(
                        onClick = {
                            coordinator.requestCompanionConnect(
                                host = hostInput,
                                controlPort = CompanionProtocol.DEFAULT_CONTROL_PORT,
                                udpPort = CompanionProtocol.DEFAULT_UDP_PORT,
                                pairingCode = pinInput,
                                allowNonPrivateOverride = allowNonPrivate
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SkyPrimary, contentColor = CanvasObsidian),
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("pc_connect_btn")
                    ) {
                        Text("Pair & Connect Encrypted PC Link")
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("Stream & Upload Quality", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                CompanionQualityTier.entries.forEach { tier ->
                    FilterChip(
                        selected = stats.qualitySelector == tier,
                        onClick = { coordinator.pcCompanion.setQualitySelector(tier) },
                        label = { Text(tier.name, maxLines = 1) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    )
                }
            }

            Text(
                "Auto-Fallback Latency Threshold (${thresholdMs.toInt()} ms for >3s)",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Slider(
                value = thresholdMs,
                onValueChange = {
                    thresholdMs = it
                    coordinator.pcCompanion.highLatencyThresholdMs = it
                },
                valueRange = 60f..250f
            )

            Text(
                "RTT: ${"%.1f".format(stats.rttMs)} ms · Jitter: ${"%.1f".format(stats.jitterMs)} ms · Loss: ${"%.1f".format(stats.packetLossPercent)}%",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Text(
                "Bitrate Up/Down: ${stats.uploadBitrateKbps}/${stats.streamBitrateKbps} kbps · Enc/Dec/Hand E2E: ${"%.1f".format(stats.cameraEncodeMs)}/${"%.1f".format(stats.streamDecodeMs)}/${"%.1f".format(stats.handEndToEndMs)} ms",
                style = MaterialTheme.typography.labelSmall,
                color = SkyPrimary
            )
        }
    }
}
