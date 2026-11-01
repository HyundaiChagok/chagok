// Pleos Playground Vehicle SDK로 :core의 VehicleSource를 구현하는 Android 라이브러리. 차 데이터는 이 모듈만 SDK로 읽는다.
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
    implementation(libs.kotlinx.coroutines.android)
}
