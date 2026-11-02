package app.chagok.rental

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import app.chagok.MainActivity
import app.chagok.R
import app.chagok.core.rental.Rental

/**
 * 차를 켰을 때 열린 대여가 없으면 띄우는 알림. '고객이 앱을 어떻게 찾느냐'의 답이다. 누르면 고지 화면(V-02)으로 간다. 앱을 열면 지운다.
 * 어느 앱이든 같은 인텐트를 보낼 수 있지만 고지 화면을 여는 데서 끝나므로 되돌릴 수 있다.
 */
object PickupReminder {
    const val ACTION_START = "app.chagok.action.START_PICKUP"

    private const val CHANNEL = "rental_pickup"
    private const val NOTIFICATION_ID = 3

    fun show(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, Rental.START_TITLE, NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).setAction(ACTION_START), PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_chagok)
                .setContentTitle(Rental.START_TITLE)
                .setContentText(notificationBody(Rental.START_TEXT))
                // 범주가 없거나 reminder면 차량 시스템이 화면 위에 띄우지 않고 알림 창에만 쌓는다(2026.11.02 에뮬레이터). 차를 켠 고객이 알림 창을 열기 전에는 보지 못한다.
                // 시스템 앱이 아닌 앱에서 뜨는 것을 확인한 범주가 navigation뿐이라 뜻이 꼭 맞지 않아도 쓴다. 실차에서 다시 확인해야 한다.
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

// 알림 창의 어두운 바탕에서 읽히는 밝은 회색이다. 차량 알림 창은 낮에도 어둡다.
private const val BODY_COLOR = 0xFFC4C8CC.toInt()

/**
 * 알림 본문. 차량 알림 창이 다른 앱(시스템 앱이 아닌 앱)의 본문을 바탕과 거의 같은 색으로 그려 읽을 수 없어(2026.11.02 에뮬레이터) 글자 색을 직접 준다.
 * setColor나 BigTextStyle로는 바뀌지 않았다.
 */
internal fun notificationBody(text: String): CharSequence =
    SpannableString(text).apply { setSpan(ForegroundColorSpan(BODY_COLOR), 0, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE) }
