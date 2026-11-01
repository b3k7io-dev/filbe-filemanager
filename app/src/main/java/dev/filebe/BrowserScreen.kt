package dev.filebe

import android.content.Context
import android.net.http.SslError
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Folder downloads from the in-app browser are saved to, under shared storage. */
const val DOWNLOADS_SUBPATH = "Filbe/Downloads"

private const val FALLBACK_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

/**
 * The stock WebView user agent carries a "wv" marker (and "Version/x.y") that flags it as an embedded
 * browser; Google and several AI chat sites blank or block pages for that. Strip those markers so the
 * pages see a regular mobile Chrome that matches the installed WebView version.
 */
private fun browserUserAgent(context: Context): String {
    val base = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull() ?: return FALLBACK_USER_AGENT
    return base.replace("; wv", "").replace(Regex("Version/\\d+(\\.\\d+)? "), "")
}

private fun uniqueFile(dir: File, name: String): File {
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while (candidate.exists()) { candidate = File(dir, "$base ($i)$ext"); i++ }
    return candidate
}

private suspend fun downloadToFilbe(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, vm: FilebeViewModel) {
    withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                userAgent?.let { setRequestProperty("User-Agent", it) }
                CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
            }
            connection.connect()
            if (connection.responseCode !in 200..299) throw java.io.IOException("HTTP ${connection.responseCode}")
            val suggested = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val dir = File(Environment.getExternalStorageDirectory(), DOWNLOADS_SUBPATH).apply { mkdirs() }
            val target = uniqueFile(dir, suggested)
            connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            withContext(Dispatchers.Main) { vm.notify("Сохранено: $DOWNLOADS_SUBPATH/${target.name}") }
        } catch (error: Exception) {
            withContext(Dispatchers.Main) { vm.notify("Не удалось скачать файл: ${error.message ?: "неизвестная ошибка"}") }
        } finally {
            connection?.disconnect()
        }
    }
}

/** Plain holder (not Compose state) for the live WebView, so toolbar actions can reach it. */
private class WebHolder { var view: WebView? = null }

/**
 * Browser tab. It opens on a start page where a site is chosen; the address bar accepts a URL or a
 * search query. Only HTTPS pages are loaded.
 */
