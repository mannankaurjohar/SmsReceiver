
package com.example.resqflowsmsreceiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class SmsSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("clarification_id", -1)
        val partCount = intent.getIntExtra("part_count", 1)
        val backendUrl = intent.getStringExtra("backend_url").orEmpty()
        val token = intent.getStringExtra("device_token").orEmpty()

        if (id <= 0 || backendUrl.isBlank() || token.isBlank()) return

        val succeeded = resultCode == Activity.RESULT_OK
        val prefs = context.getSharedPreferences("sms_send_results", Context.MODE_PRIVATE)
        val prefix = "clarification_$id"

        var completed = false
        var finalSuccess = false
        var error = ""

        synchronized(SmsSentReceiver::class.java) {
            val done = prefs.getInt("${prefix}_done", 0) + 1
            val failedBefore = prefs.getBoolean("${prefix}_failed", false)
            val failed = failedBefore || !succeeded

            prefs.edit()
                .putInt("${prefix}_done", done)
                .putBoolean("${prefix}_failed", failed)
                .putString(
                    "${prefix}_error",
                    if (!succeeded) "One or more SMS parts failed (result code $resultCode)."
                    else prefs.getString("${prefix}_error", "").orEmpty()
                )
                .apply()

            if (done >= partCount) {
                completed = true
                finalSuccess = !failed
                error = prefs.getString("${prefix}_error", "").orEmpty()
                prefs.edit()
                    .remove("${prefix}_done")
                    .remove("${prefix}_failed")
                    .remove("${prefix}_error")
                    .apply()
            }
        }

        if (!completed) return

        Thread {
            var connection: HttpURLConnection? = null
            try {
                val endpoint =
                    "${backendUrl.trimEnd('/')}/api/requests/sms-device/$id/result"
                connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-ResQFlow-SMS-Token", token)

                val payload = JSONObject()
                    .put("result", if (finalSuccess) "SENT" else "FAILED")
                    .put("error", error)
                    .toString()

                connection.outputStream.use {
                    it.write(payload.toByteArray(Charsets.UTF_8))
                }

                // The backend response can be inspected in the app status later.
                connection.responseCode
            } catch (_: Exception) {
                // SMS may have been sent even if reporting the result failed.
            } finally {
                connection?.disconnect()
            }
        }.start()
    }
}
