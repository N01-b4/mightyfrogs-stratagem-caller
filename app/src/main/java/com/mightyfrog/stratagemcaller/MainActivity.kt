package com.mightyfrog.stratagemcaller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.lifecycle.lifecycleScope
import com.mightyfrog.stratagemcaller.data.CatalogSeeder
import com.mightyfrog.stratagemcaller.data.Stratagem
import com.mightyfrog.stratagemcaller.data.StratagemDatabase
import com.mightyfrog.stratagemcaller.macro.MacroEngine
import com.mightyfrog.stratagemcaller.wifi.GameKeyMapping
import com.mightyfrog.stratagemcaller.wifi.WifiMacroOutput
import com.mightyfrog.stratagemcaller.wifi.WifiLinkPhase
import com.mightyfrog.stratagemcaller.wifi.WifiLinkState
import com.mightyfrog.stratagemcaller.wifi.WifiSettings
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.min

private val Charcoal = Color(0xFF090B09)
private val Panel = Color(0xFF151914)
private val PanelRaised = Color(0xFF1B211B)
private val MilitaryGreen = Color(0xFFAFBDA0)
private val Amber = Color(0xFFF2C14E)
private val PermitOffensive = Color(0xFFD76A43)
private val PermitDefensive = Color(0xFF74A9C8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val database = StratagemDatabase.get(this)
        lifecycleScope.launch { CatalogSeeder.seed(this@MainActivity, database) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = MilitaryGreen,
                secondary = Amber,
                background = Charcoal,
                surface = Panel,
                onBackground = Color.White,
                onSurface = Color.White
            )) {
                Surface(Modifier.fillMaxSize(), color = Charcoal) {
                    App(database)
                }
            }
        }
    }
}

private enum class Screen { MISSION, SETUP, SETTINGS }

@Composable
private fun BrandTitle(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(com.mightyfrog.stratagemcaller.R.drawable.caller_logo),
            contentDescription = "MightyFrOg's Stratagem caller logo",
            modifier = Modifier.size(44.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text("MIGHTYFROG'S // SYSTEM", fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.sp, color = MilitaryGreen)
            Text("STRATAGEM CONTROL", fontWeight = FontWeight.Black, fontSize = 13.sp, color = Amber, letterSpacing = 1.5.sp)
        }
    }
}

