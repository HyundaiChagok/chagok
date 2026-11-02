package app.chagok

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.chagok.core.record.SnapshotRecord
import app.chagok.core.rental.RentalPolicy
import app.chagok.core.rental.ReturnPoint
import app.chagok.core.source.MixedVehicleSource
import app.chagok.core.source.VehicleSource
import app.chagok.core.voice.VoiceChain
import app.chagok.core.voice.VoiceOutput
import app.chagok.core.voice.stopSpeakingWhenDriving
import app.chagok.demo.DemoVehicle
import app.chagok.pleos.PleosNavigation
import app.chagok.pleos.PleosReplyListener
import app.chagok.pleos.PleosSpeechEngine
import app.chagok.pleos.PleosVehicleSource
import app.chagok.rental.ArrivalNotification
import app.chagok.rental.FocusedSpeech
import app.chagok.rental.GleoDesk
import app.chagok.rental.GleoResponder
import app.chagok.rental.RecordStore
import app.chagok.rental.RecordingService
import app.chagok.rental.RentalController
import app.chagok.rental.ReturnTripKeeper
import app.chagok.rental.Speaker
import app.chagok.rental.TimelineKeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.Instant

class ChagokApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

/** R1은 차량 한 대로 고정한다. R2부터 운영자 웹의 차량 연결(기능 정의서 F-SYS-06)로 받는다. */
data class AppSettings(
    /** 차대번호를 읽지 못할 때 대신 쓰는 업체 등록 번호 */
    val vehicleId: String = "V-0007",
    val vehicleCode: String = "0007",
    val appVersion: String = BuildConfig.VERSION_NAME,
    val reportBaseUrl: String = BuildConfig.REPORT_BASE_URL,
    /** 시연용 업체 기준(가정): 탱크 47L, 휘발유 1,700원/L, 배터리 77.4kWh(차가 용량을 주면 그 값을 쓴다), 전기 350원/kWh */
    val policy: RentalPolicy = RentalPolicy(
        version = "demo/2", tankCapacityL = 47.0, fuelPricePerL = 1_700, batteryCapacityKwh = 77.4, electricityPricePerKwh = 350,
    ),
    /**
     * 실제 차 값이 아닌 값을 쓰는지. 빌드 설정 chagok.demoMode=scenario이거나 Pleos 서비스가 없어 가짜 차량으로 도는 경우다(F-SYS-07).
     * 화면과 기록에 '시연 모드'를 띄우고, 직원 화면에 시연 조작 버튼을 보여 준다.
     */
    val demo: Boolean = BuildConfig.DEMO_MODE == SCENARIO_MODE,
    /** 기록에 남길 값의 출처([SnapshotRecord.source]). 실차면 null이다. */
    val recordSource: String? = null,
    /** 시연용 가상 지점. 실제 업체 지점이 아니고, 서울시청 앞 좌표를 빌려 쓴다. R2부터 운영자 웹에서 받는다. */
    val returnPoint: ReturnPoint = ReturnPoint(
        name = "반납 지점(시연)", address = "서울 중구 세종대로 110", latitude = 37.5663, longitude = 126.9779, poiId = "chagok-return-demo", poiSubId = "0",
    ),
    /** 무효와 직원 반납 기록 앞에서 받는 직원 PIN. 시연값이고 README에 적어 둔다. R2부터 운영자 웹에서 받는다. */
    val staffPin: String = "2580",
) {
    companion object {
        const val SCENARIO_MODE = "scenario"
    }
}

/** 값이 어디서 오는지. 화면에 그대로 밝힌다. [tag]는 화면 위 띠에 붙이는 표시이고, 실차에서는 붙이지 않는다. */
enum class ValueSource(val label: String, val tag: String?) {
    PLEOS("Pleos SDK", null),
    EMULATOR("Pleos SDK · 에뮬레이터(Virtual Console 주입)", "에뮬레이터"),
    SCENARIO("Pleos SDK + 시나리오", "시연 모드"),
    FAKE("가짜 차량(Pleos 서비스 없음)", "시연 모드"),
}

/** 직원 화면 진단(V-07)의 플랫폼 사실. gleoFunctions는 assets/schema.json의 함수 수이고 파일을 읽지 못하면 null이다. telltaleGranted는 경고등 권한(PRIVATE_CAR_SAFETY)을 받았는지다. */
data class PlatformFacts(val gleoFunctions: Int?, val telltaleGranted: Boolean)

/**
 * 앱의 부품을 한곳에서 만든다. 기본(chagok.demoMode=live)은 Pleos SDK 값만 쓴다. Pleos 차량 서비스가 없으면(일반 에뮬레이터·개발 PC) 가짜 차량으로 읽고
 * 화면과 기록에 시연 모드로 드러낸다. 시나리오 모드에서는 Virtual Console로 바꿀 수 있는 값만 SDK에서 읽고 나머지는 가짜 차량 대본으로 채운다.
 *
 * 화면과 서비스가 함께 쓰는 것은 여기 프로세스 수준에 둔다. 대여 중 기록([timeline])은 화면이 없어도 돈다.
 */
