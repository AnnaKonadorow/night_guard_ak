package com.example.nightguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import com.example.nightguard.ui.theme.NightGuardTheme
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.FloatingActionButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.bonuspack.routing.RoadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.bonuspack.location.NominatimPOIProvider
import org.osmdroid.bonuspack.location.GeocoderNominatim
import kotlinx.coroutines.delay
import android.content.Context
import android.location.LocationManager
import android.telephony.SmsManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.bonuspack.routing.RoadNode
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.LocationOn

// Data class dla Kontaktu (na potrzeby edycji w pamięci)
data class TrustedContact(val id: Int, val name: String, val phone: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Inicjalizacja magazynu danych (poza setContent jest OK)
        val storage = DataStorage(applicationContext)

        Configuration.getInstance().userAgentValue = "NightGuard/1.0"

        setContent {
            // --- TUTAJ ZACZYNA SIĘ KONTEKST COMPOSABLE ---

            // 1. Sprawdź ustawienie systemowe (UE)
            val systemInDarkTheme = isSystemInDarkTheme()

            // 2. Pobierz zapisany wybór użytkownika (0: System, 1: Light, 2: Dark)
            val savedThemeMode by storage.themeFlow.collectAsState(initial = 0)

            // 3. Oblicz finalny motyw
            val darkTheme = when (savedThemeMode) {
                1 -> false
                2 -> true
                else -> systemInDarkTheme // Jeśli 0, to dostosuj do UE
            }

            NightGuardTheme(darkTheme = darkTheme) {
                NightGuardApp(
                    storage = storage,
                    isDarkTheme = darkTheme,
                    onThemeChanged = { isDark ->
                        // Zapisujemy wybór na stałe (Coroutine)
                        GlobalScope.launch {
                            storage.saveTheme(if (isDark) 2 else 1)
                        }
                    }
                )
            }
        }
    }
}

fun sendSmsAlert(context: Context, phoneNumber: String, message: String) {
    try {
        val smsManager: SmsManager = context.getSystemService(SmsManager::class.java)
        smsManager.sendTextMessage(phoneNumber, null, message, null, null)
    } catch (e: Exception) {
        Toast.makeText(context, "Błąd wysyłania do $phoneNumber: ${e.message}", Toast.LENGTH_SHORT).show()
        e.printStackTrace()
    }
}

@Composable
fun NightGuardApp(
    storage: DataStorage,
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit
) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }
    val scope = rememberCoroutineScope()

    // Ładowanie kontaktów z DataStore
    val savedContacts by storage.contactsFlow.collectAsState(initial = emptyList())

    // Lista robocza w pamięci
    val trustedContacts = remember { mutableStateListOf<TrustedContact>() }

    // Synchronizacja: gdy dane z pamięci (savedContacts) się zmienią, aktualizuj listę w UI
    LaunchedEffect(savedContacts) {
        trustedContacts.clear()
        trustedContacts.addAll(savedContacts)
    }

    // Funkcja do trwałego zapisu listy
    val syncContacts = {
        scope.launch {
            storage.saveContacts(trustedContacts.toList())
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = { Icon(it.icon, contentDescription = it.label) },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        when (currentDestination) {
            AppDestinations.HOME -> HomeScreen(
                onNavigateToMap = { currentDestination = AppDestinations.MAP },
                contacts = trustedContacts
            )
            AppDestinations.MAP -> MapScreen()
            AppDestinations.PROFILE -> ProfileScreen(
                isDarkTheme = isDarkTheme,
                onThemeChanged = onThemeChanged,
                contacts = trustedContacts,
                onContactListChanged = { syncContacts() } // Zapisuj przy każdej zmianie
            )
        }
    }
}

