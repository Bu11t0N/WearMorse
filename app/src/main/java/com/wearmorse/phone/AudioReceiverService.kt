package com.wearmorse.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.InputStream

/**
 * AudioReceiverService.kt — модуль :app (телефон)
 * Путь: app/src/main/java/com/wearmorse/phone/AudioReceiverService.kt
 *
 * Фоновый сервис телефона. Регистрирует ChannelClient.ChannelCallback
 * и при открытии канала читает RAW PCM (16 bit, 16000 Hz) в реальном времени,
 * воспроизводя через AudioTrack.
 *
 * Останавливается кнопкой «ВЫКЛЮЧИТЬ ПРИЕМ ЗВУКА» в MainActivity —
 * в onDestroy() снимается callback и освобождается AudioTrack.
 */
class AudioReceiverService : Service() {

    companion object {
        private const val TAG = "AudioReceiverService"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_receiver_channel"

        /**
         * Путь канала — должен совпадать с SecretSpyService на часах.
         * Часы открывают этот канал, телефон принимает входящий поток.
         */
        const val AUDIO_CHANNEL_PATH = "/audio_from_wear"

        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var channelClient: ChannelClient
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var isServiceActive = true

    /** Слушатель открытия каналов от часов */
    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            if (channel.path != AUDIO_CHANNEL_PATH) return
            if (!isServiceActive) return

            Log.d(TAG, "Канал открыт: ${channel.path}, node=${channel.nodeId}")
            serviceScope.launch {
                try {
                    channelClient.getInputStream(channel).await().use { input ->
                        playIncomingStream(input)
                    }
                } catch (e: Exception) {
                    if (isServiceActive) {
                        Log.e(TAG, "Ошибка чтения аудиопотока", e)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        channelClient = Wearable.getChannelClient(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        channelClient.registerChannelCallback(channelCallback)
        Log.d(TAG, "ChannelCallback зарегистрирован: $AUDIO_CHANNEL_PATH")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Не перезапускать после явной остановки пользователем
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — останавливаем приём и освобождаем AudioTrack")

        isServiceActive = false

        channelClient.unregisterChannelCallback(channelCallback)
        releaseAudioTrack()

        serviceScope.cancel()
        serviceJob.cancel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        super.onDestroy()
    }

    /** Читает PCM из канала и пишет в AudioTrack без буферизации на диск */
    private suspend fun playIncomingStream(inputStream: InputStream) {
        val buffer = ByteArray(4096)

        try {
            prepareAudioTrack()
            val track = audioTrack ?: return

            while (serviceScope.isActive && isServiceActive) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead <= 0) break
                track.write(buffer, 0, bytesRead)
            }
        } catch (e: Exception) {
            if (isServiceActive) {
                Log.e(TAG, "Ошибка воспроизведения потока", e)
            }
        } finally {
            releaseAudioTrack()
        }
    }

    /** Создаёт и запускает AudioTrack для потокового воспроизведения */
    private fun prepareAudioTrack() {
        releaseAudioTrack()

        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = minBuffer.coerceAtLeast(4096)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()

        audioTrack?.play()
        Log.d(TAG, "AudioTrack запущен, sampleRate=$SAMPLE_RATE")
    }

    /** Останавливает и освобождает AudioTrack */
    private fun releaseAudioTrack() {
        audioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка освобождения AudioTrack", e)
            }
        }
        audioTrack = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.audio_service_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.audio_service_text)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.audio_service_title))
            .setContentText(getString(R.string.audio_service_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
