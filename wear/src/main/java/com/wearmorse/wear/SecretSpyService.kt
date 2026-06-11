package com.wearmorse.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.google.android.gms.tasks.Tasks
import java.io.OutputStream

/**
 * SecretSpyService.kt — модуль :wear (часы)
 * Путь: wear/src/main/java/com/wearmorse/wear/SecretSpyService.kt
 *
 * Фоновый сервис часов. При работе:
 * 1) Записывает микрофон (RAW PCM 16 bit, 16000 Hz) и стримит на телефон через ChannelClient.
 * 2) Принимает текст с телефона (MessageClient, путь "/to_wear_morse"):
 *    - вибрация Морзе через MorseVibrator;
 *    - отображение текста на WearMainActivity через локальный Broadcast.
 *
 * При выключении микрофона кнопкой на часах сервис полностью останавливается,
 * все потоки закрываются, AudioRecord освобождается в onDestroy().
 */
class SecretSpyService : Service() {

    companion object {
        private const val TAG = "SecretSpyService"

        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "secret_spy_channel"

        /** Путь сообщения с телефона — совпадает с MainActivity.MESSAGE_PATH_TO_WEAR */
        private const val MESSAGE_PATH_FROM_PHONE = "/to_wear_morse"

        /** Путь аудиоканала — совпадает с AudioReceiverService.AUDIO_CHANNEL_PATH на телефоне */
        private const val AUDIO_CHANNEL_PATH = "/audio_from_wear"

        /** PCM 16 bit, моно, 16000 Hz — синхронизировано с AudioTrack на телефоне */
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** Broadcast: новое текстовое сообщение для WearMainActivity */
        const val ACTION_MESSAGE_RECEIVED = "com.wearmorse.wear.ACTION_MESSAGE_RECEIVED"
        const val EXTRA_MESSAGE_TEXT = "extra_message_text"

        /** Broadcast: изменился статус микрофона (сервис стартовал / остановился) */
        const val ACTION_MIC_STATE_CHANGED = "com.wearmorse.wear.ACTION_MIC_STATE_CHANGED"
        const val EXTRA_MIC_ENABLED = "extra_mic_enabled"
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    @Volatile
    private var isRecording = false

    /** Флаг жизненного цикла сервиса — при false циклы стриминга завершаются */
    @Volatile
    private var isServiceActive = true

    private var audioRecord: AudioRecord? = null
    private var activeChannel: ChannelClient.Channel? = null
    private var activeOutputStream: OutputStream? = null

    private lateinit var morseVibrator: MorseVibrator
    private lateinit var channelClient: ChannelClient

    /** Слушатель текстовых сообщений с телефона */
    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        onMessageReceived(event)
    }

    override fun onCreate() {
        super.onCreate()
        morseVibrator = MorseVibrator(this)
        channelClient = Wearable.getChannelClient(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        Wearable.getMessageClient(this).addListener(messageListener)
        sendMicStateBroadcast(enabled = true)

        serviceScope.launch {
            startAudioStreamingLoop()
        }

        Log.d(TAG, "Сервис запущен")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Не перезапускать автоматически после остановки пользователем
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — освобождаем все ресурсы")

        isServiceActive = false
        isRecording = false

        stopRecording()
        closeActiveChannel()
        closeActiveOutputStream()

        Wearable.getMessageClient(this).removeListener(messageListener)

        serviceScope.cancel()
        serviceJob.cancel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        sendMicStateBroadcast(enabled = false)
        super.onDestroy()
    }

    /** Обрабатывает текст с телефона: Морзе + broadcast на экран часов */
    private fun onMessageReceived(event: MessageEvent) {
        if (event.path != MESSAGE_PATH_FROM_PHONE) return

        val text = String(event.data, Charsets.UTF_8)
        Log.d(TAG, "Получено сообщение: $text")

        morseVibrator.play(text)
        sendMessageBroadcast(text)
    }

    /** Отправляет текст в WearMainActivity через локальный broadcast (только наше приложение) */
    private fun sendMessageBroadcast(text: String) {
        val intent = Intent(ACTION_MESSAGE_RECEIVED).apply {
            setPackage(packageName)
            putExtra(EXTRA_MESSAGE_TEXT, text)
        }
        sendBroadcast(intent)
    }

    /** Сообщает WearMainActivity, включён или выключен микрофон */
    private fun sendMicStateBroadcast(enabled: Boolean) {
        val intent = Intent(ACTION_MIC_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_MIC_ENABLED, enabled)
        }
        sendBroadcast(intent)
    }

