// 顶层构建脚本：AGP 9.1.1 + Kotlin compose 插件 2.2.10（与 v1 原型验证一致的组合）
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}
