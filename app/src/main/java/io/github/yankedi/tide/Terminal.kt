package io.github.yankedi.tide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

lateinit var PREFIX: String
lateinit var HOME: String

sealed class InitState {
    data object Checking : InitState()
    data class Downloading(val progress: Float) : InitState()
    data class Extracting(val message: String = "") : InitState()
    data object Ready : InitState()
    data class Error(val message: String) : InitState()
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
                        .url("https://api.github.com/repos/yankedi/tide-packages/releases/latest")
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
                                    state =
                                        InitState.Downloading(if (totalSize > 0) downloaded.toFloat() / totalSize else 0f)
                                }
                            }
                        }
                    }

                    state = InitState.Extracting("正在解压 bootstrap...")
                    unzip(downloadFile, usrDir)
                    downloadFile.delete()
                }
                /*
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
                */
                val secondStageScript = File(PREFIX, "etc/termux/tide-bootstrap/second-stage/termux-bootstrap-second-stage.sh")
                if (secondStageScript.exists()) {
                    val bash = File(PREFIX, "bin/bash")
                    val envArray = arrayOf(
                        "TERM=xterm-256color",
                        "HOME=$HOME",
                        "PREFIX=$PREFIX",
                        "TERMINFO=$PREFIX/share/terminfo",
                        "LD_LIBRARY_PATH=$PREFIX/lib",
                        "PATH=$PREFIX/bin:$PREFIX/bin/applets",
                        "LANG=en_US.UTF-8"
                    )

                    val process = ProcessBuilder(bash.absolutePath, secondStageScript.absolutePath)
                        .redirectErrorStream(true)
                        .apply {
                            environment().clear()
                            envArray.forEach {
                                val (k, v) = it.split("=", limit = 2)
                                environment()[k] = v
                            }
                        }
                        .start()
                    val outputLines = StringBuilder()
                    process.inputStream.bufferedReader().lineSequence().forEach { line ->
                        outputLines.appendLine(line)
                        state = InitState.Extracting(outputLines.toString())  // 实时刷新
                    }
                    val output = process.inputStream.bufferedReader().readText()
                    val exitCode = process.waitFor()
                    android.util.Log.d("SecondStage", "exit=$exitCode output=\n$output")

                    if (exitCode != 0) {
                        throw Exception("second-stage failed (exit $exitCode):\n$output")
                    }
                    state = InitState.Ready
                }
            } catch (e: Exception) {
                state = InitState.Error(e.message ?: "Unknown error")
            }
            state = InitState.Ready
        }
    }

    when (val s = state) {
        is InitState.Ready -> return true
        is InitState.Checking -> Box(
            Modifier.Companion.fillMaxSize(),
            contentAlignment = Alignment.Companion.Center
        ) {
            Column(horizontalAlignment = Alignment.Companion.CenterHorizontally) {
                CircularProgressIndicator()
                Text(
                    "Checking environment...",
                    Modifier.Companion.padding(top = 16.dp),
                    color = Color.Companion.White
                )
            }
        }
        is InitState.Downloading -> Box(
            Modifier.Companion.fillMaxSize(),
            contentAlignment = Alignment.Companion.Center
        ) {
            Column(horizontalAlignment = Alignment.Companion.CenterHorizontally) {
                LinearProgressIndicator(progress = { s.progress })
                Text(
                    "Downloading bootstrap: ${(s.progress * 100).toInt()}%",
                    Modifier.Companion.padding(top = 16.dp),
                    color = Color.Companion.White
                )
            }
        }
        is InitState.Extracting -> Box(
            Modifier.Companion.fillMaxSize(),
            contentAlignment = Alignment.Companion.Center
        ) {
            Column(horizontalAlignment = Alignment.Companion.CenterHorizontally) {
                CircularProgressIndicator()
                Text(
                    if (s.message.isNotBlank()) s.message else "Extracting...",
                    Modifier.Companion.padding(top = 16.dp),
                    color = Color.Companion.White
                )
            }
        }
        is InitState.Error -> Box(
            Modifier.Companion.fillMaxSize(),
            contentAlignment = Alignment.Companion.Center
        ) { Text("Error: ${s.message}", color = Color.Companion.Red) }
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
                    } catch (_: Exception) {
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
                    Log.e("Tide", "Failed to create symlink: $link -> $target", e)
                }
            }
        }
    }
}
fun TerminalSession.writeString(text: String) {
    val data = text.toByteArray()
    this.write(data, 0, data.size)
    Log.d("Tide", "writeString: ${text.length} chars, bytes: ${data.size}, content: ${data.joinToString(",") { it.toString() }}")
}

