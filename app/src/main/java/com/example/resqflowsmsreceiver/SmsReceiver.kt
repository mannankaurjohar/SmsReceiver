package com.example.resqflowsmsreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val body = messages.joinToString("") { it.messageBody.orEmpty() }.trim()
        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()

        if (
            !body.startsWith("RF:SOS|") &&
            !body.startsWith("RF:SUP|") &&
            !body.startsWith("RF:CLAR|")
        ) return

        val pending = goAsync()

        Thread {
            try {
                val prefs = context.getSharedPreferences(
                    "receiver_settings", Context.MODE_PRIVATE
                )
                val baseUrl = prefs.getString("backend_url", "")
                    ?.trim()?.trimEnd('/').orEmpty()

                require(baseUrl.startsWith("https://", ignoreCase = true)) {
                    "Configure an HTTPS backend URL first"
                }

                val connection = URL(
                    "$baseUrl/api/requests/sms-inbound"
                ).openConnection() as HttpURLConnection

                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    connection.doOutput = true
                    connection.setRequestProperty(
                        "Content-Type", "application/json; charset=utf-8"
                    )

                    val payload = JSONObject()
                        .put("text", body)
                        .put("phone", sender)
                        .toString()

                    connection.outputStream.use {
                        it.write(payload.toByteArray(Charsets.UTF_8))
                    }

                    val status = connection.responseCode
                    prefs.edit().putString(
                        "last_result", "Last forwarding result: HTTP $status"
                    ).apply()
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                context.getSharedPreferences(
                    "receiver_settings", Context.MODE_PRIVATE
                ).edit().putString(
                    "last_result",
                    "Forwarding failed: ${e.javaClass.simpleName}"
                ).apply()
            } finally {
                pending.finish()
            }
        }.start()
    }
}
