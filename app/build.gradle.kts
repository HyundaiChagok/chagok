import java.util.Properties

// 차곡 차량 앱. 화면은 Compose이고, 차 데이터는 :pleos(실차·에뮬레이터)나 시연용 가짜 차량에서 읽는다.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 출품용 서명 키. 경로는 local.properties의 chagok.release.signing에 두고 키와 비밀번호는 저장소 밖에 둔다.
// 그 파일이 없는 PC에서는 릴리스도 디버그 키로 서명해 바로 설치할 수 있게 한다.
val releaseSigning: Properties? = run {
    val local = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(local::load)
    local.getProperty("chagok.release.signing")?.let(::file)?.takeIf { it.exists() }
        ?.let { path -> Properties().apply { path.inputStream().use(::load) } }
}

android {
    namespace = "app.chagok"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.chagok"
        minSdk = 28
        // Pleos 에뮬레이터(Pleos_Connect_v2_1)가 API 34다.
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // 데이터 QR이 여는 고객 리포트 웹 주소. web/을 올린 뒤 gradle.properties의 chagok.reportBaseUrl을 바꾼다.
        val reportBaseUrl = providers.gradleProperty("chagok.reportBaseUrl").getOrElse("https://example.com/chagok")
        buildConfigField("String", "REPORT_BASE_URL", "\"$reportBaseUrl\"")
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 공모전 제출 파일: apk/〈팀명〉_차곡_release.apk. 팀명은 gradle.properties의 chagok.team이나 -Pchagok.team=…으로 준다.
val team = providers.gradleProperty("chagok.team").orElse("TEAM")
tasks.register<Copy>("contestApk") {
    group = "chagok"
    description = "공모전 제출용 릴리스 APK를 apk/ 폴더에 이름 규칙대로 만든다."
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk") }
    into(rootProject.layout.projectDirectory.dir("apk"))
    rename { "${team.get()}_차곡_release.apk" }
}

dependencies {
    implementation(project(":pleos"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