// 扩展函数：处理修饰键与字符的组合
fun String.withModifiers(ctrl: Boolean, alt: Boolean, shift: Boolean): String {
    if (!ctrl && !alt && !shift) return this

    // 对于特殊按键（转义序列），直接返回
    if (this.startsWith("\u001b")) return this

    if (this.length != 1) return this

    var result = this
    val ch = result[0]

    if (shift) {
        result = when (ch) {
            '/' -> "?"
            '-' -> "_"
            '&' -> "*"
            ';' -> ":"
            else -> ch.uppercaseChar().toString()
        }
    }

    if (ctrl) {
        val c = result[0]
        result = when {
            c in 'a'..'z' -> (c.code - 'a'.code + 1).toChar().toString()
            c in 'A'..'Z' -> (c.code - 'A'.code + 1).toChar().toString()
            c == ' ' || c == '2' -> "\u0000"
            c == '[' -> "\u001b"
            c == '\\' -> "\u001c"
            c == ']' -> "\u001d"
            c == '^' || c == '6' -> "\u001e"
            c == '_' || c == '-' -> "\u001f"
            c == '?' || c == '/' -> "\u007f"
            else -> result
        }
    }

    if (alt) {
        result = "\u001b$result"
    }

    return result
}

@Composable
fun Terminal(modifier: Modifier = Modifier.Companion, viewModel: TerminalViewModel = viewModel()) {
    var terminalViewRef by remember { mutableStateOf<TerminalView?>(null) }
    var textSize by remember { mutableFloatStateOf(40f) }
    // 快捷键栏状态
    var isCtrlPressed by remember { mutableStateOf(false) }
    var isAltPressed by remember { mutableStateOf(false) }
    var isShiftPressed by remember { mutableStateOf(false) }

    // 创建可变引用，用于在 TerminalViewClient 中读取修饰键状态
    val modifierKeyState = remember {
        object {
            var ctrl = false
            var alt = false
            var shift = false
        }
    }

    // 同步修饰键状态到可变引用（TerminalViewClient 可以读取）
    LaunchedEffect(isCtrlPressed, isAltPressed, isShiftPressed) {
        modifierKeyState.ctrl = isCtrlPressed
        modifierKeyState.alt = isAltPressed
        modifierKeyState.shift = isShiftPressed
        Log.d(
            "Tide",
            "Modifier keys state updated: Ctrl=$isCtrlPressed, Alt=$isAltPressed, Shift=$isShiftPressed"
        )
    }

    val tintedDarkBg = MaterialTheme.colorScheme.primary
        .copy(alpha = 0.1f)
        .compositeOver(Color(0xFF101014))

    val terminalBgColorInt = tintedDarkBg.toArgb()

    val shellPath = remember {
        val bash = File(PREFIX, "bin/bash")
        if (bash.exists()) bash.absolutePath else "$PREFIX/bin/sh"
    }
    val shellArgs = remember(shellPath) {
        emptyArray<String>()
    }

    val terminalSession = remember {
        val envs = arrayOf(
            "TERM=xterm-256color",
            "HOME=$HOME",
            "PREFIX=$PREFIX",
            "SHELL=$PREFIX/bin/bash",
            "TERMUX_VERSION=0.118.0",
            "TERMINFO=$PREFIX/share/terminfo",
            "LD_LIBRARY_PATH=$PREFIX/lib",
            "LD_PRELOAD=$PREFIX/lib/libtermux-exec-direct-ld-preload.so",
            "PATH=$PREFIX/bin:$PREFIX/bin/applets",
            "LANG=en_US.UTF-8",
            "PS1=\\w \\$ ",
            "PROMPT_DIRTRIM=3"
        )

        Log.d("Tide", "Starting shell: $shellPath ${shellArgs?.joinToString(" ")}")

        viewModel.getOrCreateSession(
            shellPath,
            HOME,
            shellArgs,
            envs,
            object : TerminalSessionClient {
                override fun onTextChanged(changedSession: TerminalSession) {
                    terminalViewRef?.onScreenUpdated()
                }

                override fun onTitleChanged(changedSession: TerminalSession) {}
                override fun onSessionFinished(finishedSession: TerminalSession) {}
                override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
                    if (text != null && text.isNotEmpty()) {
                        try {
                            val context = terminalViewRef?.context ?: return
                            val clipboardManager =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipData = ClipData.newPlainText("Terminal Content", text)
                            clipboardManager.setPrimaryClip(clipData)
                            Log.d("Tide", "Copied to clipboard: ${text.length} chars")
                        } catch (e: Exception) {
                            Log.e("Tide", "Failed to copy to clipboard", e)
                        }
                    }
                }

                override fun onPasteTextFromClipboard(session: TerminalSession?) {
                    if (session == null) return
                    try {
                        val context = terminalViewRef?.context ?: return
                        val clipboardManager =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clipData = clipboardManager.primaryClip

                        if (clipData != null && clipData.itemCount > 0) {
                            val text = clipData.getItemAt(0).text?.toString() ?: return
                            if (text.isNotEmpty()) {
                                val data = text.toByteArray()
                                session.write(data, 0, data.size)
                                Log.d("Tide", "Pasted from clipboard: ${text.length} chars")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("Tide", "Failed to paste from clipboard", e)
                    }
                }

                override fun onBell(changedSession: TerminalSession) {}
                override fun onColorsChanged(session: TerminalSession) {}
                override fun onTerminalCursorStateChange(state: Boolean) {}

                override fun getTerminalCursorStyle(): Int = 0
                override fun logError(tag: String?, message: String?) {}
                override fun logWarn(tag: String?, message: String?) {}
                override fun logInfo(tag: String?, message: String?) {}
                override fun logDebug(tag: String?, message: String?) {}
                override fun logVerbose(tag: String?, message: String?) {}
                override fun logStackTraceWithMessage(
                    tag: String?,
                    message: String?,
                    e: Exception?
                ) {
                }

                override fun logStackTrace(tag: String?, e: Exception?) {}
            }
        )
    }

    val clearModifiers = {
        if (isCtrlPressed || isAltPressed || isShiftPressed) {
            isCtrlPressed = false
            isAltPressed = false
            isShiftPressed = false
        }
    }

    val sendVirtualInput: (String) -> Unit = { key ->
        terminalSession.writeString(key.withModifiers(isCtrlPressed, isAltPressed, isShiftPressed))
        clearModifiers()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()  // 响应软键盘高度变化，自动添加底部 padding
    ) {
        Column(modifier = Modifier.Companion.fillMaxSize()) {
            // 终端区域（占据剩余空间）
            Box(
                modifier = Modifier.Companion
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView(
                    modifier = Modifier.Companion.fillMaxSize(),
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
                                    val imm =
                                        ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    imm.restartInput(this@apply)
                                    post {
                                        imm.showSoftInput(this@apply, 0)
                                    }
                                }

                                override fun onKeyDown(
                                    keyCode: Int,
                                    e: KeyEvent?,
                                    session: TerminalSession?
                                ): Boolean = false

                                override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
                                override fun onCodePoint(
                                    codePoint: Int,
                                    ctrlDown: Boolean,
                                    session: TerminalSession?
                                ): Boolean = false

                                override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                                override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                                override fun isTerminalViewSelected(): Boolean = true
                                override fun copyModeChanged(copyMode: Boolean) {}
                                override fun onLongPress(event: MotionEvent?): Boolean = false
                                override fun readControlKey(): Boolean {
                                    val result = modifierKeyState.ctrl
                                    if (result) Log.d("Tide", "readControlKey() -> true")
                                    return result
                                }

                                override fun readAltKey(): Boolean {
                                    val result = modifierKeyState.alt
                                    if (result) Log.d("Tide", "readAltKey() -> true")
                                    return result
                                }

                                override fun readShiftKey(): Boolean {
                                    val result = modifierKeyState.shift
                                    if (result) Log.d("Tide", "readShiftKey() -> true")
                                    return result
                                }

                                override fun readFnKey(): Boolean = false
                                override fun shouldEnforceCharBasedInput(): Boolean = false
                                override fun onEmulatorSet() {}

                                override fun logError(tag: String?, message: String?) {}
                                override fun logWarn(tag: String?, message: String?) {}
                                override fun logInfo(tag: String?, message: String?) {}
                                override fun logDebug(tag: String?, message: String?) {}
                                override fun logVerbose(tag: String?, message: String?) {}
                                override fun logStackTraceWithMessage(
                                    tag: String?,
                                    message: String?,
                                    e: Exception?
                                ) {
                                }

                                override fun logStackTrace(tag: String?, e: Exception?) {}
                            })

                            attachSession(terminalSession)
                            viewTreeObserver.addOnGlobalLayoutListener(object :
                                ViewTreeObserver.OnGlobalLayoutListener {
                                override fun onGlobalLayout() {
                                    if (width > 0 && height > 0) {
                                        viewTreeObserver.removeOnGlobalLayoutListener(this)
                                        postDelayed({
                                            try {
                                                val updateSizeMethod =
                                                    this@apply.javaClass.getMethod("updateSize")
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
                                } catch (_: Exception) {
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
                                } catch (_: Exception) {
                                }
                                view.onScreenUpdated()
                            }
                        }
                    }
                )
            }

            // 快捷键栏 - 第一行：修饰键和主要功能键
            Row(
                modifier = Modifier.Companion
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Companion.CenterVertically
            ) {
                // Ctrl
                KeyButton(
                    text = "Ctrl",
                    isPressed = isCtrlPressed,
                    onPress = {
                        isCtrlPressed = !isCtrlPressed
                    }
                )

                // Alt
                KeyButton(
                    text = "Alt",
                    isPressed = isAltPressed,
                    onPress = {
                        isAltPressed = !isAltPressed
                    }
                )

                // Shift
                KeyButton(
                    text = "Shift",
                    isPressed = isShiftPressed,
                    onPress = {
                        isShiftPressed = !isShiftPressed
                    }
                )

                // Esc
                PressableKeyButton(
                    text = "Esc",
                    onClick = { sendVirtualInput("\u001b") }
                )

                // Tab
                PressableKeyButton(
                    text = "Tab",
                    onClick = { sendVirtualInput("\t") }
                )

                // /
                PressableKeyButton(
                    text = "/",
                    onClick = { sendVirtualInput("/") }
                )

                // ~
                PressableKeyButton(
                    text = "~",
                    onClick = { sendVirtualInput("~") }
                )

                // -
                PressableKeyButton(
                    text = "-",
                    onClick = { sendVirtualInput("-") }
                )

                // |
                PressableKeyButton(
                    text = "|",
                    onClick = { sendVirtualInput("|") }
                )

                Spacer(Modifier.Companion.weight(3f))

                // 方向键 - 上
                PressableKeyButton(
                    text = "↑",
                    onClick = { sendVirtualInput("\u001b[A") }
                )

                Spacer(Modifier.Companion.width(17.dp)) // 占位，使上方向键居中
            }

            // 快捷键栏 - 第二行：常用符号和功能键
            Row(
                modifier = Modifier.Companion
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Companion.CenterVertically
            ) {
                // &
                PressableKeyButton(
                    text = "&",
                    onClick = { sendVirtualInput("&") }
                )

                // ;
                PressableKeyButton(
                    text = ";",
                    onClick = { sendVirtualInput(";") }
                )

                // Del
                PressableKeyButton(
                    text = "Del",
                    onClick = { sendVirtualInput("\u007f") }
                )

                // Home
                PressableKeyButton(
                    text = "Home",
                    onClick = { sendVirtualInput("\u001b[H") }
                )

                // End
                PressableKeyButton(
                    text = "End",
                    onClick = { sendVirtualInput("\u001b[F") }
                )

                // PgUp
                PressableKeyButton(
                    text = "PgUp",
                    onClick = { sendVirtualInput("\u001b[5~") }
                )

                // PgDn
                PressableKeyButton(
                    text = "PgDn",
                    onClick = { sendVirtualInput("\u001b[6~") }
                )

                Spacer(Modifier.Companion.weight(1f))

                // 方向键 - 左下右
                PressableKeyButton(
                    text = "←",
                    onClick = { sendVirtualInput("\u001b[D") }
                )
                PressableKeyButton(
                    text = "↓",
                    onClick = { sendVirtualInput("\u001b[B") }
                )
                PressableKeyButton(
                    text = "→",
                    onClick = { sendVirtualInput("\u001b[C") }
                )
            }
        }
    }
}

@Composable
fun KeyButton(
    text: String,
    isPressed: Boolean = false,
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {},
    onClick: () -> Unit = {}
) {
    val backgroundColor = when {
        isPressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        else -> Color.Companion.Transparent
    }

    Box(
        modifier = Modifier.Companion
            .padding(8.dp)
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable {
                onClick()
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        Log.d("Tide", "KeyButton '$text' onPress")
                        onPress()
                        // 注意：对于修饰键（粘滞键），onPress 在点击时调用
                        // 不需要在这里立即释放，粘滞键会保持状态直到再次点击
                        tryAwaitRelease()
                        Log.d("Tide", "KeyButton '$text' onRelease")
                        onRelease()
                    }
                )
            },
        contentAlignment = Alignment.Companion.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun PressableKeyButton(
    text: String,
    onClick: () -> Unit = {}
) {
    var isPressed by remember { mutableStateOf(false) }

    val backgroundColor = when {
        isPressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        else -> Color.Companion.Transparent
    }

    Box(
        modifier = Modifier.Companion
            .padding(8.dp)
            .background(
                color = backgroundColor,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        Log.d("Tide", "PressableKeyButton '$text' pressed")
                        tryAwaitRelease()
                        isPressed = false
                        Log.d("Tide", "PressableKeyButton '$text' released, calling onClick")
                        onClick()
                    }
                )
            },
        contentAlignment = Alignment.Companion.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}