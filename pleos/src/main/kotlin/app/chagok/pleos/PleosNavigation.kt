package app.chagok.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import app.chagok.core.rental.ReturnPoint
import app.chagok.core.source.ReturnNavigation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Pleos NaviHelper SDK로 반납 지점 길안내를 요청하는 [ReturnNavigation].
 * SDK 호출은 전용 줄에서 한다. Vehicle SDK처럼 첫 호출이 느릴 수 있어 메인 스레드에서 부르지 않는다.
 * 결과는 모두 리스너로 오고, 내비 앱이 어떻게 구현됐는지에 따라 오지 않을 수 있어 기다리는 곳마다 시간 제한을 둔다.
 *
 * requestRoute를 받으면 내비가 스스로 화면에 뜨고 경로 안내를 시작한다(2026.11.02 에뮬레이터 확인). 그래서 내비를 앞으로 불러오는 요청은 따로 하지 않는다.
 * 현재 위치(getCurrentLocationInfo)와 목적지 정보(getDestinationInfo)는 부르지 않고, 리스너에서도 도착 예상 배터리와 도착 신호, 경로 시작 때의 목적지 이름 비교만 한다.
 * 리스너는 연결할 때 하나만 걸고 연결을 끊을 때만 푼다. Vehicle SDK는 리스너 하나를 풀면 다른 리스너까지 끊겼다.
 */
class PleosNavigation(context: Context) : ReturnNavigation {
    private val app = context.applicationContext
    private val calls = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + calls)
    private var helper: NaviHelper? = null

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _arrivals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val arrivals: Flow<Unit> = _arrivals.asSharedFlow()

    private val _routeCancelled = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val routeCancelled: Flow<Unit> = _routeCancelled.asSharedFlow()

    private val _routeFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val routeFailed: Flow<Unit> = _routeFailed.asSharedFlow()

    @Volatile
    private var estimate: CompletableDeferred<Int?>? = null

    // 이 앱이 요청한 길안내의 목적지 이름. 기억만 하고 저장하지 않는다. 프로세스가 죽으면 도착 안내 없이 고객이 '반납 체크'를 누른다.
    @Volatile
    private var routeName: String? = null

    private val listener = object : NaviHelperEventListener {
        override fun onTripInfo(tripInfo: TripInfo) {
            Log.i(TAG, "onTripInfo: 도착 예상 배터리 ${tripInfo.estimatedSocAtDest}%")
            estimate?.complete(tripInfo.estimatedSocAtDest)
        }

        // 고객이 내비에서 다른 목적지를 설정하면 그 도착은 반납 지점 도착이 아니다. 이름은 비교만 하고 남기지 않는다.
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            val ours = routeStartInfo.name == routeName
            Log.i(TAG, "onRouteStarted: 이 앱이 요청한 길안내=$ours")
            if (!ours && routeName != null) {
                routeName = null
                _routeCancelled.tryEmit(Unit)
            }
        }

        override fun onRoutingCompleted() {
            Log.i(TAG, "onRoutingCompleted")
        }

        override fun onRouteCancelled() {
            Log.i(TAG, "onRouteCancelled")
            routeName = null
            _routeCancelled.tryEmit(Unit)
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            // 도착 정보(이름·주소·거리·시간)는 로그에도 남기지 않는다.
            val ours = routeName != null
            Log.i(TAG, "onDestinationArrived: 이 앱이 요청한 길안내=$ours")
            if (ours) {
                routeName = null
                _arrivals.tryEmit(Unit)
            }
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "onError: $errorCode")
            val waiting = estimate
            when {
                waiting != null && !waiting.isCompleted -> waiting.complete(null)
                routeName != null -> {
                    routeName = null
                    _routeFailed.tryEmit(Unit)
                }
            }
        }
    }

    override fun connect() {
        calling {
            if (helper != null) return@calling
            val sdk = NaviHelper(app)
            helper = sdk
            sdk.initialize(
                onConnected = {
                    Log.i(TAG, "내비 서비스 연결됨")
                    calling {
                        sdk.addListener(listener)
                        _connected.value = true
                    }
                },
                onFailed = { e ->
                    Log.w(TAG, "내비 서비스에 연결하지 못했습니다: $e")
                    _connected.value = false
                    calling { if (helper === sdk) helper = null }
                },
            )
        }
    }

    override val guiding: Boolean get() = routeName != null

    // 이 앱이 건 길안내만 끝낸다. 내비가 스스로 화면 앞으로 나온다(cancelRoute 뒤 0.1초쯤, 2026.11.02 에뮬레이터).
    override fun cancelRoute() = calling {
        if (routeName != null) {
            routeName = null
            helper?.cancelRoute()
        }
    }

    // 반납을 마쳤는데 이 앱이 건 반납 지점 길안내가 남아 있으면 함께 끝낸다. 고객이 직접 설정한 목적지는 건드리지 않는다.
    override fun disconnect() {
        calling {
            _connected.value = false
            // 끊다가 실패해도 경로를 찾지 못했다고 알릴 일은 아니다.
            runCatching {
                helper?.let {
                    if (routeName != null) it.cancelRoute()
                    it.removeListener(listener)
                    it.release()
                }
            }
            routeName = null
            helper = null
        }
    }

    override suspend fun estimateArrivalPercent(point: ReturnPoint): Int? {
        val waiting = CompletableDeferred<Int?>()
        estimate = waiting
        try {
            withContext(calls) {
                helper?.requestTrip(TripRequest(point.latitude, point.longitude, point.poiId, point.name, point.poiSubId))
            }
            return withTimeoutOrNull(ESTIMATE_WITHIN) { waiting.await() }
        } finally {
            estimate = null
        }
    }

    override fun startRoute(point: ReturnPoint) = calling {
        routeName = point.name
        helper?.requestRoute(
            RouteInfo(
                latitude = point.latitude,
                longitude = point.longitude,
                poiName = point.name,
                poiId = point.poiId,
                address = point.address,
                poiSubId = point.poiSubId,
            ),
        )
    }

    override fun showNearbyChargers() = calling {
        helper?.requestNavigationScreen(NavigationScreenRequest.NearbyChargingStations)
    }

    private fun calling(block: () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                // SDK가 던져도 앱이 죽으면 안 된다. 화면에는 경로를 찾지 못한 것으로 보인다.
                Log.w(TAG, "호출 실패: $e")
                _routeFailed.tryEmit(Unit)
            }
        }
    }

    private companion object {
        const val TAG = "ChagokNavi"
        // 에뮬레이터에서 4초쯤 걸렸다(경로 서버 왕복 포함).
        val ESTIMATE_WITHIN = 8.seconds
    }
}