@Composable
private fun App(database: StratagemDatabase) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("stratagem-caller", android.content.Context.MODE_PRIVATE) }
    val wifi = remember { WifiMacroOutput(context.applicationContext) }
    val macroEngine = remember { MacroEngine(wifi) }
    val scope = rememberCoroutineScope()
    val linkState by wifi.state.collectAsState()
    val savedPersonalIds = remember { preferences.getStringSet("personal_stratagem_ids", emptySet()).orEmpty().toSet() }
    val savedMissionIds = remember { preferences.getStringSet("mission_stratagem_ids", emptySet()).orEmpty().toSet() }
    var screen by remember { mutableStateOf(if (savedPersonalIds.size == 4) Screen.MISSION else Screen.SETUP) }
    var catalog by remember { mutableStateOf<List<Stratagem>>(emptyList()) }
    var personal by remember { mutableStateOf<List<Stratagem>>(emptyList()) }
    var mission by remember { mutableStateOf<List<Stratagem>>(emptyList()) }
    var locked by remember { mutableStateOf(savedPersonalIds.size == 4) }
    var loadoutRestored by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var wifiSettings by remember { mutableStateOf(WifiSettings.load(preferences)) }
    var connectionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(wifi) {
        wifi.start(wifiSettings.mapping)
    }
    DisposableEffect(wifi) {
        onDispose { wifi.stop() }
    }

    LaunchedEffect(database) {
        database.dao().observeAll().collectLatest { catalog = it }
    }
    LaunchedEffect(catalog) {
        if (!loadoutRestored && catalog.isNotEmpty()) {
            personal = catalog.filter { it.id in savedPersonalIds && it.personalSelectable }
            mission = catalog.filter { it.id in savedMissionIds && it.missionSelectable }
            loadoutRestored = true
            if (personal.size == 4) {
                locked = true
                screen = Screen.MISSION
            } else {
                locked = false
                screen = Screen.SETUP
            }
        }
    }

    if (screen == Screen.SETUP) {
        SetupScreen(catalog, personal, mission, linkState,
            onPersonalChange = {
                personal = it
                preferences.edit().putStringSet("personal_stratagem_ids", it.map(Stratagem::id).toSet()).apply()
            },
            onMissionChange = {
                mission = it
                preferences.edit().putStringSet("mission_stratagem_ids", it.map(Stratagem::id).toSet()).apply()
            },
            onLock = { if (personal.size == 4) { locked = true; screen = Screen.MISSION } },
            onSettings = { screen = Screen.SETTINGS })
    } else if (screen == Screen.MISSION) {
        MissionScreen(catalog, personal, mission, running, linkState.isConnected, connectionError ?: linkState.detail,
            onRun = { item ->
                if (wifi.isConnected) {
                    macroEngine.execute(
                        scope = scope,
                        directions = item.directionList,
                        directionDelayMs = wifiSettings.delayMs.toLongOrNull() ?: MacroEngine.DEFAULT_KEY_HOLD_MS,
                        onRunning = { running = it },
                        onError = {
                            connectionError = it
                        }
                    )
                } else {
                    connectionError = "Start the receiver on your Windows 11 Steam Deck and approve its connection."
                }
            },
            onStop = { macroEngine.cancel(); running = false },
            onEdit = { screen = Screen.SETUP },
            onSettings = { screen = Screen.SETTINGS })
    } else {
        SettingsScreen(
            settings = wifiSettings,
            linkState = linkState,
            onSettingsChange = {
                wifiSettings = it
                it.persist(preferences)
                scope.launch {
                    try {
                        wifi.updateMapping(it.mapping)
                    } catch (error: IOException) {
                        connectionError = error.message ?: "Could not update the receiver mapping."
                    }
                }
            },
            onBack = { screen = if (locked) Screen.MISSION else Screen.SETUP }
        )
    }

    if (linkState.phase == WifiLinkPhase.REQUEST_PENDING) {
        AlertDialog(
            onDismissRequest = wifi::rejectConnection,
            title = { Text("STEAM DECK RECEIVER FOUND") },
            text = {
                Text("Allow the Windows receiver at ${linkState.peerAddress ?: "your local network"} to control stratagem keys?")
            },
            confirmButton = {
                TextButton(onClick = { wifi.approveConnection(wifiSettings.mapping) }) { Text("ALLOW") }
            },
            dismissButton = {
                TextButton(onClick = wifi::rejectConnection) { Text("REJECT") }
            },
            containerColor = Panel
        )
    }
}

