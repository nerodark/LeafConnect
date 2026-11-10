package leafconnect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import leafconnect.generated.resources.Res
import leafconnect.generated.resources.battery_status
import leafconnect.generated.resources.cancel
import leafconnect.generated.resources.cmd_failed
import leafconnect.generated.resources.cmd_note
import leafconnect.generated.resources.cmd_status
import leafconnect.generated.resources.cmd_success
import leafconnect.generated.resources.ic_refresh
import leafconnect.generated.resources.locate
import leafconnect.generated.resources.lock
import leafconnect.generated.resources.log_out
import leafconnect.generated.resources.no_vehicles
import leafconnect.generated.resources.plugin_status
import leafconnect.generated.resources.range_status
import leafconnect.generated.resources.refresh_status
import leafconnect.generated.resources.refreshing
import leafconnect.generated.resources.start_charge
import leafconnect.generated.resources.start_temp
import leafconnect.generated.resources.status_placeholder
import leafconnect.generated.resources.stop_temp
import leafconnect.generated.resources.temp_status
import leafconnect.generated.resources.unit_celsius
import leafconnect.generated.resources.unit_fahrenheit
import leafconnect.generated.resources.unit_km
import leafconnect.generated.resources.unit_miles
import leafconnect.generated.resources.unlock

/**
 * The home screen, laid out like the original `activity_home.xml`: the last
 * command banner on top (bold 16sp on a rounded banner), the vehicle list in the
 * middle, the fixed-width LOG OUT button pinned below it, and the rate-limit
 * footnote at the bottom. Vehicle rows are plain text — name bold 18sp, VIN 13sp,
 * status 14sp with the refresh arrow sitting right after the first line — and the
 * command buttons are solid green, as the platform Material buttons were.
 *
 * Pull-to-refresh is the only refresh gesture (the arrow does the same round for
 * one vehicle); while a round runs the dim overlay with CANCEL covers the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(controller: AppController) {
    val vehicles by controller.vehicles.collectAsState()
    val banner by controller.banner.collectAsState()
    val loading by controller.loading.collectAsState()
    val palette = LocalLeafPalette.current

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            banner?.let { CommandBanner(it) }

            PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = { controller.refreshAll() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (vehicles.isEmpty()) {
                        item {
                            Text(
                                stringResource(Res.string.no_vehicles),
                                fontSize = 16.sp,
                                color = palette.textSecondary,
                                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                            )
                        }
                    }
                    items(vehicles, key = { it.vin }) { card ->
                        VehicleRow(card, controller)
                    }
                }
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(onClick = { controller.logout(manual = true) }, modifier = Modifier.width(300.dp)) {
                    Text(stringResource(Res.string.log_out))
                }
            }

            Text(
                stringResource(Res.string.cmd_note),
                fontSize = 12.sp,
                color = palette.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }

        if (loading) {
            // The dim overlay: CANCEL is the only way out, and cancelling
            // restores the cached statuses instead of leaving half-updated rows.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x80000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { controller.cancelAll() }) {
                        Text(stringResource(Res.string.cancel))
                    }
                }
            }
        }
    }
}

/** Last requested command + outcome, bold on a rounded banner (as before). */
@Composable
private fun CommandBanner(banner: CommandBanner) {
    val palette = LocalLeafPalette.current
    val background = if (banner.ok) palette.bannerOkBackground else palette.bannerFailBackground
    val foreground = if (banner.ok) palette.bannerOkForeground else palette.bannerFailForeground
    Text(
        stringResource(
            Res.string.cmd_status,
            stringResource(commandLabel(banner.label)),
            stringResource(if (banner.ok) Res.string.cmd_success else Res.string.cmd_failed),
        ),
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = foreground,
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .padding(bottom = 8.dp),
    )
}

/** One vehicle, laid out like HomeActivity's card rows (no card chrome). */
@Composable
private fun VehicleRow(card: VehicleUi, controller: AppController) {
    val palette = LocalLeafPalette.current

    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp)) {
        Text(card.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(card.vinText, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }

    if (card.fetching) {
        Text(stringResource(Res.string.refreshing), fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusFirstLine(card.status)
            IconButton(
                onClick = { controller.refreshVehicle(card.vin) },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_refresh),
                    contentDescription = stringResource(Res.string.refresh_status),
                    tint = palette.iconTint,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        StatusSecondLine(card.status)
    }

    CommandRow(topPadding = 8.dp) {
        CommandButton(stringResource(Res.string.lock)) { controller.command(card.vin, CommandKey.LOCK) }
        CommandButton(stringResource(Res.string.unlock)) { controller.command(card.vin, CommandKey.UNLOCK) }
    }
    CommandRow(topPadding = 8.dp) {
        CommandButton(stringResource(Res.string.start_temp)) { controller.command(card.vin, CommandKey.START_TEMP) }
        CommandButton(stringResource(Res.string.stop_temp)) { controller.command(card.vin, CommandKey.STOP_TEMP) }
    }
    CommandRow(topPadding = 8.dp) {
        CommandButton(stringResource(Res.string.start_charge)) { controller.command(card.vin, CommandKey.START_CHARGE) }
        CommandButton(stringResource(Res.string.locate)) { controller.command(card.vin, CommandKey.LOCATE) }
    }
}

@Composable
private fun CommandRow(topPadding: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = topPadding, bottom = 8.dp),
    ) {
        content()
    }
}

/** Solid green button, like the platform Material button it replaced. */
@Composable
private fun CommandButton(label: String, onClick: () -> Unit) {
    val palette = LocalLeafPalette.current
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = palette.green, contentColor = Color.White),
    ) {
        Text(label)
    }
}

@Composable
private fun StatusFirstLine(status: VehicleStatus?) {
    val palette = LocalLeafPalette.current
    val first = buildList {
        status?.socPercent?.let { add(stringResource(Res.string.battery_status, it)) }
        status?.tempValue()?.let {
            val unit = stringResource(if (status.usesFahrenheit) Res.string.unit_fahrenheit else Res.string.unit_celsius)
            add(stringResource(Res.string.temp_status, it, unit))
        }
    }
    Text(
        first.joinToString(" · ").ifEmpty { stringResource(Res.string.status_placeholder) },
        fontSize = 14.sp,
        color = if (first.isEmpty()) palette.textSecondary else Color.Unspecified,
    )
}

@Composable
private fun StatusSecondLine(status: VehicleStatus?) {
    val palette = LocalLeafPalette.current
    val second = buildList {
        status?.pluginState?.let { add(stringResource(Res.string.plugin_status, it)) }
        status?.rangeValues()?.let { (on, off) ->
            val unit = stringResource(if (status.miles) Res.string.unit_miles else Res.string.unit_km)
            add(stringResource(Res.string.range_status, on, off, unit))
        }
    }
    if (second.isEmpty()) return
    Text(
        second.joinToString(" · "),
        fontSize = 14.sp,
        color = palette.textSecondary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun commandLabel(key: CommandKey) = when (key) {
    CommandKey.LOCK -> Res.string.lock
    CommandKey.UNLOCK -> Res.string.unlock
    CommandKey.START_TEMP -> Res.string.start_temp
    CommandKey.STOP_TEMP -> Res.string.stop_temp
    CommandKey.START_CHARGE -> Res.string.start_charge
    CommandKey.LOCATE -> Res.string.locate
}
