plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 发布签名只从构建环境读取，私钥/密码绝不写入源码
val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    // namespace 决定 R 类与 Manifest 中 ".Xxx" 的展开前缀，
    // 必须与源码 package 声明（kbs / kbs.core.xxx）一致。
    namespace = "kbs"

    compileSdk = 34

    defaultConfig {
        // ★★ applicationId 必须至少两段（含一个点）★★
        //
        // Android 硬性规则（developer.android.com/build/configure-app-module）：
        //   "It must have at least two segments (one or more dots)."
        //
        // 单段包名（如 "kbs"）会让 PackageParser 解析异常，症状为：
        //   ① 启动图标回退成默认机器人
        //   ② 运行时崩溃 —— 连主界面都进不去
        //
        // 此处保留 kbs 前缀，补第二段满足合规。
        // applicationId 与 namespace 不同是官方允许的配置；
        // namespace 仍为 "kbs"，确保 Manifest 的 ".MainActivity"
        // 正确展开为 kbs.MainActivity。
        applicationId = "kbs.pearl"

        minSdk = 28      // Android 9 (Pie)
        targetSdk = 34   // Android 14

        // ============================================================
        // 版本号规则（项目约定，勿随意改动）
        // ============================================================
        //   · **不因小改动递增**。修 bug、调 UI、补注释、改脚本都不改版本。
        //   · 仅在**重大改变**时才递增，例如：
        //       - 物理模型/算法变更（影响计算结果）
        //       - 架构重构
        //       - 新增或移除功能模块
        //       - applicationId 变更
        //   · 记录变更时，同步更新 README 顶部的版本号。
        //
        // ★ 这条约定写在文件里（而非只留在对话记录中），
        //   是为了避免上下文被截断后规则丢失。
        versionCode = 1
        versionName = "4.0.0"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = null
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures { compose = true }

    composeOptions {
        // Compose Compiler 与 Kotlin 版本必须严格对应。
        // 官方映射表：Kotlin 1.9.24 → Compose Compiler 1.5.14
        // 版本不匹配会直接导致编译失败。
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    // ============================================================
    // Jetpack Compose（Google 开源 UI 工具包）
    //
    // ★ 所有被代码**直接 import** 的模块都必须显式声明。
    //   依赖传递依赖是运行时 NoClassDefFoundError 的主要来源 ——
    //   编译期可见但运行期类加载失败，症状是启动即闪退，
    //   而编译完全不报错，极难排查。
    // ============================================================
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    // 注：未引入 material-icons-extended —— 该库会让 APK 增大数 MB，
    //     本项目的图标由 drawable 与 Canvas 绘制提供。

    // ============================================================
    // 架构组件
    // ============================================================
    implementation("androidx.core:core-ktx:1.13.1")       // FileProvider
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

    // 协程：解算并行化用。显式声明，不依赖传递依赖
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 单元测试：core 包是纯 Kotlin（零 Android 依赖），可直接 JVM 单测
    testImplementation("junit:junit:4.13.2")
}