fun traceRoute(
    context: android.content.Context,
    mapView: MapView,
    startPoint: GeoPoint,
    destinationPoint: GeoPoint
) {
    // Zmieniamy typ na OSRMRoadManager, aby odblokować funkcje specyficzne dla OSRM
    val roadManager = OSRMRoadManager(context, "NightGuard/1.0")

    // Ustawienie profilu pieszego poprzez zmianę URL serwisu
    //roadManager.setService("https://router.project-osrm.org/route/v1/walking/")

    GlobalScope.launch(Dispatchers.IO) {
        val waypoints = arrayListOf(startPoint, destinationPoint)
        try {
            val road = roadManager.getRoad(waypoints)
            val roadOverlay = RoadManager.buildRoadOverlay(road)

            withContext(Dispatchers.Main) {
                mapView.overlays.removeAll { it is Polyline }
                mapView.overlays.add(roadOverlay)
                mapView.invalidate()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// Poprawiona funkcja wyszukiwania adresu (findAddress)
fun findAddress(
    addressString: String,
    mapView: MapView,
    onLocationFound: (GeoPoint) -> Unit
) {
    val geocoder = GeocoderNominatim("NightGuard/1.0")

    GlobalScope.launch(Dispatchers.IO) {
        try {
            // Jawne określenie typu dla listy wyników
            val addresses = geocoder.getFromLocationName(addressString, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val targetPoint = GeoPoint(address.latitude, address.longitude)

                withContext(Dispatchers.Main) {
                    // Przesunięcie kamery do celu
                    mapView.controller.animateTo(targetPoint)
                    // Wywołanie callbacka, który ustawi pinezkę i trasę
                    onLocationFound(targetPoint)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
fun fetchAddressSuggestions(
    query: String,
    onResult: (List<android.location.Address>) -> Unit
) {
    if (query.length < 3) return // Nie szukaj dla zbyt krótkich fraz

    val geocoder = GeocoderNominatim("NightGuard/1.0")
    GlobalScope.launch(Dispatchers.IO) {
        try {
            // Pobieramy do 5 propozycji
            val results = geocoder.getFromLocationName(query, 5)
            withContext(Dispatchers.Main) {
                onResult(results ?: emptyList())
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}

// --- Definicja Zakładek ---
enum class AppDestinations(val label: String, val icon: ImageVector) {
    HOME("Start", Icons.Default.Home),
    MAP("Mapa", Icons.Default.LocationOn),
    PROFILE("Profil", Icons.Default.AccountCircle),
}

// --- EKRAN GŁÓWNY (HOME) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToMap: () -> Unit,
    contacts: List<TrustedContact>
) {
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Asystent Powrotu") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Karta nawigacji
            ElevatedCard(
                onClick = onNavigateToMap,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Otwórz Mapę i nawiguj", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            Text("Twoje Zaufane Kontakty", style = MaterialTheme.typography.headlineSmall)

            if (contacts.isEmpty()) {
                Text("Brak kontaktów. Dodaj je w zakładce Profil.", color = Color.Gray)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(contacts) { contact ->
                        TrustedContactItem(name = contact.name, phone = contact.phone)
                    }
                }
            }
        }
    }
}
@Composable
fun TrustedContactItem(name: String, phone: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Person, contentDescription = null)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = name, fontWeight = FontWeight.Bold)
                Text(text = phone, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- STANY ---
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var locationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }

    var startPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var destinationPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var currentRoad by remember { mutableStateOf<Road?>(null) }

    var destinationQuery by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<android.location.Address>>(emptyList()) }
    var isExpanded by remember { mutableStateOf(false) } // Rozwijanie listy kroków

    // --- GEOLOKALIZACJA ---
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            locationOverlay?.enableMyLocation()
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    // --- WYSZUKIWANIE ---
    LaunchedEffect(destinationQuery) {
        if (destinationQuery.length > 3) {
            delay(600)
            val geocoder = GeocoderNominatim("NightGuard/0.1.3")
            try {
                val results = withContext(Dispatchers.IO) { geocoder.getFromLocationName(destinationQuery, 5) }
                suggestions = results ?: emptyList()
            } catch (e: Exception) { suggestions = emptyList() }
        } else suggestions = emptyList()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. MAPA
        AndroidView(
            factory = { ctx ->
                Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(15.0)

                    val overlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this)
                    overlay.enableMyLocation()
                    overlays.add(overlay)
                    locationOverlay = overlay

                    // Kliknięcie w mapę ustawia CEL (Punkt B)
                    overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            p?.let {
                                destinationPoint = it
                                startPoint = locationOverlay?.myLocation
                                if (startPoint != null) {
                                    fetchRoute(context, this@apply, startPoint!!, it) { road -> currentRoad = road }
                                }
                            }
                            return true
                        }
                        override fun longPressHelper(p: GeoPoint?): Boolean = false
                    }))
                    mapView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. PANEL ROUTINGU (Wzorowany na OSRM Demo)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 50.dp, start = 16.dp, end = 16.dp)
                .align(Alignment.TopCenter)
        ) {
            Card(elevation = CardDefaults.cardElevation(8.dp)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    // Start (Punkt A) - Domyślnie Twoja lokalizacja
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Color.Blue, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Moja lokalizacja", style = MaterialTheme.typography.bodyMedium)
                    }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 1.dp)

                    // Cel (Punkt B)
                    TextField(
                        value = destinationQuery,
                        onValueChange = { destinationQuery = it },
                        placeholder = { Text("Wyszukaj cel...") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.Red) },
                        trailingIcon = {
                            if (currentRoad != null) {
                                IconButton(onClick = {
                                    currentRoad = null
                                    destinationQuery = ""
                                    mapView?.overlays?.removeAll { it is Polyline || it is Marker }
                                    mapView?.invalidate()
                                }) { Icon(Icons.Default.Close, null) }
                            }
                        },
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        singleLine = true
                    )
                }
            }

            // Sugestie wyszukiwania
            if (suggestions.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(suggestions) { addr ->
                            // Budujemy pełny opis: Ulica Numer, Miasto (Województwo)
                            val street = addr.thoroughfare ?: ""
                            val houseNum = addr.featureName ?: "" // W OSM featureName często trzyma numer budynku
                            val city = addr.locality ?: addr.adminArea ?: ""
                            val country = addr.countryName ?: ""

                            // Tworzymy czytelną etykietę
                            val mainText = if (street.isNotEmpty()) "$street $houseNum".trim() else addr.featureName ?: "Nieznany punkt"
                            val secondaryText = "$city, $country".trim().trim(',')

                            ListItem(
                                headlineContent = { Text(mainText, fontWeight = FontWeight.Bold) },
                                supportingContent = { Text(secondaryText, style = MaterialTheme.typography.bodySmall) },
                                leadingContent = { Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.Gray) },
                                modifier = Modifier.clickable {
                                    val point = GeoPoint(addr.latitude, addr.longitude)
                                    destinationPoint = point
                                    startPoint = locationOverlay?.myLocation

                                    // Ustawiamy w polu tekstowym pełny adres po kliknięciu
                                    destinationQuery = "$mainText, $city"
                                    suggestions = emptyList() // Zamykamy listę sugestii

                                    if (startPoint != null) {
                                        fetchRoute(context, mapView!!, startPoint!!, point) { road ->
                                            currentRoad = road
                                        }
                                    } else {
                                        // Jeśli nie mamy GPS, tylko przesuń mapę do celu
                                        mapView?.controller?.animateTo(point)
                                    }
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                }
            }

            // LISTA KROKÓW (Turn-by-turn instructions)
            if (currentRoad != null) {
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { isExpanded = !isExpanded }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ArrowForward, null, modifier = Modifier.rotate(-90f))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Trasa: ${String.format("%.2f", currentRoad!!.mLength)} km • ${currentRoad!!.mDuration.toInt() / 60} min",
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (isExpanded) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                                items(currentRoad!!.mNodes) { node ->
                                    if (node.mInstructions != null) {
                                        ListItem(
                                            headlineContent = { Text(node.mInstructions) },
                                            leadingContent = { Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(16.dp)) },
                                            supportingContent = { Text("${(node.mLength * 1000).toInt()} m") }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. PRZYCISK LOKALIZACJI
        FloatingActionButton(
            onClick = {
                locationOverlay?.let {
                    it.enableFollowLocation()
                    it.myLocation?.let { loc -> mapView?.controller?.animateTo(loc) }
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            // Zmieniono z Icons.Default.MyLocation na Icons.Default.LocationOn
            Icon(Icons.Default.LocationOn, contentDescription = "Moja lokalizacja")
        }
    }
}
// Pomocnicza funkcja do ustawiania celu i rysowania trasy
private fun setDestinationAndRoute(
    target: GeoPoint,
    mapView: MapView,
    locationOverlay: MyLocationNewOverlay?,
    context: android.content.Context
) {
    // Usuń stare markery i trasy
    mapView.overlays.removeAll { it is Marker || it is Polyline }

    // Dodaj pinezkę
    val marker = Marker(mapView)
    marker.position = target
    marker.title = "Cel"
    mapView.overlays.add(marker)

    // Wyznacz trasę po drogach
    val currentLoc = locationOverlay?.myLocation
    if (currentLoc != null) {
        traceRoute(context, mapView, currentLoc, target)
    }
    mapView.invalidate()
}

// --- EKRAN PROFILU I USTAWIEŃ ---

// Enum sterujący widokiem wewnątrz zakładki Profil
enum class ProfileView { MAIN, EDIT_CONTACTS }

@OptIn(ExperimentalMaterial3Api::class)
// --- ZMIANA W PROFILE SCREEN (dodaj parametr contacts) ---
@Composable
fun ProfileScreen(
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    contacts: List<TrustedContact>, // Zmienione na List dla odczytu
    onContactListChanged: () -> Unit
) {
    var currentView by remember { mutableStateOf(ProfileView.MAIN) }

    when (currentView) {
        ProfileView.MAIN -> {
            ProfileMainView(
                isDarkTheme = isDarkTheme,
                onThemeChanged = onThemeChanged,
                onEditContactsClick = { currentView = ProfileView.EDIT_CONTACTS },
                contacts = contacts // Przekazujemy listę
            )
        }
        ProfileView.EDIT_CONTACTS -> {
            ContactsEditorView(
                contacts = contacts as MutableList<TrustedContact>,
                onBack = { currentView = ProfileView.MAIN },
                onContactListChanged = onContactListChanged
            )
        }
    }
}

// --- ZMIANA W PROFILE MAIN VIEW ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileMainView(
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    onEditContactsClick: () -> Unit,
    contacts: List<TrustedContact> // Dodany parametr
) {
    val context = LocalContext.current

    // Launcher dla uprawnień SMS
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Jeśli przyznano po kliknięciu - wyślij do wszystkich
            contacts.forEach { contact ->
                sendSmsAlert(context, contact.phone, "To jest testowy alert z aplikacji NightGuard!")
            }
            Toast.makeText(context, "Wysłano testowe wiadomości", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Brak uprawnień do SMS", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Profil i Ustawienia") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // ... (reszta Twojego istniejącego kodu UI: Nagłówek, Motyw itp.) ...

            // Nagłówek użytkownika (skrócony opis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 32.dp)) {
                Box(modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text("JA", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("Jan Kowalski", style = MaterialTheme.typography.titleLarge)
                    Text("jan.kowalski@student.pwr.edu.pl", color = Color.Gray)
                }
            }

            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Sekcja: Wygląd
            Text("Preferencje Aplikacji", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            ListItem(
                headlineContent = { Text("Ciemny motyw") },
                trailingContent = { Switch(checked = isDarkTheme, onCheckedChange = onThemeChanged) }
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text("Bezpieczeństwo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

            ListItem(
                headlineContent = { Text("Zarządzaj Zaufanymi Kontaktami") },
                leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                modifier = Modifier.clickable { onEditContactsClick() }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- TESTOWY PRZYCISK ---
            Button(
                onClick = {
                    if (contacts.isEmpty()) {
                        Toast.makeText(context, "Dodaj najpierw kontakty!", Toast.LENGTH_SHORT).show()
                    } else {
                        // Sprawdzanie uprawnień przed wysłaniem
                        val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
                        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                            contacts.forEach { contact ->
                                sendSmsAlert(context, contact.phone, "Testowy alert z aplikacji NightGuard!")
                            }
                            Toast.makeText(context, "Wysłano test do ${contacts.size} osób", Toast.LENGTH_SHORT).show()
                        } else {
                            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Wyślij testowy SMS do wszystkich")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsEditorView(
    contacts: MutableList<TrustedContact>,
    onBack: () -> Unit,
    onContactListChanged: () -> Unit // Nowy parametr
) {
    var newName by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }
    var contactToEdit by remember { mutableStateOf<TrustedContact?>(null) }

    if (contactToEdit != null) {
        EditContactDialog(
            contact = contactToEdit!!,
            onDismiss = { contactToEdit = null },
            onSave = { updatedContact ->
                val index = contacts.indexOfFirst { it.id == updatedContact.id }
                if (index != -1) {
                    contacts[index] = updatedContact
                    onContactListChanged() // ZAPIS po edycji
                }
                contactToEdit = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edycja Kontaktów") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wróć")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("Dodaj nowy kontakt", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Nazwa (np. Mama)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = newPhone,
                onValueChange = { newPhone = it },
                label = { Text("Numer telefonu") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (newName.isNotBlank() && newPhone.isNotBlank()) {
                        val newId = (contacts.maxOfOrNull { it.id } ?: 0) + 1
                        contacts.add(TrustedContact(id = newId, name = newName, phone = newPhone))
                        onContactListChanged() // ZAPIS po dodaniu
                        newName = ""
                        newPhone = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Dodaj kontakt")
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text("Twoja lista:", style = MaterialTheme.typography.titleMedium)

            LazyColumn {
                items(contacts) { contact ->
                    ListItem(
                        headlineContent = { Text(contact.name) },
                        supportingContent = { Text(contact.phone) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { contactToEdit = contact }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edytuj", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = {
                                    contacts.remove(contact)
                                    onContactListChanged() // ZAPIS po usunięciu
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Usuń", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray)
                }
            }
        }
    }
}
@Composable
fun EditContactDialog(
    contact: TrustedContact,
    onDismiss: () -> Unit,
    onSave: (TrustedContact) -> Unit
) {
    // Lokalne stany dla pól w dialogu, zainicjowane danymi kontaktu
    var name by remember { mutableStateOf(contact.name) }
    var phone by remember { mutableStateOf(contact.phone) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Edytuj kontakt") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nazwa") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Numer telefonu") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && phone.isNotBlank()) {
                        // Tworzymy kopię kontaktu z nowymi danymi, ale tym samym ID
                        onSave(contact.copy(name = name, phone = phone))
                    }
                }
            ) {
                Text("Zapisz")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Anuluj")
            }
        }
    )
}

fun fetchRoute(context: Context, mapView: MapView, start: GeoPoint, dest: GeoPoint, onLoaded: (Road) -> Unit) {
    val roadManager = OSRMRoadManager(context, "NightGuard/0.1.3")
    roadManager.setService("https://router.project-osrm.org/route/v1/walking/")

    GlobalScope.launch(Dispatchers.IO) {
        val road = roadManager.getRoad(arrayListOf(start, dest))
        withContext(Dispatchers.Main) {
            if (road.mStatus == Road.STATUS_OK) {
                // Czyścimy stare trasy i markery
                mapView.overlays.removeAll { it is Polyline || it is Marker }

                // Rysujemy trasę
                val roadOverlay = RoadManager.buildRoadOverlay(road)
                mapView.overlays.add(roadOverlay)

                // Dodajemy Marker startu i końca
                val startMarker = Marker(mapView).apply { position = start; title = "Start" }
                val destMarker = Marker(mapView).apply { position = dest; title = "Cel" }
                mapView.overlays.add(startMarker)
                mapView.overlays.add(destMarker)

                mapView.invalidate()
                onLoaded(road)
            }
        }
    }
}