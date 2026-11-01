package dev.filebe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: FilebeViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (state.theme) { ThemeMode.SYSTEM -> systemDark; ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            FilebeTheme(dark) { FilebeApp(vm, state) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FilebeApp(vm: FilebeViewModel, state: UiState) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    var allFiles by remember { mutableStateOf(hasStorage(context)) }
    var themeMenu by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    val currentFolder = rememberUpdatedState(state.folder?.id)

    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        allFiles = hasStorage(context)
        if (allFiles && currentFolder.value == null) vm.shared()
        else if (allFiles) vm.refresh()
    }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allFiles = hasStorage(context)
        if (allFiles && currentFolder.value == null) vm.shared()
        else if (!allFiles) vm.notify("Доступ не выдан. Разрешение можно включить в настройках приложения.")
    }
    val requestPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                settings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
            } catch (_: Exception) {
                try { settings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                catch (_: Exception) { vm.notify("Не удалось открыть системные настройки доступа к файлам.") }
            }
        } else legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFiles = hasStorage(context)
                if (hasStorage(context) && currentFolder.value == null) vm.shared()
                else if (hasStorage(context)) vm.refresh()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        vm.clearMessage()
        scope.launch { snackbar.showSnackbar(message) }
    }
    BackHandler(enabled = state.tab != Tab.FILES || (state.folder != null && state.folder.id != Environment.getExternalStorageDirectory().absolutePath) || state.opened?.dirty == true) {
        if (state.tab != Tab.FILES) vm.tab(Tab.FILES)
        else if (state.folder != null && state.folder.id != Environment.getExternalStorageDirectory().absolutePath) vm.up()
        else if (state.opened?.dirty == true) confirmExit = true
    }
    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false }, title = { Text("Выйти без сохранения?") },
        text = { Text("В редакторе есть несохранённые изменения.") },
        confirmButton = { TextButton(onClick = { confirmExit = false; activity?.finish() }) { Text("Выйти") } },
        dismissButton = { TextButton(onClick = { confirmExit = false; vm.tab(Tab.OPEN) }) { Text("К редактору") } })

    val destinations = listOf(Triple(Tab.FILES, "Файлы", Icons.Outlined.Folder), Triple(Tab.OPEN, "Редактор", Icons.Outlined.Code), Triple(Tab.BROWSER, "Браузер", Icons.Outlined.Language))
    LiquidBackground {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Filbe", style = MaterialTheme.typography.titleLarge)
                        Text(when (state.tab) { Tab.FILES -> "Файлы устройства"; Tab.OPEN -> "Редактор кода"; Tab.BROWSER -> "Браузер" },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { themeMenu = true }) { Icon(Icons.Outlined.Palette, contentDescription = "Тема") }
                        DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                            listOf(ThemeMode.SYSTEM to "Как в системе", ThemeMode.LIGHT to "Светлая", ThemeMode.DARK to "Тёмная").forEach { (mode, title) ->
                                DropdownMenuItem(text = { Text(title) }, onClick = { vm.theme(mode); themeMenu = false })
                            }
                        }
                    }
                }
            },
            bottomBar = {
                NavigationBar(modifier = Modifier.height(68.dp), containerColor = MaterialTheme.colorScheme.surface) {
                    destinations.forEach { (tab, title, icon) ->
                        NavigationBarItem(selected = state.tab == tab, onClick = { vm.tab(tab) }, icon = { Icon(icon, contentDescription = title) }, label = { Text(title) })
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (state.tab) {
                    Tab.FILES -> FilesScreen(state, vm, allFiles, requestPermission)
                    Tab.OPEN -> EditorScreen(state, vm)
                    Tab.BROWSER -> BrowserScreen(state, vm)
                }
            }
        }
    }
}

private fun hasStorage(context: android.content.Context): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
