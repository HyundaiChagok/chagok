package app.chagok

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.chagok.core.gleo.GleoFunction
import app.chagok.rental.GleoResponder
import app.chagok.rental.PickupReminder
import app.chagok.rental.RentalController
import app.chagok.ui.ChagokScreen
import app.chagok.ui.ChagokTheme
import java.io.FileDescriptor
import java.io.PrintWriter

class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as ChagokApplication).graph
    private val controller: RentalController by viewModels { graph.controllerFactory }

    // 대여 중 기록 서비스의 알림을 보이려면 권한이 필요하다. 거절해도 기록은 돌고 고지 화면이 같은 내용을 알린다.
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChagokTheme {
                ChagokScreen(
                    controller = controller,
                    demo = graph.demo,
                    source = graph.source,
                    speaking = graph.voice.speaking,
                    voiceEngine = graph.voice.engine,
                    platform = graph.platform,
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handle(intent)
    }

    // singleTask라 앱이 떠 있으면 Gleo 호출이 여기로 온다.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == PickupReminder.ACTION_START) {
            // 최근 앱 목록에서 다시 열면 같은 인텐트가 또 오므로 그때는 다시 돌리지 않는다.
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) controller.startFromNotification()
        } else {
            handleGleo(intent)
        }
    }

    // 정해진 함수만 받는다. 최근 앱 목록에서 다시 열면 같은 인텐트가 또 오므로 그때는 다시 돌리지 않는다. 망가진 값을 넣어 보내도 죽지 않는다.
    private fun handleGleo(intent: Intent?) {
        val function = GleoFunction.of(intent?.action) ?: return
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0 || function == GleoFunction.WHAT_TO_CHECK) return
        val raw = try {
            intent.getStringExtra(GleoResponder.ACTION_PARAM)
        } catch (e: RuntimeException) {
            GleoResponder.UNREADABLE
        }
        controller.onGleo(function, raw)
    }

    // '닫기'로 취소한 길안내 때문에 내비가 앞으로 나오면 바로 돌아온다. 내비가 그려지기 전에 부를수록 덜 깜박여서 포커스를 잃는 순간을 먼저 쓴다.
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (!isTopResumedActivity) comeBackIfCovered()
    }

    override fun onPause() {
        super.onPause()
        comeBackIfCovered()
    }

    // 이 호출은 플랫폼이 백그라운드 시작으로 보아 창만 앞으로 오고 포커스는 오지 않을 수 있다(2026.11.02 에뮬레이터 BAL_BLOCK, 다음 터치로 포커스가 온다). 창이 보인 뒤 한 번 더 부르면 포커스까지 온다.
    private fun comeBackIfCovered() {
        if (!controller.reclaimFront()) return
        val self = Intent(this, MainActivity::class.java)
        startActivity(self)
        window.decorView.postDelayed({ if (!isFinishing && !hasWindowFocus()) startActivity(self) }, REFOCUS_AFTER_MILLIS)
    }

    // 다른 앱이 화면을 덮으면 반납 체크의 주기 확인을 멈춘다. 대여 중 타임라인은 이어 간다.
    override fun onStart() {
        super.onStart()
        controller.setVisible(true)
        graph.voice.warmUp()
        PickupReminder.cancel(this)
    }

    override fun onStop() {
        controller.setVisible(false)
        super.onStop()
    }

    // 시연 콘솔의 점검(p)이 `adb shell dumpsys activity app.chagok/.MainActivity`로 읽는다. 읽기만 하고, 차는 뒤에서 읽어 다음 물음에 적는다(메인 스레드를 막지 않는다).
    override fun dump(prefix: String, fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        writer.println("chagok-preflight ${controller.preflightLine()} voice=${graph.voice.engine.value} source=${graph.source} schema=${graph.platform.gleoFunctions} telltale=${graph.platform.telltaleGranted}")
        controller.readDiagnosis()
        super.dump(prefix, fd, writer, args)
    }

    // 뒤로 가기로 화면이 없어지면 대여 중 기록 서비스만 남으니 음성 엔진을 내린다.
    override fun onDestroy() {
        if (isFinishing) graph.voice.release()
        super.onDestroy()
    }

    private companion object {
        const val REFOCUS_AFTER_MILLIS = 800L
    }
}
