package com.ethan.localagent.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * "Ask Chattifier" on the watch face. Small slots open the app; the wide one starts listening
 * straight away and says whether the phone is in reach.
 */
class AskComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val ready = request.complicationType != ComplicationType.LONG_TEXT || PhoneLink(this).status(timeoutMs = 2_000) != null
        return build(request.complicationType, ready)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, ready = true)

    private fun build(type: ComplicationType, ready: Boolean): ComplicationData? {
        val icon = Icon.createWithResource(this, R.drawable.ic_complication)
        val mono = MonochromaticImage.Builder(icon).build()
        val label = text("Ask Chattifier")
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text("Ask"), label)
                .setMonochromaticImage(mono).setTapAction(open(listen = false)).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(if (ready) "Phone ready · offline OK" else "Phone out of reach"), label)
                .setTitle(label).setMonochromaticImage(mono).setTapAction(open(listen = true)).build()
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(SmallImage.Builder(icon, SmallImageType.ICON).build(), label)
                .setTapAction(open(listen = false)).build()
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(mono, label)
                .setTapAction(open(listen = false)).build()
            else -> null
        }
    }

    private fun text(s: String): ComplicationText = PlainComplicationText.Builder(s).build()

    private fun open(listen: Boolean): PendingIntent = PendingIntent.getActivity(
        this,
        if (listen) 1 else 0,
        Intent(this, WatchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(WatchActivity.EXTRA_LISTEN, listen),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