    /**
     * Основной цикл: ищет телефон, открывает канал, стримит PCM.
     * Завершается, когда isServiceActive == false (onDestroy).
     */
    private suspend fun startAudioStreamingLoop() {
        if (!hasRecordPermission()) {
            Log.e(TAG, "Нет разрешения RECORD_AUDIO")
            stopSelf()
            return
        }

        val nodeClient = Wearable.getNodeClient(this)

        while (serviceScope.isActive && isServiceActive) {
            try {
                val nodes = nodeClient.connectedNodes.await()
                val phoneNode = nodes.firstOrNull()

                if (phoneNode == null) {
                    Log.w(TAG, "Телефон не найден, повтор через 3 сек…")
                    delay(3000)
                    continue
                }

                Log.d(TAG, "Открываем канал к телефону: ${phoneNode.displayName}")

                val channel = channelClient
                    .openChannel(phoneNode.id, AUDIO_CHANNEL_PATH)
                    .await()

                activeChannel = channel
                streamAudioToChannel(channel)
            } catch (e: Exception) {
                if (!isServiceActive) break
                Log.e(TAG, "Ошибка стриминга, переподключение…", e)
                stopRecording()
                closeActiveOutputStream()
                closeActiveChannel()
                delay(2000)
            }
        }
    }

    /**
     * Записывает микрофон и пишет байты в OutputStream канала.
     * При остановке сервиса isRecording и isServiceActive сбрасываются в onDestroy().
     */
    private suspend fun streamAudioToChannel(channel: ChannelClient.Channel) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Некорректный bufferSize: $bufferSize")
            return
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord не инициализирован")
            record.release()
            return
        }

        audioRecord = record
        isRecording = true

        val buffer = ByteArray(bufferSize)

        try {
            record.startRecording()
            Log.d(TAG, "Запись микрофона начата")

            val output = channelClient.getOutputStream(channel).await()
            activeOutputStream = output

            while (serviceScope.isActive && isServiceActive && isRecording) {
                val read = record.read(buffer, 0, buffer.size)
                when {
                    read > 0 -> {
                        output.write(buffer, 0, read)
                        output.flush()
                    }
                    read < 0 -> {
                        Log.w(TAG, "AudioRecord.read вернул ошибку: $read")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            if (isServiceActive) {
                Log.e(TAG, "Поток аудио прерван", e)
                throw e
            }
        } finally {
            stopRecording()
            closeActiveOutputStream()
            closeActiveChannel()
        }
    }

    /** Останавливает и освобождает AudioRecord */
    private fun stopRecording() {
        isRecording = false
        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка остановки AudioRecord", e)
            }
        }
        audioRecord = null
    }

    /** Закрывает исходящий поток канала */
    private fun closeActiveOutputStream() {
        try {
            activeOutputStream?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка закрытия OutputStream", e)
        } finally {
            activeOutputStream = null
        }
    }

    /** Закрывает активный Wearable-канал (синхронно, для надёжного onDestroy) */
    private fun closeActiveChannel() {
        val channel = activeChannel ?: return
        activeChannel = null
        try {
            Tasks.await(channelClient.close(channel))
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка закрытия канала", e)
        }
    }

    private fun hasRecordPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.spy_service_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.spy_service_text)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WearMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.spy_service_title))
            .setContentText(getString(R.string.spy_service_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