@Composable fun BrowserScreen(state: UiState, vm: FilebeViewModel) {
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val holder = remember { WebHolder() }

    var browsing by remember { mutableStateOf(state.browserUrl != null) }
    var startUrl by remember { mutableStateOf(state.browserUrl) }
    var currentUrl by remember { mutableStateOf(state.browserUrl) }
    var address by remember { mutableStateOf(state.browserUrl ?: "") }
    var fieldFocused by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }

    // Remember the page when the tab is left so it reopens where the user was.
    val lastPage = rememberUpdatedState(if (browsing) currentUrl else null)
    DisposableEffect(Unit) { onDispose { vm.rememberBrowser(lastPage.value) } }

    fun open(url: String) {
        address = url
        currentUrl = url
        val view = holder.view
        if (browsing && view != null) view.loadUrl(url)
        else { startUrl = url; browsing = true }   // the WebView is created on the next composition
    }
    fun goStart() {
        focus.clearFocus()
        browsing = false
        loading = false
        currentUrl = null
        address = ""
    }
    fun goBack() {
        val view = holder.view
        if (browsing && view != null && view.canGoBack()) view.goBack() else goStart()
    }

    BackHandler(enabled = browsing) { goBack() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(onClick = { goBack() }, enabled = browsing, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.ArrowBack, "Назад")
            }
            OutlinedTextField(
                value = address, onValueChange = { address = it },
                modifier = Modifier.weight(1f).onFocusChanged { fieldFocused = it.isFocused },
                singleLine = true, textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = { Text("Поиск или адрес сайта") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    val target = resolveAddress(address)
                    if (target != null) { focus.clearFocus(); open(target) }
                })
            )
            if (browsing) {
                IconButton(onClick = { holder.view?.reload() }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Refresh, "Обновить") }
                IconButton(onClick = { goStart() }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Home, "Список сайтов") }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.MoreVert, "Ещё") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Добавить в мои сайты") }, onClick = {
                            menu = false
                            val url = holder.view?.url ?: currentUrl
                            if (url != null) vm.addSite(Site(siteTitle(url), url))
                        })
                        DropdownMenuItem(text = { Text("Скопировать ссылку") }, onClick = {
                            menu = false
                            val url = holder.view?.url ?: currentUrl
                            if (url != null) { clipboard.setText(AnnotatedString(url)); vm.notify("Ссылка скопирована") }
                        })
                    }
                }
            }
            state.opened?.let { doc ->
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(doc.text))
                    vm.notify("Код скопирован. Вставьте его на сайте вручную.")
                }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.ContentCopy, "Скопировать код") }
            }
        }
        if (browsing && loading) LinearProgressIndicator(progress = { loadProgress / 100f }, modifier = Modifier.fillMaxWidth())

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (browsing) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                    WebView(ctx).apply {
                        holder.view = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = browserUserAgent(ctx)
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.allowFileAccessFromFileURLs = false
                        settings.allowUniversalAccessFromFileURLs = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { loadProgress = newProgress }
                        }
                        setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
                            vm.notify("Загрузка начата…")
                            scope.launch { downloadToFilbe(downloadUrl, userAgent, contentDisposition, mimeType, vm) }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val uri = request.url
                                if (uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) return false
                                vm.notify("Заблокирован небезопасный или локальный адрес")
                                return true
                            }
                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                currentUrl = url; if (!fieldFocused) address = url
                                loading = true; loadProgress = 0
                            }
                            override fun onPageFinished(view: WebView, url: String) {
                                currentUrl = url; if (!fieldFocused) address = url
                                loading = false
                            }
                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                // Single-page sites (Google AI Mode, chat UIs) change URL without a page load.
                                currentUrl = url; if (!fieldFocused) address = url
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) { loading = false; vm.notify("Страница не загрузилась: ${error.description}") }
                            }
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                loading = false
                                handler.cancel()
                                vm.notify("Ошибка сертификата HTTPS. Переход отменён.")
                            }
                        }
                        loadUrl(startUrl ?: BUILTIN_SITES.first().url)
                    }
                })
                // Destroy the WebView only when it actually leaves the screen (back to the start page or
                // another tab). This runs after the view has been detached from the hierarchy.
                DisposableEffect(Unit) {
                    onDispose {
                        holder.view?.let { view ->
                            (view.parent as? ViewGroup)?.removeView(view)
                            view.stopLoading()
                            view.destroy()
                        }
                        holder.view = null
                    }
                }
            } else {
                StartPage(state.sites, onOpen = { open(it.url) }, onRemove = { vm.removeSite(it) })
            }
        }
    }
}

@Composable private fun StartPage(custom: List<Site>, onOpen: (Site) -> Unit, onRemove: (Site) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Выберите сайт или введите адрес", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        }
        items(BUILTIN_SITES) { site -> SiteTile(site, removable = false, onOpen = { onOpen(site) }, onRemove = {}) }
        if (custom.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Мои сайты", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            }
            items(custom) { site -> SiteTile(site, removable = true, onOpen = { onOpen(site) }, onRemove = { onRemove(site) }) }
        }
    }
}

@Composable private fun SiteTile(site: Site, removable: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).glass().clickable(onClick = onOpen)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(44.dp).background(colors.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text(site.title.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = colors.onSecondaryContainer)
            }
            Spacer(Modifier.height(8.dp))
            Text(site.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        }
        if (removable) {
            IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                Icon(Icons.Outlined.Close, "Убрать из списка", Modifier.size(16.dp))
            }
        }
    }
}
