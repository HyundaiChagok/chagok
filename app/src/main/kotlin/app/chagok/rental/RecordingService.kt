package app.chagok.rental

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.chagok.ChagokApplication
import app.chagok.MainActivity
import app.chagok.R
import app.chagok.core.rental.Rental
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 대여가 열려 있는 동안 도는 포그라운드 서비스. 앱이 뒤로 가면 Android가 프로세스를 얼려 경고등 변화를 놓치므로, 대여 중에만 프로세스를 깨어 있게 한다.
 * 기록은 [TimelineKeeper]가 하고, 여기서는 알림을 띄우고, 화면 없이 프로세스가 떴을 때(재부팅, 시스템 재시작) 대여를 이어 달라고 부르기만 한다.
 * 이을 대여가 없으면 바로 멈춘다. 반납을 마치거나 무효 처리하면 [RentalController]가 멈춘다.
 *
 * 형식은 specialUse다. 연결 기기·미디어·위치 등 정해진 형식 어디에도 맞지 않고, dataSync는 Android 15부터 시간 제한과 부팅 시작 제한이 있다.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService마다 5초 안에 불러야 한다. 부팅 직후 차가 바빠도 늦지 않게 무거운 일(AppGraph)보다 먼저 한다.
        goForeground()
        scope.launch {
            val graph = (application as ChagokApplication).graph
            val open = try {
                graph.timeline.resumeSaved()
            } catch (e: Exception) {
                Log.w(TAG, "대여를 잇지 못했습니다: ${e.message}")
                false
            }
            Log.i(TAG, if (open) "대여 중 기록 서비스 실행 중" else "이을 대여가 없어 서비스를 멈춥니다")
            if (!open) stopSelf(startId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        Log.i(TAG, "대여 중 기록 서비스 멈춤")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, Rental.RECORDING_TITLE, NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_chagok)
            .setContentTitle(Rental.RECORDING_TITLE)
            .setContentText(notificationBody(Rental.RECORDING_TEXT))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: IllegalStateException) {
            // 시스템이 뒤에서 다시 시작한 경우 막힐 수 있다. 앱은 죽지 않고, 프로세스가 얼면 그 구간은 공백으로 남는다.
            Log.w(TAG, "포그라운드로 올리지 못했습니다: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ChagokRecording"
        private const val CHANNEL = "rental_recording"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, RecordingService::class.java))
            } catch (e: IllegalStateException) {
                // 앱이 뒤에 있을 때 막힐 수 있다. 기록은 이어 가되 프로세스가 얼면 그 구간은 공백으로 남는다.
                Log.w(TAG, "서비스를 시작하지 못했습니다: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}

/**
 * 재부팅이나 앱 업데이트 뒤에 열린 대여가 있으면 화면을 띄우지 않고 기록을 잇는다. 꺼져 있던 구간은 공백으로 남는다.
 * 차를 켰을 때 열린 대여가 없으면 인수 기록을 제안하는 알림을 띄운다([PickupReminder]). 저장된 기록을 믿을 수 없으면 새 인수를 받지 않으므로 띄우지 않는다.
 */
class RecordingRestarter : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val saved = RecordStore(File(context.filesDir, RecordStore.FOLDER)).readSession()
        val open = (saved as? Stored.Found)?.value?.isOpen == true
        Log.i("ChagokRecording", "${intent.action}: 열린 대여 $open")
        when {
            open -> RecordingService.start(context)
            saved !is Stored.Damaged && intent.action == Intent.ACTION_BOOT_COMPLETED -> PickupReminder.show(context)
        }
    }
}
