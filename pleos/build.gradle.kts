// Pleos Playground SDK를 :core의 인터페이스로 감싸는 Android 라이브러리. Vehicle은 VehicleSource, TextToSpeech는 SpeechEngine, SpeechToText는 ReplyListener, NaviHelper는 ReturnNavigation이다. SDK를 직접 쓰는 코드는 이 모듈에만 둔다.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.chagok.pleos"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)
    implementation(libs.pleos.navi)
    implementation(libs.kotlinx.coroutines.android)
}