@Composable
private fun SetupScreen(
    catalog: List<Stratagem>,
    personal: List<Stratagem>,
    mission: List<Stratagem>,
    linkState: WifiLinkState,
    onPersonalChange: (List<Stratagem>) -> Unit,
    onMissionChange: (List<Stratagem>) -> Unit,
    onLock: () -> Unit,
    onSettings: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    val source = if (tab == 0) catalog.filter { it.personalSelectable } else catalog.filter { it.missionSelectable }
    val visible = source.filter {
        it.name.contains(query, ignoreCase = true) &&
            (category == "All" || it.category == category)
    }
    val selected = if (tab == 0) personal else mission

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrandTitle(Modifier.weight(1f))
            Text(
                when (linkState.phase) {
                    WifiLinkPhase.CONNECTED -> "DECK READY"
                    WifiLinkPhase.CONNECTING, WifiLinkPhase.REQUEST_PENDING -> "CONNECTING"
                    WifiLinkPhase.ERROR -> "LINK ERROR"
                    else -> "WAITING FOR DECK"
                },
                color = if (linkState.phase == WifiLinkPhase.CONNECTED) MilitaryGreen else Amber,
                fontSize = 10.sp
            )
            TextButton(onClick = onSettings) { Text("SETTINGS") }
        }
        if (linkState.phase == WifiLinkPhase.ERROR) {
            Text(linkState.detail ?: "Wi-Fi connection failed.", color = Amber, fontSize = 12.sp)
        }
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0; category = "All" }, text = { Text("PERSONAL ${personal.size}/4") })
            Tab(tab == 1, { tab = 1; category = "All" }, text = { Text("MISSION CALL-INS ${mission.size}/6") })
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Search") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = tab == 0) { Text(category) }
                DropdownMenu(expanded, { expanded = false }) {
                    listOf("All", "Offensive", "Supply", "Defensive").forEach {
                        DropdownMenuItem(text = { Text(it) }, onClick = { category = it; expanded = false })
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (visible.isEmpty()) {
                item {
                    Text(
                        if (tab == 0) "No personal stratagems match this search."
                        else "No mission-specific call-ins match this search.",
                        color = Color(0xFFCDD5CF),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                items(visible.size) { index ->
                    val item = visible[index]
                    val isSelected = selected.any { it.id == item.id || it.name == item.name }
                    CatalogRow(item, isSelected) {
                        if (tab == 0) {
                            if (isSelected) onPersonalChange(personal.filter { it.id != item.id && it.name != item.name })
                            else if (personal.size < 4) onPersonalChange(personal + item)
                        } else {
                            if (isSelected) onMissionChange(mission.filter { it.id != item.id && it.name != item.name })
                            else if (mission.size < 6) onMissionChange(mission + item)
                        }
                    }
                }
            }
        }
        Button(
            onClick = onLock,
            enabled = personal.size == 4,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MilitaryGreen)
        ) { Text(if (personal.size == 4) "LOCK MISSION LOADOUT" else "SELECT 4 PERSONAL STRATAGEMS", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun CatalogRow(item: Stratagem, selected: Boolean, onClick: () -> Unit) {
    val permit = when (item.category) {
        "Offensive" -> PermitOffensive
        "Defensive" -> PermitDefensive
        "Mission" -> Amber
        else -> MilitaryGreen
    }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = CutCornerShape(topEnd = 8.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) PanelRaised else Color(0xFF101410)),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Amber else permit),
        onClick = onClick
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name.uppercase(), Modifier.weight(1f), fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Text(if (selected) "[ SELECTED ]" else "[ ${item.category.uppercase()} ]", color = if (selected) Amber else permit, fontSize = 10.sp)
            }
            Text(item.directionList.joinToString(" ") { directionSymbol(it) }, color = Amber)
            Text(item.description, color = Color(0xFFCDD5CF), fontSize = 13.sp)
        }
    }
}

private fun directionSymbol(direction: Char): String = when (direction) {
    'U' -> "▲"; 'D' -> "▼"; 'L' -> "◀"; 'R' -> "▶"; else -> "?"
}

