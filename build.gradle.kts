// 版本选择依据（本机实测可用的组合）：
//   AGP 8.13.2（8.x 线最新稳定版）+ Kotlin 2.4.20 + Gradle 8.13 + JDK 17
//   刻意不用 AGP 9.x：9.x 要求 Gradle 9.x / 可能要求 JDK 21，而 8.x 的 DSL 更稳。
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
