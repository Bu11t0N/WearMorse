package com.wearmorse.wear

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * WearMainActivity.kt — модуль :wear (часы)
 * Путь: wear/src/main/java/com/wearmorse/wear/WearMainActivity.kt
 *
 * Экран управления на часах:
 * - статус микрофона (Включен / Выключен);
 * - последнее текстовое сообщение с телефона;
 * - кнопка ВКЛ/ВЫКЛ для запуска и полной остановки SecretSpyService.
 */
class WearMainActivity : Activity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private lateinit var micStatusText: TextView
    private lateinit var lastMessageText: TextView
    private lateinit var micToggleButton: Button

    /** Принимает текст от SecretSpyService и обновляет поле «Последнее сообщение» */
    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SecretSpyService.ACTION_MESSAGE_RECEIVED) return
            val text = intent.getStringExtra(SecretSpyService.EXTRA_MESSAGE_TEXT) ?: return
            lastMessageText.text = text
        }
    }

    /** Синхронизирует UI, если сервис сам завершился (ошибка, система и т.д.) */
    private val micStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SecretSpyService.ACTION_MIC_STATE_CHANGED) return
            val enabled = intent.getBooleanExtra(SecretSpyService.EXTRA_MIC_ENABLED, false)
            updateMicUi(enabled)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wear_main)

        micStatusText = findViewById(R.id.micStatusText)
        lastMessageText = findViewById(R.id.lastMessageText)
        micToggleButton = findViewById(R.id.micToggleButton)

        micToggleButton.setOnClickListener { toggleMicrophoneService() }

        // Восстанавливаем состояние UI при повторном открытии экрана
        updateMicUi(isSpyServiceRunning())
    }

    override fun onStart() {
        super.onStart()
        registerAppReceiver(messageReceiver, SecretSpyService.ACTION_MESSAGE_RECEIVED)
        registerAppReceiver(micStateReceiver, SecretSpyService.ACTION_MIC_STATE_CHANGED)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(messageReceiver)
        unregisterReceiver(micStateReceiver)
    }

    /** Регистрирует локальный BroadcastReceiver только для нашего приложения */
    private fun registerAppReceiver(receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    /** Переключает SecretSpyService: запуск или полная остановка */
    private fun toggleMicrophoneService() {
        if (isSpyServiceRunning()) {
            stopSpyService()
        } else {
            if (!hasRequiredPermissions()) {
                ActivityCompat.requestPermissions(
                    this,
                    requiredPermissions(),
                    PERMISSION_REQUEST_CODE
                )
                return
            }
            startSpyService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return

        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startSpyService()
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_SHORT).show()
            updateMicUi(false)
        }
    }

    /** Запускает foreground-сервис записи и стриминга */
    private fun startSpyService() {
        val serviceIntent = Intent(this, SecretSpyService::class.java)
        startForegroundService(serviceIntent)
        updateMicUi(true)
    }

    /** Полностью останавливает сервис — запись, канал и ресурсы освобождаются в onDestroy() */
    private fun stopSpyService() {
        stopService(Intent(this, SecretSpyService::class.java))
        updateMicUi(false)
    }

    /** Обновляет текст статуса и подпись кнопки */
    private fun updateMicUi(micEnabled: Boolean) {
        if (micEnabled) {
            micStatusText.setText(R.string.mic_status_on)
            micToggleButton.setText(R.string.mic_turn_off)
        } else {
            micStatusText.setText(R.string.mic_status_off)
            micToggleButton.setText(R.string.mic_turn_on)
        }
    }

    /** Проверяет, запущен ли SecretSpyService в данный момент */
    private fun isSpyServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any { serviceInfo ->
            serviceInfo.service.className == SecretSpyService::class.java.name
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}
