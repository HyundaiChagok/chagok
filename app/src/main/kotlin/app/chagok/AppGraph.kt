package app.chagok

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.chagok.core.rental.RentalPolicy
import app.chagok.core.source.MixedVehicleSource
import app.chagok.core.source.VehicleSource
import app.chagok.demo.DemoVehicle
import app.chagok.pleos.PleosVehicleSource
import app.chagok.rental.RecordStore
import app.chagok.rental.RentalController
import app.chagok.rental.Speaker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

class ChagokApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

/** R1은 차량 한 대로 고정한다. R2부터 운영자 웹의 차량 연결(기능 정의서 F-SYS-06)로 받는다. */
data class AppSettings(
    val vehicleId: String = "V-0007",
    val vehicleCode: String = "0007",
    val appVersion: String = BuildConfig.VERSION_NAME,
    val reportBaseUrl: String = BuildConfig.REPORT_BASE_URL,
    /** 시연용 업체 기준(가정): 탱크 47L, 휘발유 1,700원/L */
    val policy: RentalPolicy = RentalPolicy(version = "demo/1", tankCapacityL = 47.0, fuelPricePerL = 1_700),
    /** 에뮬레이터에서 바꿀 수 없는 값을 시나리오로 채우고 화면에 '시연 모드'를 띄운다(F-SYS-07). */
    val demo: Boolean = true,
)

/**
 * 앱의 부품을 한곳에서 만든다. Pleos 차량 서비스가 있으면 SDK로 읽고, 없으면(일반 에뮬레이터·개발 PC) 가짜 차량으로 읽는다.
 * 시연 모드에서는 문·좌석만 SDK 값을 쓰고 나머지는 가짜 차량 대본으로 채운다. 문·좌석은 Virtual Console로 바꿀 수 있다.
 */
class AppGraph(context: Context) {
    val settings = AppSettings()
    val demo = DemoVehicle()
    private val pleos: PleosVehicleSource? = if (hasPleosService(context)) PleosVehicleSource(context) else null

    val vehicle: VehicleSource = when {
        pleos == null -> demo.fake
        settings.demo -> MixedVehicleSource(pleos, demo.fake, DemoVehicle.SCRIPTED)
        else -> pleos
    }

    /** 화면 위에 보일 데이터 출처 */
    val sourceLabel: String = when {
        pleos == null -> "가짜 차량"
        settings.demo -> "Pleos SDK + 시나리오"
        else -> "Pleos SDK"
    }

    /** 기어 P 여부. 읽지 못하면 아무것도 오지 않고, 화면은 정차로 본다. */
    val parked: Flow<Boolean> = pleos?.parked() ?: emptyFlow()

    val store = RecordStore(File(context.filesDir, "records"))
    val speaker = Speaker(context)

    val controllerFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RentalController(vehicle, store, speaker, settings, parked) as T
    }
}

private fun hasPleosService(context: Context): Boolean {
    val intent = Intent("ai.pleos.playground.vehicle.VEHICLE_START")
    val pm = context.packageManager
    return pm.queryIntentServices(intent, 0).isNotEmpty() || pm.queryIntentActivities(intent, 0).isNotEmpty() ||
        pm.queryBroadcastReceivers(intent, 0).isNotEmpty()
}