class AppGraph(context: Context) {
    private val app = context.applicationContext
    private val pleos: PleosVehicleSource? = if (hasPleosService(context)) PleosVehicleSource(context) else null

    val source: ValueSource = when {
        pleos == null -> ValueSource.FAKE
        BuildConfig.DEMO_MODE == AppSettings.SCENARIO_MODE -> ValueSource.SCENARIO
        isEmulator() -> ValueSource.EMULATOR
        else -> ValueSource.PLEOS
    }

    val settings = AppSettings(
        demo = source == ValueSource.FAKE || source == ValueSource.SCENARIO,
        recordSource = SnapshotRecord.SOURCE_EMULATOR.takeIf { source == ValueSource.EMULATOR },
    )
    val demo = DemoVehicle()

    val vehicle: VehicleSource = when {
        pleos == null -> demo.fake
        settings.demo -> MixedVehicleSource(pleos, demo.fake, DemoVehicle.SCRIPTED)
        else -> pleos
    }

    /** 기어 P 여부. 읽지 못하면 아무것도 오지 않고, 화면은 정차로 본다. 가짜 차량은 늘 P단이다. */
    val parked: Flow<Boolean> = pleos?.parked() ?: flowOf(true)

    val store = RecordStore(File(context.filesDir, RecordStore.FOLDER))

    // 서비스만 도는 동안(화면 없음)에는 TTS 엔진을 띄우지 않는다. 화면이 뜰 때 MainActivity가 미리 띄운다.
    val voice: VoiceOutput by lazy {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        VoiceChain(
            engines = listOf({ FocusedSpeech(PleosSpeechEngine(app, FocusedSpeech.ATTRIBUTES), app) }, { FocusedSpeech(Speaker(app), app) }),
            scope = scope,
            log = { Log.i("ChagokVoice", it) },
        ).also { scope.stopSpeakingWhenDriving(it, parked) }
    }

    /** 대여 중 기록. 쓰기가 한 줄로 이어지게 디스패처를 하나로 묶는다. */
    val timeline = TimelineKeeper(vehicle, store, CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)), Instant::now)

    /** 반납 지점 길안내와 도착. 내비는 화면이 대여를 열 때 연결한다. '도착 재현'은 실차에서 받지 않는다. */
    val returnTrip = ReturnTripKeeper(
        navi = pleos?.let { PleosNavigation(app) },
        store = store,
        voice = { voice },
        parked = parked,
        notice = ArrivalNotification(app),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
        point = settings.returnPoint,
        canSimulate = source != ValueSource.PLEOS,
        vehicle = vehicle,
    )

    /** 직원 화면을 처음 열 때 한 번 읽는다. */
    val platform: PlatformFacts by lazy {
        val functions = runCatching {
            app.assets.open("schema.json").use { input -> Json.parseToJsonElement(input.reader().readText()).jsonObject.getValue("actionCalls").jsonArray.size }
        }.getOrNull()
        PlatformFacts(functions, PleosVehicleSource.telltaleGranted(app))
    }

    // 에뮬레이터에는 마이크 입력이 없어 디버그 빌드에서는 files/reply-test.pcm이 있으면 그 소리를 대신 인식한다. 릴리스에는 이 길이 없다.
    private val replies = pleos?.let {
        PleosReplyListener(app, File(app.filesDir, "reply-test.pcm").takeIf { BuildConfig.DEBUG && source == ValueSource.EMULATOR })
    }

    val gleoResponder = GleoResponder(app)

    /** 화면 없이 답하는 Gleo 함수. 음성 엔진은 답할 일이 생겼을 때 띄운다. */
    val gleo = GleoDesk(store, vehicle, settings.policy, parked, { voice }, gleoResponder::send)

    val controllerFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RentalController(
            vehicle, store, voice, settings, parked, timeline,
            onClosed = demo::reset,
            onRecording = { recording -> if (recording) RecordingService.start(app) else RecordingService.stop(app) },
            trip = returnTrip,
            spoken = gleo.spoken,
            onGleoReply = gleoResponder::send,
            replies = replies,
        ) as T
    }
}

private fun hasPleosService(context: Context): Boolean {
    val intent = Intent("ai.pleos.playground.vehicle.VEHICLE_START")
    val pm = context.packageManager
    return pm.queryIntentServices(intent, 0).isNotEmpty() || pm.queryIntentActivities(intent, 0).isNotEmpty() ||
        pm.queryBroadcastReceivers(intent, 0).isNotEmpty()
}

// Pleos 에뮬레이터는 ro.hardware가 ranchu이고 모델 이름이 "Connect on x86_64 emulator"다(2026.11.02 확인).
private fun isEmulator(): Boolean =
    Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" || Build.MODEL.contains("emulator", ignoreCase = true)