@Composable
private fun MissionScreen(
    catalog: List<Stratagem>,
    personal: List<Stratagem>,
    mission: List<Stratagem>,
    running: Boolean,
    connected: Boolean,
    error: String?,
    onRun: (Stratagem) -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onSettings: () -> Unit
) {
    val slots = mutableListOf<Stratagem?>()
    slots += personal.take(4)
    while (slots.size < 4) slots += null
    slots += listOf(
        catalog.firstOrNull { it.id == "resupply" },
        catalog.firstOrNull { it.id == "reinforce" },
        mission.getOrNull(0),
        mission.getOrNull(1)
    )
    slots += mission.getOrNull(2)
    slots += mission.getOrNull(3)
    slots += mission.getOrNull(4)
    slots += mission.getOrNull(5)

    Column(Modifier.fillMaxSize().background(Charcoal).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrandTitle(Modifier.weight(1f))
            Text(if (connected) "WI-FI LINK READY" else "WI-FI LINK OFFLINE",
                color = if (connected) MilitaryGreen else Amber, fontSize = 9.sp, letterSpacing = 0.8.sp)
            TextButton(onClick = onEdit, enabled = !running) { Text("EDIT") }
            TextButton(onClick = onSettings, enabled = !running) { Text("SETTINGS") }
            if (running) TextButton(onClick = onStop) { Text("STOP", color = Color.Red) }
        }
        error?.let { Text(it, color = Amber, fontSize = 12.sp, maxLines = 1) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val grid = size.width / 20f
                val line = 0.6.dp.toPx()
                for (x in 0..20) {
                    val pos = x * grid
                    drawLine(Color(0xFF20251F), Offset(pos, 0f), Offset(pos, size.height), line)
                }
                for (y in 0..12) {
                    val pos = y * (size.height / 12f)
                    drawLine(Color(0xFF20251F), Offset(0f, pos), Offset(size.width, pos), line)
                }
            }
            val tileSize = min(maxWidth.value / 4.6f, maxHeight.value / 3.25f).dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                slots.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        row.forEach { item ->
                            MissionButton(item, running, connected, Modifier.size(tileSize)) {
                                if (item != null) onRun(item)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MissionButton(item: Stratagem?, running: Boolean, connected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (item == null) {
        Box(modifier.background(Color(0xFF101310), CutCornerShape(topEnd = 6.dp))) {
            Canvas(Modifier.fillMaxSize()) {
                val step = 12.dp.toPx()
                for (x in -size.height.toInt()..size.width.toInt()) {
                    val startX = x.toFloat() * 1f
                    drawLine(Color(0xFF1A1F19), Offset(startX, size.height), Offset(startX + size.height, 0f), 1f)
                }
            }
        }
    } else {
        val permit = when (item.category) {
            "Offensive" -> PermitOffensive
            "Defensive" -> PermitDefensive
            "Mission" -> Amber
            else -> MilitaryGreen
        }
        Surface(
            modifier = modifier,
            onClick = onClick,
            enabled = connected && !running,
            shape = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp),
            color = if (running) Color(0xFF2A2617) else Panel,
            border = BorderStroke(1.dp, if (running) Amber else permit)
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val line = 1.dp.toPx()
                    drawLine(permit, Offset(0f, 0f), Offset(size.width * 0.22f, 0f), line)
                    drawLine(permit, Offset(0f, 0f), Offset(0f, size.height * 0.22f), line)
                    drawLine(permit, Offset(size.width * 0.78f, size.height), Offset(size.width, size.height), line)
                }
                val glyphSize = minOf(this.maxWidth, this.maxHeight) * 0.50f
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    StratagemGlyph(item, Modifier.size(glyphSize))
                    Text(
                        item.directionList.joinToString("") { directionSymbol(it) },
                        modifier = Modifier.padding(top = 1.dp),
                        color = permit,
                        fontSize = 8.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        item.name.uppercase(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.sp,
                        letterSpacing = 0.25.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: WifiSettings,
    linkState: WifiLinkState,
    onSettingsChange: (WifiSettings) -> Unit,
    onBack: () -> Unit
) {
    var delay by remember(settings.delayMs) { mutableStateOf(settings.delayMs) }
    var activation by remember(settings.mapping.activate) { mutableStateOf(settings.mapping.activate) }
    var up by remember(settings.mapping.up) { mutableStateOf(settings.mapping.up) }
    var down by remember(settings.mapping.down) { mutableStateOf(settings.mapping.down) }
    var left by remember(settings.mapping.left) { mutableStateOf(settings.mapping.left) }
    var right by remember(settings.mapping.right) { mutableStateOf(settings.mapping.right) }
    var settingsNotice by remember { mutableStateOf<String?>(null) }
    val current = WifiSettings(delay, GameKeyMapping(activation, up, down, left, right))
    val delayValue = delay.toIntOrNull()
    val validDelay = delayValue != null && delayValue in 30..1000
    val mappingError = current.mapping.validationError()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandTitle(Modifier.weight(1f))
        }
        Text("WIRELESS LINK", color = Amber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Surface(
            color = Color(0xFF202720),
            shape = CutCornerShape(topEnd = 10.dp, bottomStart = 10.dp),
            border = BorderStroke(1.dp, if (linkState.phase == WifiLinkPhase.CONNECTED) MilitaryGreen else Amber)
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (linkState.phase) {
                        WifiLinkPhase.STARTING -> "STARTING PHONE LINK"
                        WifiLinkPhase.WAITING -> "WAITING FOR STEAM DECK"
                        WifiLinkPhase.REQUEST_PENDING -> "DECK REQUESTING ACCESS"
                        WifiLinkPhase.CONNECTING -> "CONNECTING TO DECK"
                        WifiLinkPhase.CONNECTED -> "DECK CONNECTED"
                        WifiLinkPhase.ERROR -> "LINK NEEDS ATTENTION"
                    },
                    color = if (linkState.phase == WifiLinkPhase.CONNECTED) MilitaryGreen else Amber,
                    fontWeight = FontWeight.Bold
                )
                linkState.peerAddress?.let { Text("Receiver: $it", fontSize = 12.sp) }
                Text(
                    linkState.detail
                        ?: "On the Windows 11 Deck, open MightyFrOg's Stratagem Reciver.exe. It finds this phone automatically; approve the request here.",
                    fontSize = 12.sp,
                    color = Color(0xFFCDD5CF)
                )
            }
        }
        Text(
            "No PC address, port, PIN, Windows Firewall rule, or network-profile change is needed. Keep both devices on the same Wi-Fi; guest-network or device-isolation settings can still block local traffic.",
            fontSize = 12.sp,
            color = Color(0xFFCDD5CF)
        )
        HorizontalDivider()
        Text("MACRO TIMING", color = Amber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("Key-down time for each arrow. Default 55 ms; a 25 ms release gap separates arrows. Increase hold time if your Deck misses inputs.", fontSize = 12.sp)
        OutlinedTextField(delay, { delay = it.filter(Char::isDigit).take(4) }, label = { Text("Arrow hold time (ms)") }, singleLine = true)
        HorizontalDivider()
        Text("DECK KEYBOARD MAPPING", color = Amber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("The Windows receiver sends keyboard input to the active game; it does not emulate a gamepad. Set matching stratagem bindings in Helldivers. Keys: A–Z, arrows, CTRL, SHIFT, ALT, SPACE, F1–F12.", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(activation, { activation = it.uppercase().take(10) }, Modifier.weight(1f), label = { Text("Hold key") }, singleLine = true)
            OutlinedTextField(up, { up = it.uppercase().take(10) }, Modifier.weight(1f), label = { Text("Up") }, singleLine = true)
            OutlinedTextField(down, { down = it.uppercase().take(10) }, Modifier.weight(1f), label = { Text("Down") }, singleLine = true)
            OutlinedTextField(left, { left = it.uppercase().take(10) }, Modifier.weight(1f), label = { Text("Left") }, singleLine = true)
            OutlinedTextField(right, { right = it.uppercase().take(10) }, Modifier.weight(1f), label = { Text("Right") }, singleLine = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (validDelay) {
                    onSettingsChange(current)
                    settingsNotice = "Settings saved."
                }
            }, enabled = validDelay && mappingError == null) { Text("SAVE SETTINGS") }
            OutlinedButton(onClick = onBack) { Text("BACK") }
        }
        if (!validDelay) Text("Direction timing must be between 30 and 1000 ms.", color = Amber, fontSize = 12.sp)
        mappingError?.let { Text(it, color = Amber, fontSize = 12.sp) }
        settingsNotice?.let { Text(it, color = MilitaryGreen, fontSize = 12.sp) }
        Text("Catalog runs offline. Keep the receiver window open while playing.", fontSize = 11.sp, color = Color.LightGray)
    }
}

@Composable
private fun StratagemGlyph(item: Stratagem, modifier: Modifier = Modifier) {
    val color = when (item.category) {
        "Offensive" -> Color(0xFFE36C4B)
        "Defensive" -> Color(0xFF80A7C8)
        "Mission" -> Amber
        else -> MilitaryGreen
    }
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stroke = size.minDimension * 0.07f
        val name = item.name.lowercase()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = stroke) {
            drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = width, cap = StrokeCap.Round)
        }

        when {
            name.contains("drill") -> {
                line(cx, cy - size.height * .36f, cx, cy + size.height * .28f, stroke * 1.5f)
                line(cx - size.width * .20f, cy - size.height * .30f, cx + size.width * .20f, cy - size.height * .30f, stroke * 1.5f)
                line(cx - size.width * .24f, cy + size.height * .30f, cx + size.width * .24f, cy + size.height * .30f)
                line(cx - size.width * .15f, cy + size.height * .30f, cx, cy + size.height * .42f)
                line(cx, cy + size.height * .42f, cx + size.width * .15f, cy + size.height * .30f)
            }
            name.contains("flag") -> {
                line(cx - size.width * .24f, cy - size.height * .38f, cx - size.width * .24f, cy + size.height * .38f, stroke * 1.4f)
                line(cx - size.width * .20f, cy - size.height * .35f, cx + size.width * .29f, cy - size.height * .26f, stroke * 1.2f)
                line(cx + size.width * .29f, cy - size.height * .26f, cx - size.width * .16f, cy - size.height * .12f, stroke * 1.2f)
                line(cx - size.width * .16f, cy - size.height * .12f, cx - size.width * .20f, cy - size.height * .35f, stroke * 1.2f)
                line(cx - size.width * .35f, cy + size.height * .38f, cx + size.width * .17f, cy + size.height * .38f)
            }
            name.contains("hellbomb") -> {
                drawRoundRect(
                    color,
                    topLeft = Offset(cx - size.width * .22f, cy - size.height * .22f),
                    size = androidx.compose.ui.geometry.Size(size.width * .44f, size.height * .52f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * .08f),
                    style = Stroke(stroke * 1.3f)
                )
                line(cx, cy - size.height * .22f, cx + size.width * .12f, cy - size.height * .38f)
                line(cx + size.width * .12f, cy - size.height * .38f, cx + size.width * .24f, cy - size.height * .34f)
                line(cx - size.width * .32f, cy - size.height * .02f, cx - size.width * .22f, cy - size.height * .02f)
                line(cx + size.width * .22f, cy - size.height * .02f, cx + size.width * .32f, cy - size.height * .02f)
            }
            name.contains("artillery") -> {
                line(cx - size.width * .28f, cy + size.height * .26f, cx + size.width * .24f, cy - size.height * .26f, stroke * 1.8f)
                line(cx + size.width * .12f, cy - size.height * .14f, cx + size.width * .37f, cy - size.height * .39f, stroke * 1.6f)
                line(cx - size.width * .22f, cy + size.height * .28f, cx - size.width * .37f, cy + size.height * .39f)
                line(cx + size.width * .20f, cy + size.height * .24f, cx + size.width * .34f, cy + size.height * .38f)
            }
            name.contains("camera") -> {
                drawRoundRect(
                    color,
                    topLeft = Offset(cx - size.width * .34f, cy - size.height * .22f),
                    size = androidx.compose.ui.geometry.Size(size.width * .68f, size.height * .46f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke),
                    style = Stroke(stroke * 1.3f)
                )
                drawCircle(color, size.minDimension * .13f, Offset(cx, cy))
                line(cx - size.width * .19f, cy + size.height * .24f, cx - size.width * .30f, cy + size.height * .39f)
                line(cx + size.width * .19f, cy + size.height * .24f, cx + size.width * .30f, cy + size.height * .39f)
            }
            name.contains("upload") || name.contains("sssd") -> {
                drawRoundRect(
                    color,
                    topLeft = Offset(cx - size.width * .28f, cy - size.height * .22f),
                    size = androidx.compose.ui.geometry.Size(size.width * .56f, size.height * .46f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke),
                    style = Stroke(stroke * 1.2f)
                )
                line(cx, cy + size.height * .34f, cx, cy - size.height * .05f, stroke * 1.3f)
                line(cx, cy - size.height * .05f, cx - size.width * .13f, cy + size.height * .08f)
                line(cx, cy - size.height * .05f, cx + size.width * .13f, cy + size.height * .08f)
            }
            name.contains("cargo") -> {
                drawRoundRect(
                    color,
                    topLeft = Offset(cx - size.width * .32f, cy - size.height * .29f),
                    size = androidx.compose.ui.geometry.Size(size.width * .64f, size.height * .58f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke * .5f),
                    style = Stroke(stroke * 1.2f)
                )
                line(cx - size.width * .32f, cy, cx + size.width * .32f, cy)
                line(cx, cy - size.height * .29f, cx, cy + size.height * .29f)
            }
            name.contains("reinforcement pod") -> {
                drawArc(color, 180f, 180f, false,
                    Offset(cx - size.width * .32f, cy - size.height * .36f),
                    androidx.compose.ui.geometry.Size(size.width * .64f, size.height * .46f),
                    style = Stroke(stroke * 1.2f))
                line(cx - size.width * .29f, cy - size.height * .14f, cx - size.width * .12f, cy + size.height * .23f)
                line(cx + size.width * .29f, cy - size.height * .14f, cx + size.width * .12f, cy + size.height * .23f)
                drawRoundRect(
                    color,
                    topLeft = Offset(cx - size.width * .13f, cy + size.height * .10f),
                    size = androidx.compose.ui.geometry.Size(size.width * .26f, size.height * .30f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke),
                    style = Stroke(stroke * 1.2f)
                )
            }
            name.contains("relay") || name.contains("beacon") || name.contains("probe") -> {
                line(cx, cy + size.height * .36f, cx, cy - size.height * .24f, stroke * 1.3f)
                line(cx - size.width * .20f, cy + size.height * .36f, cx + size.width * .20f, cy + size.height * .36f)
                drawArc(color, 215f, 110f, false,
                    Offset(cx - size.width * .36f, cy - size.height * .46f),
                    androidx.compose.ui.geometry.Size(size.width * .72f, size.height * .72f),
                    style = Stroke(stroke))
                drawArc(color, 225f, 90f, false,
                    Offset(cx - size.width * .22f, cy - size.height * .32f),
                    androidx.compose.ui.geometry.Size(size.width * .44f, size.height * .44f),
                    style = Stroke(stroke))
            }
            name.contains("fluid vessel") -> {
                line(cx - size.width * .14f, cy - size.height * .37f, cx + size.width * .14f, cy - size.height * .37f)
                line(cx - size.width * .10f, cy - size.height * .37f, cx - size.width * .10f, cy - size.height * .12f)
                line(cx + size.width * .10f, cy - size.height * .37f, cx + size.width * .10f, cy - size.height * .12f)
                line(cx - size.width * .10f, cy - size.height * .12f, cx - size.width * .30f, cy + size.height * .30f)
                line(cx + size.width * .10f, cy - size.height * .12f, cx + size.width * .30f, cy + size.height * .30f)
                line(cx - size.width * .30f, cy + size.height * .30f, cx + size.width * .30f, cy + size.height * .30f)
                line(cx - size.width * .20f, cy + size.height * .08f, cx + size.width * .20f, cy + size.height * .08f)
            }
            name.contains("eagle rearm") -> {
                line(cx, cy - size.height * .36f, cx, cy + size.height * .34f, stroke * 1.4f)
                line(cx, cy - size.height * .10f, cx - size.width * .34f, cy + size.height * .08f, stroke * 1.6f)
                line(cx, cy - size.height * .10f, cx + size.width * .34f, cy + size.height * .08f, stroke * 1.6f)
                line(cx - size.width * .16f, cy + size.height * .23f, cx, cy + size.height * .34f)
                line(cx + size.width * .16f, cy + size.height * .23f, cx, cy + size.height * .34f)
            }
            name.contains("super destroyer") -> {
                line(cx, cy - size.height * .38f, cx + size.width * .18f, cy + size.height * .08f, stroke * 1.5f)
                line(cx + size.width * .18f, cy + size.height * .08f, cx + size.width * .36f, cy + size.height * .26f)
                line(cx + size.width * .36f, cy + size.height * .26f, cx - size.width * .36f, cy + size.height * .26f)
                line(cx - size.width * .36f, cy + size.height * .26f, cx - size.width * .18f, cy + size.height * .08f)
                line(cx - size.width * .18f, cy + size.height * .08f, cx, cy - size.height * .38f, stroke * 1.5f)
                line(cx - size.width * .12f, cy + size.height * .36f, cx + size.width * .12f, cy + size.height * .36f)
            }
            item.subcategory.lowercase() in setOf("orbital", "eagle") -> {
                drawCircle(color, radius = size.minDimension * 0.23f, center = Offset(cx, cy), style = Stroke(stroke))
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    val r1 = size.minDimension * 0.28f
                    val r2 = size.minDimension * 0.42f
                    line(cx + kotlin.math.cos(a).toFloat() * r1, cy + kotlin.math.sin(a).toFloat() * r1,
                        cx + kotlin.math.cos(a).toFloat() * r2, cy + kotlin.math.sin(a).toFloat() * r2)
                }
            }
            item.subcategory.lowercase() in setOf("sentry", "emplacement") -> {
                drawLine(color, Offset(cx, cy - size.height * .38f), Offset(cx, cy + size.height * .20f), strokeWidth = stroke * 1.4f, cap = StrokeCap.Round)
                drawLine(color, Offset(cx - size.width * .28f, cy + size.height * .36f), Offset(cx, cy + size.height * .12f), strokeWidth = stroke)
                drawLine(color, Offset(cx + size.width * .28f, cy + size.height * .36f), Offset(cx, cy + size.height * .12f), strokeWidth = stroke)
                drawLine(color, Offset(cx - size.width * .15f, cy - size.height * .34f), Offset(cx + size.width * .15f, cy - size.height * .34f), strokeWidth = stroke * 1.4f)
            }
            item.subcategory.equals("backpack", ignoreCase = true) -> {
                drawRoundRect(color, topLeft = Offset(cx - size.width * .28f, cy - size.height * .33f),
                    size = androidx.compose.ui.geometry.Size(size.width * .56f, size.height * .66f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * .12f),
                    style = Stroke(stroke))
                drawLine(color, Offset(cx - size.width * .17f, cy - size.height * .22f), Offset(cx - size.width * .17f, cy + size.height * .22f), strokeWidth = stroke)
                drawLine(color, Offset(cx + size.width * .17f, cy - size.height * .22f), Offset(cx + size.width * .17f, cy + size.height * .22f), strokeWidth = stroke)
            }
            item.subcategory.equals("vehicle", ignoreCase = true) -> {
                drawRoundRect(color, topLeft = Offset(cx - size.width * .35f, cy - size.height * .16f),
                    size = androidx.compose.ui.geometry.Size(size.width * .70f, size.height * .42f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke), style = Stroke(stroke))
                drawCircle(color, size.minDimension * .09f, Offset(cx - size.width * .22f, cy + size.height * .32f))
                drawCircle(color, size.minDimension * .09f, Offset(cx + size.width * .22f, cy + size.height * .32f))
                drawLine(color, Offset(cx, cy - size.height * .16f), Offset(cx, cy - size.height * .36f), strokeWidth = stroke)
            }
            item.subcategory.lowercase() in setOf("objective", "ship", "mission support") -> {
                val radius = size.minDimension * .34f
                drawCircle(color, radius, Offset(cx, cy), style = Stroke(stroke))
                drawLine(color, Offset(cx - radius * .55f, cy), Offset(cx + radius * .55f, cy), strokeWidth = stroke)
                drawLine(color, Offset(cx, cy - radius * .55f), Offset(cx, cy + radius * .55f), strokeWidth = stroke)
            }
            else -> {
                drawRoundRect(color, topLeft = Offset(cx - size.width * .17f, cy - size.height * .38f),
                    size = androidx.compose.ui.geometry.Size(size.width * .34f, size.height * .76f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * .1f), style = Stroke(stroke * 1.3f))
                drawLine(color, Offset(cx, cy - size.height * .20f), Offset(cx, cy + size.height * .20f), strokeWidth = stroke)
            }
        }
    }
}
