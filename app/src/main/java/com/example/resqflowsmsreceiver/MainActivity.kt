
package com.example.resqflowsmsreceiver

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.text.InputType
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var statusText: TextView
    private lateinit var pollButton: Button

    private val prefs by lazy {
        getSharedPreferences("receiver_settings", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 32)
        }

        fun addText(text: String, size: Float, bold: Boolean = false) {
            layout.addView(TextView(this).apply {
                this.text = text
                textSize = size
                if (bold) setTypeface(null, Typeface.BOLD)
                setPadding(0, 12, 0, 12)
            })
        }

        fun addButton(label: String, action: () -> Unit): Button {
            val button = Button(this).apply {
                text = label
                setOnClickListener { action() }
            }
            layout.addView(button)
            return button
        }

        addText("ResQFlow SMS Receiver", 24f, true)
        addText("Configure your backend and send queued clarification SMS through this phone's SIM.", 16f)

        addText("Backend HTTPS URL", 16f, true)
        urlInput = EditText(this).apply {
            hint = "https://your-backend-domain.com"
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(prefs.getString("backend_url", ""))
        }
        layout.addView(urlInput)

        addText("SMS device token", 16f, true)
        tokenInput = EditText(this).apply {
            hint = "Paste the token from your backend .env"
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(prefs.getString("device_token", ""))
        }
        layout.addView(tokenInput)

        addButton("Save settings") {
            val url = urlInput.text.toString().trim().trimEnd('/')
            val token = tokenInput.text.toString().trim()

            if (!url.startsWith("https://", ignoreCase = true) ||
                url.substringAfter("://").isBlank() ||
                url.contains(" ") || token.isBlank()
            ) {
                Toast.makeText(
                    this,
                    "Enter a valid HTTPS URL and device token",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                prefs.edit()
                    .putString("backend_url", url)
                    .putString("device_token", token)
                    .apply()

                refreshStatus()
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
        }

        addButton("Enable SMS permissions") {
            requestSmsPermissions()
        }

        pollButton = addButton("Poll & Send SMS") {
            pollAndSend()
        }

        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 24, 0, 12)
        }
        layout.addView(
            statusText,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        addButton("Refresh status") {
            refreshStatus()
        }

        setContentView(layout)
        refreshStatus()
    }

    private fun requestSmsPermissions() {
        val missing = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS
        ).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            Toast.makeText(this, "SMS permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 100) {
            refreshStatus()
            Toast.makeText(
                this,
                if (grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                ) "Requested permissions granted"
                else "Some SMS permissions were denied",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun refreshStatus() {
        val receiveGranted =
            checkSelfPermission(Manifest.permission.RECEIVE_SMS) ==
                    PackageManager.PERMISSION_GRANTED
        val sendGranted =
            checkSelfPermission(Manifest.permission.SEND_SMS) ==
                    PackageManager.PERMISSION_GRANTED

        val backend = prefs.getString("backend_url", "").orEmpty()
        val token = prefs.getString("device_token", "").orEmpty()
        val result = prefs.getString(
            "last_result",
            "No SMS operation recorded yet."
        )

        statusText.text = """
            Receive SMS permission: ${if (receiveGranted) "Granted" else "Not granted"}
            Send SMS permission: ${if (sendGranted) "Granted" else "Not granted"}
            Backend URL: ${if (backend.isBlank()) "Not configured" else "Saved"}
            Device token: ${if (token.isBlank()) "Not configured" else "Saved"}
            $result
        """.trimIndent()
    }

    private fun pollAndSend() {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestSmsPermissions()
            return
        }

        val backend = prefs.getString("backend_url", "").orEmpty()
        val token = prefs.getString("device_token", "").orEmpty()

        if (backend.isBlank() || token.isBlank()) {
            Toast.makeText(this, "Save the backend URL and token first", Toast.LENGTH_LONG).show()
            return
        }

        pollButton.isEnabled = false
        statusText.text = "Checking the SMS queue..."

        Thread {
            var connection: HttpURLConnection? = null

            try {
                val endpoint = "$backend/api/requests/sms-device/next"
                connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("X-ResQFlow-SMS-Token", token)

                val code = connection.responseCode
                val responseStream =
                    if (code in 200..299) connection.inputStream
                    else connection.errorStream

                val response = responseStream?.bufferedReader()?.use {
                    it.readText()
                }.orEmpty()

                if (code !in 200..299) {
                    throw IllegalStateException("Queue request failed: HTTP $code $response")
                }

                val json = JSONObject(response)

                if (json.isNull("id")) {
                    runOnUiThread {
                        statusText.text = "No queued clarification SMS."
                    }
                } else {
                    val id = json.getInt("id")
                    val destination = json.getString("to")
                    val text = json.getString("text")

                    prefs.edit()
                        .putString("last_result", "Sending clarification #$id...")
                        .apply()

                    runOnUiThread {
                        statusText.text = "Sending clarification #$id..."
                    }

                    sendSms(id, destination, text, backend, token)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text =
                        "Queue error: ${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                connection?.disconnect()
                runOnUiThread {
                    pollButton.isEnabled = true
                    refreshStatus()
                }
            }
        }.start()
    }

    private fun sendSms(
        id: Int,
        destination: String,
        text: String,
        backend: String,
        token: String
    ) {
        try {
            val phone = destination.replace(Regex("[\\s()-]"), "")
            require(phone.matches(Regex("^\\+?[0-9]{7,15}$"))) {
                "Invalid destination phone number"
            }

            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(text)

            if (parts.isEmpty()) {
                throw IllegalStateException("SMS message is empty")
            }

            val sentIntents = ArrayList<PendingIntent>()

            parts.forEachIndexed { index, _ ->
                val callbackIntent = Intent(this, SmsSentReceiver::class.java).apply {
                    data = Uri.parse("resqflow-sms://$id/$index")
                    putExtra("clarification_id", id)
                    putExtra("part_count", parts.size)
                    putExtra("backend_url", backend)
                    putExtra("device_token", token)
                }

                val requestCode = (id * 100 + index) and 0x7fffffff
                sentIntents.add(
                    PendingIntent.getBroadcast(
                        this,
                        requestCode,
                        callbackIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or
                                PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            smsManager.sendMultipartTextMessage(
                phone,
                null,
                parts,
                sentIntents,
                null
            )

            prefs.edit()
                .putString("last_result", "SMS #$id submitted to Android's SMS service.")
                .apply()

            runOnUiThread {
                statusText.text =
                    "SMS #$id submitted. Waiting for Android's send result..."
            }
        } catch (e: Exception) {
            prefs.edit()
                .putString(
                    "last_result",
                    "SMS send failed: ${e.message ?: e.javaClass.simpleName}"
                )
                .apply()

            reportImmediateFailure(id, backend, token, e.message ?: "SMS send failed")
            runOnUiThread { refreshStatus() }
        }
    }

    private fun reportImmediateFailure(
        id: Int,
        backend: String,
        token: String,
        error: String
    ) {
        Thread {
            var connection: HttpURLConnection? = null

            try {
                connection = URL(
                    "$backend/api/requests/sms-device/$id/result"
                ).openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-ResQFlow-SMS-Token", token)

                val payload = JSONObject()
                    .put("result", "FAILED")
                    .put("error", error)
                    .toString()

                connection.outputStream.use {
                    it.write(payload.toByteArray(Charsets.UTF_8))
                }

                connection.responseCode
            } catch (_: Exception) {
                // Keep the local error visible even if the backend cannot be reached.
            } finally {
                connection?.disconnect()
            }
        }.start()
    }
}
