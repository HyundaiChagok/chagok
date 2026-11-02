package app.chagok.rental

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.chagok.MainActivity
import app.chagok.R
import app.chagok.core.rental.ReturnTrip

/** 앱이 뒤에 있을 때 도착을 알린다. 누르면 앱으로 들어오고, 대여 중 화면이 '반납 체크 시작'을 보여 준다. 알림 권한을 거절했으면 음성만 남는다. */
class ArrivalNotification(context: Context) : ReturnTripKeeper.ArrivalNotice {
    private val app = context.applicationContext
    private val manager = app.getSystemService(NotificationManager::class.java)

    override fun show() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, ReturnTrip.ARRIVED_TITLE, NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(app, 1, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_chagok)
                .setContentTitle(ReturnTrip.ARRIVED_TITLE)
                .setContentText(notificationBody(ReturnTrip.ARRIVED_TEXT))
                // 범주가 없으면 차량 시스템이 화면 위에 띄우지 않고 알림 창에만 쌓는다(로그 'category not set', 2026.11.02 에뮬레이터). 내비를 보는 고객이 모르게 된다.
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    override fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL = "rental_arrival"
        const val NOTIFICATION_ID = 2
    }
}
