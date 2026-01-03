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

@Composable
fun MapScreen() {
    val context = LocalContext.current

    // Stan uprawnień - kluczowy po wyczyszczeniu danych
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var locationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<android.location.Address>>(emptyList()) }

    // 1. Launcher uprawnień - reaguje natychmiast na "Zezwól"
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPermission = granted
    }

    // 2. Obsługa cyklu życia mapy (wymagane przez osmdroid)
    DisposableEffect(mapView) {
        mapView?.onResume()
        onDispose {
            mapView?.onPause()
        }
    }

    // 3. Reakcja na zmianę uprawnień (np. zaraz po kliknięciu "Zezwól")
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            locationOverlay?.enableMyLocation()
            locationOverlay?.enableFollowLocation()
            mapView?.invalidate()
        } else {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    // GŁÓWNY KONTENER
    Box(modifier = Modifier.fillMaxSize()) {

        // WARSTWA 1: MAPA
        AndroidView(
            factory = { ctx ->
                // Załadowanie konfiguracji po czyszczeniu danych
                Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))

                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(15.0)

                    val provider = GpsMyLocationProvider(ctx).apply {
                        addLocationSource(LocationManager.GPS_PROVIDER)
                        addLocationSource(LocationManager.NETWORK_PROVIDER)
                    }

                    val overlay = MyLocationNewOverlay(provider, this)
                    // Jeśli mamy uprawnienia już teraz, włączamy
                    if (hasLocationPermission) {
                        overlay.enableMyLocation()
                    }

                    overlays.add(overlay)
                    locationOverlay = overlay
                    mapView = this

                    // (Tutaj zachowaj swój kod MapEventsOverlay dla pinezki)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // WARSTWA 2: WYSZUKIWARKA (Na górze)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 16.dp, end = 16.dp)
        ) {
            // Tutaj Twój obecny kod OutlinedTextField i Listy podpowiedzi
            // ... (zachowaj go bez zmian)
        }

        // WARSTWA 3: PRZYCISK LOKALIZACJI (FAB)
        // Musi być w Box, ale POZE Column, aby nie uciekał na dół
        FloatingActionButton(
            onClick = {
                if (hasLocationPermission) {
                    locationOverlay?.let {
                        it.enableFollowLocation()
                        val myLoc = it.myLocation
                        if (myLoc != null) {
                            mapView?.controller?.animateTo(myLoc)
                        }
                    }
                } else {
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 32.dp, end = 16.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.LocationOn, contentDescription = "Lokalizacja")
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
@Composable
fun ProfileScreen(
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    contacts: MutableList<TrustedContact>,
    onContactListChanged: () -> Unit // Dodajemy ten parametr
) {
    var currentView by remember { mutableStateOf(ProfileView.MAIN) }

    when (currentView) {
        ProfileView.MAIN -> {
            ProfileMainView(
                isDarkTheme = isDarkTheme,
                onThemeChanged = onThemeChanged,
                onEditContactsClick = { currentView = ProfileView.EDIT_CONTACTS }
            )
        }
        ProfileView.EDIT_CONTACTS -> {
            ContactsEditorView(
                contacts = contacts,
                onBack = { currentView = ProfileView.MAIN },
                onContactListChanged = onContactListChanged // Przekazujemy dalej
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileMainView(
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    onEditContactsClick: () -> Unit
) {
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Profil i Ustawienia") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Nagłówek użytkownika
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 32.dp)) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text("JA", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("Jan Kowalski", style = MaterialTheme.typography.titleLarge)
                    Text("jan.kowalski@student.pwr.edu.pl", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                }
            }

            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Sekcja: Wygląd
            Text("Preferencje Aplikacji", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

            ListItem(
                headlineContent = { Text("Ciemny motyw") },
                trailingContent = {
                    Switch(checked = isDarkTheme, onCheckedChange = onThemeChanged)
                }
            )

            ListItem(
                headlineContent = { Text("Powiadomienia Push") },
                supportingContent = { Text("Otrzymuj alerty o bezpieczeństwie") },
                trailingContent = {
                    var notificationsEnabled by remember { mutableStateOf(true) }
                    Switch(checked = notificationsEnabled, onCheckedChange = { notificationsEnabled = it })
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Sekcja: Bezpieczeństwo
            Text("Bezpieczeństwo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

            ListItem(
                headlineContent = { Text("Zarządzaj Zaufanymi Kontaktami") },
                supportingContent = { Text("Dodaj lub usuń osoby powiadamiane") },
                leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                modifier = Modifier.clickable { onEditContactsClick() }
            )

            ListItem(
                headlineContent = { Text("Czułość wykrywania trasy") },
                supportingContent = { Text("Tolerancja zboczenia: 50m") },
                leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) }
            )
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
