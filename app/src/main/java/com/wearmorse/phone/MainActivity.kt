package com.wearmorse.phone

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * MainActivity.kt — модуль :app (телефон)
 * Путь: app/src/main/java/com/wearmorse/phone/MainActivity.kt
 *
 * Экран телефона:
 * - поле ввода и кнопка «Отправить» (текст на часы через MessageClient);
 * - автозапуск AudioReceiverService при открытии приложения;
 * - кнопка «ВЫКЛЮЧИТЬ ПРИЕМ ЗВУКА» для полной остановки приёма аудио.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Путь сообщения для передачи текста на часы (Морзе + текст на экране) */
        const val MESSAGE_PATH_TO_WEAR = "/to_wear_morse"
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var messageInput: EditText
    private lateinit var statusText: TextView
    private lateinit var audioStatusText: TextView
    private lateinit var stopAudioButton: Button
    private lateinit var nodeClient: NodeClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        messageInput = findViewById(R.id.messageInput)
        statusText = findViewById(R.id.statusText)
        audioStatusText = findViewById(R.id.audioStatusText)
        stopAudioButton = findViewById(R.id.stopAudioButton)
        nodeClient = Wearable.getNodeClient(this)

        requestNotificationPermission()

        // При первом запуске включаем приём звука с часов
        if (!isAudioReceiverRunning()) {
            startAudioReceiverService()
        }
        updateAudioUi()

        findViewById<Button>(R.id.sendButton).setOnClickListener {
            sendMessageToWatch()
        }

        stopAudioButton.setOnClickListener {
            stopAudioReceiverService()
        }
    }

    override fun onResume() {
        super.onResume()
        updateAudioUi()
    }

    /** Запускает foreground-сервис приёма аудио с часов */
    private fun startAudioReceiverService() {
        startForegroundService(Intent(this, AudioReceiverService::class.java))
    }

    /** Полностью останавливает AudioReceiverService и освобождает AudioTrack */
    private fun stopAudioReceiverService() {
        stopService(Intent(this, AudioReceiverService::class.java))
        updateAudioUi()
        Toast.makeText(this, R.string.audio_stopped_toast, Toast.LENGTH_SHORT).show()
    }

    /** Обновляет надпись о состоянии приёма звука и доступность кнопки */
    private fun updateAudioUi() {
        val isRunning = isAudioReceiverRunning()
        audioStatusText.setText(
            if (isRunning) R.string.audio_receiving_on else R.string.audio_receiving_off
        )
        stopAudioButton.isEnabled = isRunning
    }

    /** Проверяет, работает ли AudioReceiverService */
    private fun isAudioReceiverRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any { serviceInfo ->
            serviceInfo.service.className == AudioReceiverService::class.java.name
        }
    }

    /** Отправляет текст на все подключённые часы */
    private fun sendMessageToWatch() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Введите текст", Toast.LENGTH_SHORT).show()
            return
        }

        statusText.setText(R.string.status_sending)

        scope.launch {
            try {
                val nodes = withContext(Dispatchers.IO) {
                    nodeClient.connectedNodes.await()
                }

                if (nodes.isEmpty()) {
                    statusText.setText(R.string.status_no_watch)
                    return@launch
                }

                val messageClient = Wearable.getMessageClient(this@MainActivity)
                val payload = text.toByteArray(Charsets.UTF_8)

                withContext(Dispatchers.IO) {
                    for (node in nodes) {
                        messageClient.sendMessage(node.id, MESSAGE_PATH_TO_WEAR, payload).await()
                    }
                }

                statusText.setText(R.string.status_sent)
            } catch (e: Exception) {
                statusText.setText(R.string.status_error)
                Toast.makeText(
                    this@MainActivity,
                    "Ошибка: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Запрашивает разрешение на уведомления (Android 13+) для foreground-сервиса */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1
            )
        }
    }
}
