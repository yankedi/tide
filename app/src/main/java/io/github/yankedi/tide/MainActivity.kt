package io.github.yankedi.tide

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import android.system.Os
import android.system.OsConstants

lateinit var PREFIX: String
lateinit var HOME: String

sealed class InitState {
    data object Checking : InitState()
    data class Downloading(val progress: Float) : InitState()
    data object Extracting : InitState()
    data object Ready : InitState()
    data class Error(val message: String) : InitState()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PREFIX = File(filesDir, "usr").absolutePath
        HOME = File(filesDir, "home").absolutePath
        File(HOME).mkdirs()
        File(PREFIX).mkdirs()

        checkStoragePermission()
        val hasInternet = checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
        android.util.Log.d("Tide", "Has internet permission: $hasInternet")
        enableEdgeToEdge()
        setContent{
            val context = LocalContext.current
            val isDarkTheme = isSystemInDarkTheme()
            val colorScheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    if (isDarkTheme) dynamicDarkColorScheme(context)
                    else dynamicLightColorScheme(context)
                }
                isDarkTheme -> darkColorScheme()
                else -> lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                MainScreen()
            }
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = "package:${packageName}".toUri()
                startActivity(intent)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun MainScreen(viewModel: TerminalViewModel = viewModel()) {
    val context = LocalContext.current
    val hasInternet = remember {
        context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ){
                FileMenu()
                TextButton(
                    onClick = { viewModel.sendCommand("ls") },
                    shape = RoundedCornerShape(12.dp),
                ){
                    Text("Code")
                }
                TextButton(
                    onClick = { viewModel.sendCommand("git status") },
                    shape = RoundedCornerShape(12.dp),
                ){
                    Text("Git")
                }
                TextButton(
                    onClick = { viewModel.sendCommand("./run.sh") },
                    shape = RoundedCornerShape(12.dp),
                ){
                    Text("Run")
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = if (hasInternet) "Online" else "Offline",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasInternet) Color(0xFF4CAF50) else Color(0xFFF44336),
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        },
    ){ innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if(checkEnvironment()) Terminal(viewModel = viewModel)
        }
    }
}

@Composable
fun checkEnvironment(): Boolean {
    val context = LocalContext.current
    var state by remember { mutableStateOf<InitState>(InitState.Checking) }

    LaunchedEffect(Unit) {
        val usrDir = File(PREFIX)
        val shFile = File(usrDir, "bin/sh")
        val prootFile = File(usrDir, "bin/proot")

        if (shFile.exists() && prootFile.exists()) {
            state = InitState.Ready
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            try {
                // 1. 获取最新 Release
                if (!shFile.exists()) {
                    val client = OkHttpClient()
                    val request = Request.Builder()
                        .url("https://api.github.com/repos/termux/termux-packages/releases/latest")
                        .build()

                    val response = client.newCall(request).execute()
                    val jsonBody = response.body?.string() ?: throw Exception("Empty response")
                    val json = JSONObject(jsonBody)
                    val assets = json.getJSONArray("assets")

                    val abi = Build.SUPPORTED_ABIS[0]
                    val targetName = when {
                        abi.contains("arm64") || abi.contains("aarch64") -> "bootstrap-aarch64.zip"
                        abi.contains("armeabi") || abi.contains("arm") -> "bootstrap-arm.zip"
                        abi.contains("x86_64") -> "bootstrap-x86_64.zip"
                        else -> "bootstrap-i686.zip"
                    }

                    var downloadUrl = ""
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.getString("name") == targetName) {
                            downloadUrl = asset.getString("browser_download_url")
                            break
                        }
                    }

                    if (downloadUrl.isEmpty()) throw Exception("No bootstrap found for $abi")

                    // 2. 下载
                    val downloadFile = File(context.cacheDir, "bootstrap.zip")
                    val downloadRequest = Request.Builder().url(downloadUrl).build()
                    client.newCall(downloadRequest).execute().use { downloadResponse ->
                        val body = downloadResponse.body ?: throw Exception("Download failed")
                        val totalSize = body.contentLength()
                        body.byteStream().use { input ->
                            FileOutputStream(downloadFile).use { output ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                var downloaded = 0L
                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    state = InitState.Downloading(if (totalSize > 0) downloaded.toFloat() / totalSize else 0f)
                                }
                            }
                        }
                    }

                    // 3. 解压
                    state = InitState.Extracting
                    unzip(downloadFile, usrDir)
                    downloadFile.delete()
                }

                // 4. 仅提取 proot，不影响 sh
                if (!prootFile.exists()) {
                    state = InitState.Extracting
                    val abi = Build.SUPPORTED_ABIS[0]
                    val assetAbiDir = when {
                        abi.contains("arm64") || abi.contains("aarch64") -> "arm64-v8a"
                        abi.contains("armeabi") || abi.contains("arm") -> "armeabi-v7a"
                        abi.contains("x86_64") -> "x86_64"
                        else -> "x86"
                    }
                    val assetPath = "bin/$assetAbiDir/proot"
                    context.assets.open(assetPath).use { input ->
                        prootFile.parentFile?.mkdirs()
                        FileOutputStream(prootFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Os.chmod(prootFile.absolutePath, 448) // 700
                }

                state = InitState.Ready
            } catch (e: Exception) {
                state = InitState.Error(e.message ?: "Unknown error")
            }
        }
    }

    when (val s = state) {
        is InitState.Ready -> return true
        is InitState.Checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Checking environment...", Modifier.padding(top = 16.dp))
            }
        }
        is InitState.Downloading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 使用 Float 参数兼容性更好
                LinearProgressIndicator(progress = s.progress)
                Text("Downloading bootstrap: ${(s.progress * 100).toInt()}%", Modifier.padding(top = 16.dp))
            }
        }
        is InitState.Extracting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Extracting...", Modifier.padding(top = 16.dp))
            }
        }
        is InitState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Error: ${s.message}", color = Color.Red) }
    }

    return false
}

