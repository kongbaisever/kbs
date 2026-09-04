// 根构建脚本：只声明插件，不在此处应用。
// 版本集中在这里，避免各模块各写一份导致不一致。
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
