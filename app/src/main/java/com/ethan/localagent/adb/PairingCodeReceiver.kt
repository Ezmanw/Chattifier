package com.ethan.localagent.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.ethan.localagent.app

/** Receives the pairing code typed into the notification, so the user never leaves Settings. */
class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString() ?: return
        context.app.adb.submitCode(code)
    }

    companion object {
        const val KEY_CODE = "pairing_code"
    }
}
