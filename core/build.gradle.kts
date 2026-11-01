import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// SDK와 무관한 순수 Kotlin 모듈. Android 앱 모듈이 그대로 가져다 쓴다.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // 컴파일러 플러그인 없이 JsonElement만 쓴다. 기록 형식(snapshot/1)을 클래스 구조와 떼어 직접 정한다.
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    defaultCharacterEncoding = "UTF-8"
}
