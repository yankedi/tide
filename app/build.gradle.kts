import java.util.Properties
import com.android.build.gradle.AppExtension
import java.io.FileOutputStream
import java.net.URL

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.yankedi.tide"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.yankedi.tide"
        minSdk = 24
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++11"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
}

// 获取 NDK 目录
fun getNdkDir(): String {
    val localProperties = Properties()
    val localPropertiesFile = project.rootProject.file("local.properties")
    var sdkDir = ""
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { localProperties.load(it) }
        val ndkDir = localProperties.getProperty("ndk.dir")
        if (ndkDir != null) return ndkDir
        sdkDir = localProperties.getProperty("sdk.dir") ?: ""
    }
    
    // 如果没有 ndk.dir，尝试从 sdk.dir/ndk 自动获取
    if (sdkDir.isNotEmpty()) {
        val ndkRoot = file("$sdkDir/ndk")
        if (ndkRoot.exists() && ndkRoot.isDirectory) {
            val versions = ndkRoot.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
            if (!versions.isNullOrEmpty()) {
                return versions.first().absolutePath
            }
        }
    }

    val appExtension = project.extensions.findByType(AppExtension::class.java)
    return appExtension?.ndkDirectory?.absolutePath ?: ""
}

// 注册编译 PRoot 的任务
val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

abis.forEach { abi ->
    tasks.register<Exec>("buildStaticProot_$abi") {
        val ndkDir = getNdkDir()
        val scriptFile = file("proot-builder/build-proot.sh")
        workingDir = file("proot-builder")
        commandLine("bash", scriptFile.absolutePath, ndkDir, abi)
        
        inputs.dir("proot-builder")
        outputs.file("src/main/assets/bin/$abi/proot")
    }
}

// --- 流派三：CI/CD 产物拉取配置 ---
val prootVersion = "v1.0.0" // 对应 GitHub Release 的 Tag
val githubRepo = "yankedi/tide" // 替换为你的真实 GitHub 仓库名

tasks.register("fetchProotBinaries") {
    group = "prebuild"
    description = "从 GitHub Releases 下载预编译的静态 PRoot 二进制文件"

    val abis = listOf("arm64-v8a", "x86_64")
    val baseDir = file("src/main/assets/bin")

    inputs.property("version", prootVersion)
    outputs.dirs(abis.map { file("$baseDir/$it") })

    doLast {
        abis.forEach { abi ->
            val targetDir = file("$baseDir/$abi")
            val targetFile = file("$targetDir/proot")
            val downloadUrl = "https://github.com/$githubRepo/releases/download/$prootVersion/proot-$abi"

            if (!targetFile.exists()) {
                println("正在从 GitHub 下载 $abi 版本的 PRoot...")
                targetDir.mkdirs()
                try {
                    URL(downloadUrl).openStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    targetFile.setExecutable(true)
                    println("$abi 版本下载成功！")
                } catch (e: Exception) {
                    println("下载失败: ${e.message}。请确保 Release 已发布且链接正确：$downloadUrl")
                    // 如果下载失败，可以考虑是否要报错中断
                }
            } else {
                println("$abi 版本的 PRoot 已存在，跳过下载。")
            }
        }
    }
}

// 将拉取任务挂载到资源生成之前
tasks.configureEach {
    if (name.startsWith("generate") && name.endsWith("Assets")) {
        dependsOn("fetchProotBinaries")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.compose.runtime)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    implementation(libs.termux.terminal.emulator)
    implementation(libs.termux.terminal.view)
    implementation(libs.commons.compress)
    implementation(libs.xz)
}
