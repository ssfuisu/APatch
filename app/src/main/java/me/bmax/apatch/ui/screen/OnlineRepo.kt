package me.bmax.apatch.ui.screen

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.InstallScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.apApp
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URI

data class OnlineModule(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val description: String,
    val downloadUrl: String,
    val sourceUrl: String,
    val category: String,
    val stars: Int = 0,
    val type: MODULE_TYPE = MODULE_TYPE.APM
)

private const val API_V2_URL = "https://raw.githubusercontent.com/Magisk-Modules-Alt-Repo/json-v2/main/json/modules.json"
private const val API_V1_URL = "https://raw.githubusercontent.com/Magisk-Modules-Alt-Repo/json/main/modules.json"

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineRepoScreen(navigator: DestinationsNavigator) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    val categories = listOf("All", "Security", "Zygisk", "System", "Audio", "UI")
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    val modulesList = remember { mutableStateListOf<OnlineModule>() }

    fun fetchModules() {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            val fetched = mutableListOf<OnlineModule>()
            try {
                // 1. Try V2 API
                val req = Request.Builder().url(API_V2_URL).build()
                apApp.okhttpClient.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val rootJson = JSONObject(body)
                        val modulesArray = rootJson.optJSONArray("modules")
                        if (modulesArray != null) {
                            for (i in 0 until modulesArray.length()) {
                                val item = modulesArray.optJSONObject(i) ?: continue
                                val id = item.optString("id", "")
                                val name = item.optString("name", id).ifBlank { id }
                                val author = item.optString("author", "Unknown")
                                val version = item.optString("version", "1.0")
                                val description = item.optString("description", "")
                                val stars = item.optInt("stars", 0)
                                val support = item.optString("support", "https://github.com/Magisk-Modules-Alt-Repo/$id")

                                var downloadUrl = ""
                                val versionsArray = item.optJSONArray("versions")
                                if (versionsArray != null && versionsArray.length() > 0) {
                                    val firstVer = versionsArray.optJSONObject(0)
                                    downloadUrl = firstVer?.optString("zipUrl", "") ?: ""
                                }

                                if (downloadUrl.isBlank()) {
                                    downloadUrl = "https://github.com/Magisk-Modules-Alt-Repo/$id/archive/main.zip"
                                }

                                val catLower = (name + " " + description + " " + id).lowercase()
                                val category = when {
                                    catLower.contains("integrity") || catLower.contains("hide") || catLower.contains("shamiko") || catLower.contains("root") || catLower.contains("security") || catLower.contains("protect") -> "Security"
                                    catLower.contains("zygisk") || catLower.contains("xposed") || catLower.contains("hook") || catLower.contains("lsposed") -> "Zygisk"
                                    catLower.contains("audio") || catLower.contains("sound") || catLower.contains("music") || catLower.contains("volume") -> "Audio"
                                    catLower.contains("font") || catLower.contains("icon") || catLower.contains("ui") || catLower.contains("theme") || catLower.contains("blur") -> "UI"
                                    else -> "System"
                                }

                                fetched.add(
                                    OnlineModule(
                                        id = id,
                                        name = name,
                                        author = author,
                                        version = version,
                                        description = description,
                                        downloadUrl = downloadUrl,
                                        sourceUrl = support,
                                        category = category,
                                        stars = stars
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("OnlineRepo", "V2 API failed: ", e)
            }

            // Fallback to V1 API if V2 was empty
            if (fetched.isEmpty()) {
                try {
                    val req = Request.Builder().url(API_V1_URL).build()
                    apApp.okhttpClient.newCall(req).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            val rootJson = JSONObject(body)
                            val modulesArray = rootJson.optJSONArray("modules")
                            if (modulesArray != null) {
                                for (i in 0 until modulesArray.length()) {
                                    val item = modulesArray.optJSONObject(i) ?: continue
                                    val id = item.optString("id", "")
                                    val stars = item.optInt("stars", 0)
                                    val zipUrl = item.optString("zip_url", "")
                                    val sourceUrl = "https://github.com/Magisk-Modules-Alt-Repo/$id"

                                    fetched.add(
                                        OnlineModule(
                                            id = id,
                                            name = id,
                                            author = "Community",
                                            version = "Latest",
                                            description = "Magisk / APatch module from Alt-Repo",
                                            downloadUrl = zipUrl,
                                            sourceUrl = sourceUrl,
                                            category = "System",
                                            stars = stars
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e2: Exception) {
                    Log.e("OnlineRepo", "V1 API failed: ", e2)
                }
            }

            withContext(Dispatchers.Main) {
                modulesList.clear()
                // Sort by stars descending
                fetched.sortByDescending { it.stars }
                modulesList.addAll(fetched)
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        fetchModules()
    }

    val filteredModules = remember(searchQuery, selectedCategory, modulesList.size) {
        modulesList.filter { module ->
            (selectedCategory == "All" || module.category.equals(selectedCategory, ignoreCase = true)) &&
            (searchQuery.isBlank() ||
             module.name.contains(searchQuery, ignoreCase = true) ||
             module.author.contains(searchQuery, ignoreCase = true) ||
             module.description.contains(searchQuery, ignoreCase = true) ||
             module.id.contains(searchQuery, ignoreCase = true))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.online_repo_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { fetchModules() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.online_repo_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(categories) { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Fetching live module repository...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredModules, key = { it.id }) { module ->
                        OnlineModuleCard(
                            module = module,
                            isDownloading = downloadingId == module.id,
                            onInstallClick = {
                                downloadingId = module.id
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val cacheDir = File(context.cacheDir, "modules")
                                        cacheDir.mkdirs()
                                        val targetFile = File(cacheDir, "${module.id}.zip")
                                        if (targetFile.exists()) targetFile.delete()

                                        val conn = URI.create(module.downloadUrl).toURL().openConnection()
                                        conn.connectTimeout = 20000
                                        conn.readTimeout = 60000
                                        conn.getInputStream().use { input ->
                                            targetFile.outputStream().use { output ->
                                                input.copyTo(output)
                                            }
                                        }

                                        withContext(Dispatchers.Main) {
                                            downloadingId = null
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                targetFile
                                            )
                                            navigator.navigate(InstallScreenDestination(uri, module.type))
                                        }
                                    } catch (e: Exception) {
                                        Log.e("OnlineRepo", "Download failed: ", e)
                                        withContext(Dispatchers.Main) {
                                            downloadingId = null
                                            Toast.makeText(context, "Download failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onSourceClick = {
                                uriHandler.openUri(module.sourceUrl)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun OnlineModuleCard(
    module: OnlineModule,
    isDownloading: Boolean,
    onInstallClick: () -> Unit,
    onSourceClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = module.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${module.author} • ${module.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (module.stars > 0) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "Stars",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${module.stars}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    SuggestionChip(
                        onClick = {},
                        label = { Text(module.category) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = module.description.ifBlank { "No description available." },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onSourceClick,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        Icons.Filled.OpenInBrowser,
                        contentDescription = "Source",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Source")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onInstallClick,
                    enabled = !isDownloading,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            Icons.Filled.CloudDownload,
                            contentDescription = "Install",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.online_repo_install))
                    }
                }
            }
        }
    }
}
