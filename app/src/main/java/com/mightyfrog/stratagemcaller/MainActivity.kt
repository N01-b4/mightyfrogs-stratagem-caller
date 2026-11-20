package com.mightyfrog.stratagemcaller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mightyfrog.stratagemcaller.bluetooth.BluetoothCapability
import com.mightyfrog.stratagemcaller.data.CatalogSeeder
import com.mightyfrog.stratagemcaller.data.Stratagem
import com.mightyfrog.stratagemcaller.data.StratagemDatabase
import com.mightyfrog.stratagemcaller.macro.MacroEngine
import com.mightyfrog.stratagemcaller.macro.MacroOutput
import com.mightyfrog.stratagemcaller.macro.MacroDirection
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private val Charcoal = Color(0xFF171B18)
private val Panel = Color(0xFF252B26)
private val MilitaryGreen = Color(0xFF668A63)
private val Amber = Color(0xFFE3B23C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
private fun App(database: StratagemDatabase) {
    var screen by remember { mutableStateOf(Screen.SETUP) }
    var catalog by remember { mutableStateOf<List<Stratagem>>(emptyList()) }
    var personal by remember { mutableStateOf<List<Stratagem>>(emptyList()) }
    var mission by remember { mutableStateOf<List<Stratagem>>(emptyList()) }
    var locked by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(database) {
        database.dao().observeAll().collectLatest { catalog = it }
    }

    if (screen == Screen.SETUP) {
        SetupScreen(catalog, personal, mission,
            onPersonalChange = { personal = it },
            onMissionChange = { mission = it },
            onLock = { if (personal.size == 4) { locked = true; screen = Screen.MISSION } },
            onSettings = { screen = Screen.SETTINGS })
    } else if (screen == Screen.MISSION) {
        MissionScreen(catalog, personal, mission, running,
            onRun = { _, _ -> running = true },
            onStop = { running = false },
            onEdit = { screen = Screen.SETUP },
            onSettings = { screen = Screen.SETTINGS })
    } else {
        SettingsScreen(onBack = { screen = if (locked) Screen.MISSION else Screen.SETUP })
    }
}

@Composable
private fun SetupScreen(
    catalog: List<Stratagem>,
    personal: List<Stratagem>,
    mission: List<Stratagem>,
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
            Text("LOADOUT CONFIGURATION", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            TextButton(onClick = onSettings) { Text("SETTINGS") }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("PERSONAL ${personal.size}/4") })
            Tab(tab == 1, { tab = 1 }, text = { Text("MISSION ${mission.size}/6") })
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Search") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(category) }
                DropdownMenu(expanded, { expanded = false }) {
                    listOf("All", "Offensive", "Supply", "Defensive", "Mission").forEach {
                        DropdownMenuItem(text = { Text(it) }, onClick = { category = it; expanded = false })
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(visible.size) { index ->
                val item = visible[index]
                val isSelected = selected.any { it.id == item.id }
                CatalogRow(item, isSelected) {
                    if (tab == 0) {
                        if (isSelected) onPersonalChange(personal.filter { it.id != item.id })
                        else if (personal.size < 4) onPersonalChange(personal + item)
                    } else {
                        if (isSelected) onMissionChange(mission.filter { it.id != item.id })
                        else if (mission.size < 6) onMissionChange(mission + item)
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
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Amber else MilitaryGreen),
        onClick = onClick
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(if (selected) "SELECTED" else item.category.uppercase(), color = if (selected) Amber else MilitaryGreen, fontSize = 12.sp)
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
    onRun: (Stratagem, Boolean) -> Unit,
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

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("MISSION CONTROL", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            TextButton(onClick = onEdit) { Text("EDIT") }
            TextButton(onClick = onSettings) { Text("SETTINGS") }
            if (running) TextButton(onClick = onStop) { Text("STOP", color = Color.Red) }
        }
        LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(slots) { item ->
                MissionButton(item, running) { if (item != null) onRun(item, true) }
            }
        }
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Amber)
    }
}

@Composable
private fun MissionButton(item: Stratagem?, running: Boolean, onClick: () -> Unit) {
    if (item == null) {
        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).background(Color(0xFF202420), RoundedCornerShape(8.dp)))
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = !running,
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(2.dp, if (running) Amber else MilitaryGreen),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Panel)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(item.name, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                Text(item.directionList.joinToString(" ") { directionSymbol(it) }, color = Amber, fontSize = 16.sp)
                Text(item.category.uppercase(), color = MilitaryGreen, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    var delayMs by remember { mutableStateOf("90") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SETTINGS", fontWeight = FontWeight.Bold)
        OutlinedTextField(delayMs, { delayMs = it }, label = { Text("Direction delay (ms)") })
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Controller mapping", fontWeight = FontWeight.Bold)
                Text("LB: Stratagem activation")
                Text("D-pad: Up / Down / Left / Right")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Connection", fontWeight = FontWeight.Bold)
                Text("Bluetooth HID: device capability and permission checked at runtime")
                Text("USB: compatible HID bridge hardware required")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Database", fontWeight = FontWeight.Bold)
                Text("Catalog version: 1.0.0")
                Text("Bundled entries load locally; no internet lookup is required")
            }
        }
        Button(onClick = onBack, Modifier.fillMaxWidth()) { Text("BACK") }
    }
}
