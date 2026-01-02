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

// Data class dla Kontaktu (na potrzeby edycji w pamięci)
data class TrustedContact(val id: Int, val name: String, val phone: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Zarządzanie motywem na poziomie całej aplikacji
            var isDarkTheme by rememberSaveable { mutableStateOf(false) } // Domyślnie light, lub zmień na isSystemInDarkTheme()

            NightGuardTheme(darkTheme = isDarkTheme) {
                NightGuardApp(
                    isDarkTheme = isDarkTheme,
                    onThemeChanged = { isDarkTheme = it }
                )
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun NightGuardApp(
    isDarkTheme: Boolean = false,
    onThemeChanged: (Boolean) -> Unit = {}
) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

    // Wspólna lista kontaktów (stan wyniesiony wyżej, by był dostępny w Home i w Profile)
    val trustedContacts = remember { mutableStateListOf(
        TrustedContact(1, "Mama", "123-456-789"),
        TrustedContact(2, "Krzysztof", "987-654-321")
    )}

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
                onContactListChanged = { /* Lista jest mutowalna, zmiany dzieją się "w miejscu", ale tu można dodać logikę zapisu do bazy */ }
            )
        }
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

// --- EKRAN MAPY (Placeholder) ---
@Composable
fun MapScreen() {
    val context = LocalContext.current

    // Stan mapy i kontrolera (do centrowania)
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var locationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }

    // Launcher do zapytania o uprawnienia
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val isGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (isGranted) {
            // Jeśli przyznano uprawnienia, włączamy śledzenie
            locationOverlay?.enableMyLocation()
            locationOverlay?.enableFollowLocation()
        }
    }

    // Sprawdzenie uprawnień przy starcie
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName

                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(18.0)

                    // 1. Konfiguracja warstwy lokalizacji
                    // Używamy domyślnego dostawcy (automatycznie wybierze GPS lub Network)
                    val provider = GpsMyLocationProvider(ctx)

                    val overlay = MyLocationNewOverlay(provider, this)
                    overlay.enableMyLocation()
                    overlay.enableFollowLocation()
                    overlay.isDrawAccuracyEnabled = true

                    // Dodajemy warstwę lokalizacji do mapy
                    overlays.add(overlay)
                    locationOverlay = overlay

                    // Wstępne wycentrowanie (Polska)
                    controller.setCenter(GeoPoint(52.0, 19.0))

                    // 2. Obsługa kliknięć (Wyznaczanie celu)
                    val mapEventsReceiver = object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            p?.let { targetPoint ->
                                // Usuwamy stare trasy i markery (zachowując MyLocationOverlay i MapEventsOverlay)
                                overlays.removeAll { it !is MyLocationNewOverlay && it !is MapEventsOverlay }

                                // Dodajemy Marker Celu
                                val marker = Marker(this@apply)
                                marker.position = targetPoint
                                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                marker.title = "Cel podróży"
                                overlays.add(marker)

                                // Rysujemy linię (trasę) od użytkownika do celu
                                val myLoc = overlay.myLocation
                                if (myLoc != null) {
                                    val line = Polyline()
                                    line.addPoint(myLoc)
                                    line.addPoint(targetPoint)
                                    // Kolor linii (niebieski ARGB)
                                    line.color = android.graphics.Color.BLUE
                                    line.width = 10f
                                    overlays.add(line)
                                }
                                invalidate() // Odśwież mapę
                            }
                            return true
                        }

                        override fun longPressHelper(p: GeoPoint?): Boolean = false
                    }
                    overlays.add(MapEventsOverlay(mapEventsReceiver))

                    mapView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Przycisk "Moja Lokalizacja" (FAB)
        FloatingActionButton(
            onClick = {
                locationOverlay?.enableFollowLocation()
                val myLoc = locationOverlay?.myLocation
                if (myLoc != null) {
                    mapView?.controller?.animateTo(myLoc)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            // Zmieniono ikonę na LocationOn (standardowa pinezka), bo NearMe wymaga dodatkowej biblioteki
            Icon(Icons.Default.LocationOn, contentDescription = "Wyśrodkuj")
        }
    }
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
    onContactListChanged: () -> Unit
) {
    var currentView by remember { mutableStateOf(ProfileView.MAIN) }

    // Prosta nawigacja wewnętrzna
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
                onBack = { currentView = ProfileView.MAIN }
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
    onBack: () -> Unit
) {
    // Stan dla pól formularza (dodawanie nowego)
    var newName by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }

    // Stan przechowujący kontakt, który aktualnie edytujemy (null = brak edycji)
    var contactToEdit by remember { mutableStateOf<TrustedContact?>(null) }

    // Jeśli contactToEdit nie jest nullem, pokazujemy dialog
    if (contactToEdit != null) {
        EditContactDialog(
            contact = contactToEdit!!,
            onDismiss = { contactToEdit = null },
            onSave = { updatedContact ->
                // Logika aktualizacji: znajdź indeks i podmień obiekt
                val index = contacts.indexOfFirst { it.id == updatedContact.id }
                if (index != -1) {
                    contacts[index] = updatedContact
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
            // --- SEKCJA 1: Formularz dodawania ---
            Text("Dodaj nowy kontakt", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Nazwa (np. Mama)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = newPhone,
                onValueChange = { newPhone = it },
                label = { Text("Numer telefonu") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) }
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (newName.isNotBlank() && newPhone.isNotBlank()) {
                        // Generowanie ID (w prawdziwej bazie robi to autoincrement)
                        val newId = (contacts.maxOfOrNull { it.id } ?: 0) + 1
                        contacts.add(TrustedContact(id = newId, name = newName, phone = newPhone))
                        newName = ""
                        newPhone = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Dodaj kontakt")
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // --- SEKCJA 2: Lista kontaktów ---
            Text("Twoja lista:", style = MaterialTheme.typography.titleMedium)

            LazyColumn {
                items(contacts) { contact ->
                    ListItem(
                        headlineContent = { Text(contact.name) },
                        supportingContent = { Text(contact.phone) },
                        trailingContent = {
                            Row {
                                // Przycisk Edycji
                                IconButton(onClick = { contactToEdit = contact }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Edytuj",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                // Przycisk Usuwania
                                IconButton(onClick = { contacts.remove(contact) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Usuń",
                                        tint = MaterialTheme.colorScheme.error
                                    )
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