private fun unzip(zipFile: File, targetDir: File) {
    ZipInputStream(zipFile.inputStream()).use { zis ->
        var entry: ZipEntry? = zis.nextEntry
        while (entry != null) {
            val file = File(targetDir, entry.name)
            if (entry.isDirectory) {
                file.mkdirs()
            } else {
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { fos ->
                    zis.copyTo(fos)
                }
                // 设置执行权限
                if (file.path.contains("/bin/") || file.path.contains("/lib/") || file.path.contains("/libexec/")) {
                    try {
                        Os.chmod(file.absolutePath, OsConstants.S_IRWXU)
                    } catch (e: Exception) {
                        file.setExecutable(true, true)
                    }
                }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }

    // 处理软链接
    val symlinksFile = File(targetDir, "SYMLINKS.txt")
    if (symlinksFile.exists()) {
        symlinksFile.readLines().forEach { line ->
            val parts = line.split("←")
            if (parts.size == 2) {
                val target = parts[0].trim()
                val link = parts[1].trim()

                try {
                    val linkFile = File(targetDir, link)
                    linkFile.parentFile?.mkdirs()

                    if (linkFile.exists() || linkFile.canonicalFile.exists()) {
                        linkFile.delete()
                    }

                    Os.symlink(target, linkFile.absolutePath)

                } catch (e: Exception) {
                    android.util.Log.e("Tide", "Failed to create symlink: $link -> $target", e)
                }
            }
        }
    }
}

@Composable
fun FileMenu(){
    var expanded by remember { mutableStateOf(false) }
    Box{
        TextButton(
            onClick = {expanded = true },
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("File")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(text = {Text("New Project")}, onClick = { expanded = false })
            DropdownMenuItem(text = {Text("Open...")}, onClick = { expanded = false })
            DropdownMenuItem(text = {Text("Settings...")}, onClick = { expanded = false })
            DropdownMenuItem(text = {Text("Save")}, onClick = { expanded = false })
        }
    }
}


@Composable
fun Terminal(modifier: Modifier = Modifier, viewModel: TerminalViewModel = viewModel()) {
    var terminalViewRef by remember { mutableStateOf<TerminalView?>(null) }
    var textSize by remember { mutableFloatStateOf(40f) }
    val tintedDarkBg = MaterialTheme.colorScheme.primary
        .copy(alpha = 0.1f)
        .compositeOver(Color(0xFF101014))

    val terminalBgColorInt = tintedDarkBg.toArgb()

    val terminalSession = remember {
        val envs = arrayOf(
            "TERM=xterm-256color",
            "HOME=$HOME",
            "PREFIX=$PREFIX",
            "LD_LIBRARY_PATH=$PREFIX/lib",
            "PATH=$PREFIX/bin:$PREFIX/bin/applets",
            "LANG=en_US.UTF-8"
        )

        viewModel.getOrCreateSession(
            "$PREFIX/bin/sh",
            HOME,
            null, // args 适配 ViewModel
            envs,
            object : TerminalSessionClient {
                override fun onTextChanged(changedSession: TerminalSession) {
                    terminalViewRef?.onScreenUpdated()
                }

                override fun onTitleChanged(changedSession: TerminalSession) {}
                override fun onSessionFinished(finishedSession: TerminalSession) {}
                override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
                override fun onPasteTextFromClipboard(session: TerminalSession?) {}
                override fun onBell(session: TerminalSession) {}
                override fun onColorsChanged(session: TerminalSession) {}
                override fun onTerminalCursorStateChange(state: Boolean) {}

                override fun getTerminalCursorStyle(): Int = 0
                override fun logError(tag: String?, message: String?) {}
                override fun logWarn(tag: String?, message: String?) {}
                override fun logInfo(tag: String?, message: String?) {}
                override fun logDebug(tag: String?, message: String?) {}
                override fun logVerbose(tag: String?, message: String?) {}
                override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
                override fun logStackTrace(tag: String?, e: Exception?) {}
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    terminalViewRef = this
                    setBackgroundColor(terminalBgColorInt)
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true

                    setTerminalViewClient(object : TerminalViewClient {
                        override fun onScale(scale: Float): Float {
                            val sensitivity = 0.3f
                            val adjustedScale = (scale - 1.0f) * sensitivity + 1.0f

                            val newSize = (textSize * adjustedScale).coerceIn(12f, 100f)
                            if (newSize != textSize) {
                                textSize = newSize
                                this@apply.setTextSize(newSize.toInt())
                            }
                            return scale
                        }

                        override fun onSingleTapUp(e: MotionEvent?) {
                            requestFocus()
                            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.restartInput(this@apply)
                            post {
                                imm.showSoftInput(this@apply, 0)
                            }
                        }

                        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
                        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
                        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
                        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                        override fun isTerminalViewSelected(): Boolean = true
                        override fun copyModeChanged(copyMode: Boolean) {}
                        override fun onLongPress(event: MotionEvent?): Boolean = false
                        override fun readControlKey(): Boolean = false
                        override fun readAltKey(): Boolean = false
                        override fun readShiftKey(): Boolean = false
                        override fun readFnKey(): Boolean = false
                        override fun shouldEnforceCharBasedInput(): Boolean = false
                        override fun onEmulatorSet() {}

                        override fun logError(tag: String?, message: String?) {}
                        override fun logWarn(tag: String?, message: String?) {}
                        override fun logInfo(tag: String?, message: String?) {}
                        override fun logDebug(tag: String?, message: String?) {}
                        override fun logVerbose(tag: String?, message: String?) {}
                        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
                        override fun logStackTrace(tag: String?, e: Exception?) {}
                    })

                    attachSession(terminalSession)
                    viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            if (width > 0 && height > 0) {
                                viewTreeObserver.removeOnGlobalLayoutListener(this)
                                postDelayed({
                                    try {
                                        val updateSizeMethod = this@apply.javaClass.getMethod("updateSize")
                                        updateSizeMethod.invoke(this@apply)
                                        onScreenUpdated()
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }, 100)
                            }
                        }
                    })

                    post {
                        try {
                            val customTypeface = Typeface.createFromAsset(
                                ctx.assets,
                                "fonts/JetBrainsMono-Regular.ttf"
                            )
                            setTypeface(customTypeface)
                        } catch (e: Exception) {
                            setTypeface(Typeface.MONOSPACE)
                        }
                        setTextSize(textSize.toInt())
                    }
                }
            },
            update = { view ->
                terminalViewRef = view
                view.setTextSize(textSize.toInt())
                view.post {
                    view.requestFocus()
                    if (view.width > 0 && view.height > 0) {
                        try {
                            val updateSizeMethod = view.javaClass.getMethod("updateSize")
                            updateSizeMethod.invoke(view)
                        } catch (e: Exception) { }
                        view.onScreenUpdated()
                    }
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            terminalViewRef = null
        }
    }
}
