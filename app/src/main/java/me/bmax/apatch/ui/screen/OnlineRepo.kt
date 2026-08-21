package me.bmax.apatch.ui.screen

import android.net.Uri
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    val type: MODULE_TYPE = MODULE_TYPE.APM
)

private val curatedModules = listOf(
    OnlineModule(
        id = "playintegrityfix",
        name = "Play Integrity Fix",
        author = "chiteroman",
        version = "v18.5",
        description = "Fixes Google Play Integrity verdicts to pass BASIC and DEVICE integrity on rooted devices.",
        downloadUrl = "https://github.com/chiteroman/PlayIntegrityFix/releases/latest/download/PlayIntegrityFix.zip",
        sourceUrl = "https://github.com/chiteroman/PlayIntegrityFix",
        category = "Integrity"
    ),
    OnlineModule(
        id = "zygisk-next",
        name = "Zygisk-Next",
        author = "Dr-TSNG",
        version = "v1.2.5",
        description = "Standalone Zygisk implementation for KernelSU and APatch with high compatibility.",
        downloadUrl = "https://github.com/Dr-TSNG/ZygiskNext/releases/latest/download/Zygisk-Next.zip",
        sourceUrl = "https://github.com/Dr-TSNG/ZygiskNext",
        category = "Zygisk"
    ),
    OnlineModule(
        id = "shamiko",
        name = "Shamiko",
        author = "LSPosed Devs",
        version = "v1.1.1",
        description = "Zygisk module to hide root, zygisk and modules from banking and sensitive apps.",
        downloadUrl = "https://github.com/LSPosed/Shamiko/releases/latest/download/Shamiko.zip",
        sourceUrl = "https://github.com/LSPosed/Shamiko",
        category = "Integrity"
    ),
    OnlineModule(
        id = "trickystore",
        name = "Tricky Store",
        author = "5ec1cff",
        version = "v1.2.0",
        description = "A trick for keystore hardware attestation to pass STRONG_INTEGRITY verdicts.",
        downloadUrl = "https://github.com/5ec1cff/TrickyStore/releases/latest/download/TrickyStore.zip",
        sourceUrl = "https://github.com/5ec1cff/TrickyStore",
        category = "Integrity"
    ),
    OnlineModule(
        id = "lsposed-zygisk",
        name = "LSPosed (Zygisk)",
        author = "LSPosed",
        version = "v1.9.3",
        description = "The modern Xposed framework implementation for Android with Zygisk support.",
        downloadUrl = "https://github.com/LSPosed/LSPosed/releases/latest/download/LSPosed-v1.9.3-7244-zygisk-release.zip",
        sourceUrl = "https://github.com/LSPosed/LSPosed",
        category = "Zygisk"
    ),
    OnlineModule(
        id = "systemless-hosts",
        name = "Systemless Hosts",
        author = "APatch",
        version = "1.0",
        description = "Provides a systemless /system/etc/hosts file for AdAway and content blocking.",
        downloadUrl = "https://github.com/gloeyq/systemless-hosts/releases/latest/download/systemless-hosts.zip",
        sourceUrl = "https://github.com/gloeyq/systemless-hosts",
        category = "System"
    ),
    OnlineModule(
        id = "busybox-ndk",
        name = "Busybox for Android NDK",
        author = "osm0sis",
        version = "1.36.1",
        description = "Static busybox binaries built with Android NDK for all architectures.",
        downloadUrl = "https://github.com/Magisk-Modules-Repo/busybox-ndk/releases/latest/download/busybox-ndk.zip",
        sourceUrl = "https://github.com/Magisk-Modules-Repo/busybox-ndk",
        category = "System"
    ),
    OnlineModule(
        id = "bootloader-spoofer",
        name = "Bootloader Spoofer",
        author = "chiteroman",
        version = "v1.1",
        description = "Spoofs bootloader locked state in kernel and user properties.",
        downloadUrl = "https://github.com/chiteroman/BootloaderSpoofer/releases/latest/download/BootloaderSpoofer.zip",
        sourceUrl = "https://github.com/chiteroman/BootloaderSpoofer",
        category = "Integrity"
    )
)

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineRepoScreen(navigator: DestinationsNavigator) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    val categories = listOf("All", "Integrity", "Zygisk", "System")
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var downloadingId by remember { mutableStateOf<String?>(null) }

    val filteredModules = remember(searchQuery, selectedCategory) {
        curatedModules.filter { module ->
            (selectedCategory == "All" || module.category.equals(selectedCategory, ignoreCase = true)) &&
            (searchQuery.isBlank() ||
             module.name.contains(searchQuery, ignoreCase = true) ||
             module.author.contains(searchQuery, ignoreCase = true) ||
             module.description.contains(searchQuery, ignoreCase = true))
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
                                    conn.connectTimeout = 15000
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

                SuggestionChip(
                    onClick = {},
                    label = { Text(module.category) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = module.description,
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
