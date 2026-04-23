package io.github.yankedi.tide

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.net.toUri
import java.io.File
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PREFIX = File(filesDir, "usr").absolutePath
        HOME = File(filesDir, "home").absolutePath
        File(HOME).mkdirs()
        File(PREFIX).mkdirs()

        checkStoragePermission()
        //enableEdgeToEdge()

        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

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
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as Activity).window
                    // 将状态栏背景色设为 M3 动态方案的背景色（或根据需求使用 colorScheme.surface）
                    window.statusBarColor = colorScheme.background.toArgb()
                    // 动态控制状态栏图标/文字颜色：浅色背景时使用深色图标，深色背景时使用浅色图标
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDarkTheme
                }
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
fun MainScreen() {
    Scaffold(
        containerColor = Color(0xFF101014),
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
                    onClick = {  },
                    shape = RoundedCornerShape(12.dp),
                ){
                    Text("Code")
                }
                TextButton(
                    onClick = {  },
                    shape = RoundedCornerShape(12.dp),
                ){
                    Text("Git")
                }
                TextButton(
                    onClick = {  },
                    shape = RoundedCornerShape(12.dp),
                ){
                    Text("Run")
                }
                TerminalMenu()
            }
        },
    ){ innerPadding ->
        Box(modifier = Modifier.padding(top = innerPadding.calculateTopPadding())) {
            if(checkEnvironment()) DynmicTab()
        }
    }
}
@Composable
fun TerminalMenu(tabViewModel: TabViewModel = viewModel()){
    var expanded by remember { mutableStateOf(false) }
    Box{
        TextButton(
            onClick = {expanded = true },
            shape = RoundedCornerShape(12.dp),
        ){ Text("Terminal") }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(text = {Text("New Terminal")}, onClick = {
                expanded = false
                tabViewModel.addTab()
            })
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