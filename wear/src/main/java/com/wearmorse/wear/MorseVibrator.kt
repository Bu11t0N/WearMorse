package com.wearmorse.wear

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MorseVibrator.kt — модуль :wear (часы)
 * Путь: wear/src/main/java/com/wearmorse/wear/MorseVibrator.kt
 *
 * Переводит текст в азбуку Морзе и воспроизводит вибрацией на часах.
 * Поддерживает русский и английский алфавит, а также цифры.
 *
 * Тайминги:
 * - точка = 150 мс
 * - тире = 450 мс
 * - пауза внутри буквы = 150 мс
 * - пауза между буквами = 500 мс
 */
class MorseVibrator(context: Context) {

    companion object {
        /** Длительность точки (короткая вибрация) */
        private const val DOT_MS = 150L

        /** Длительность тире (длинная вибрация) */
        private const val DASH_MS = 450L

        /** Пауза между элементами одной буквы (точка/тире) */
        private const val INTRA_LETTER_GAP_MS = 150L

        /** Пауза между буквами */
        private const val INTER_LETTER_GAP_MS = 500L

        /** Пауза между словами (пробел в тексте) */
        private const val WORD_GAP_MS = 1400L

        /**
         * Полная карта символов → Морзе.
         * Ключи в нижнем регистре; при play() текст приводится к lowerCase().
         */
        private val MORSE_MAP: Map<Char, String> = mapOf(
            // --- Латиница (английский алфавит) ---
            'a' to ".-",
            'b' to "-...",
            'c' to "-.-.",
            'd' to "-..",
            'e' to ".",
            'f' to "..-.",
            'g' to "--.",
            'h' to "....",
            'i' to "..",
            'j' to ".---",
            'k' to "-.-",
            'l' to ".-..",
            'm' to "--",
            'n' to "-.",
            'o' to "---",
            'p' to ".--.",
            'q' to "--.-",
            'r' to ".-.",
            's' to "...",
            't' to "-",
            'u' to "..-",
            'v' to "...-",
            'w' to ".--",
            'x' to "-..-",
            'y' to "-.--",
            'z' to "--..",

            // --- Кириллица (русский алфавит, стандарт ITU) ---
            'а' to ".-",
            'б' to "-...",
            'в' to ".---",
            'г' to "--.",
            'д' to "-..",
            'е' to ".",
            'ё' to ".",
            'ж' to "...-",
            'з' to "--..",
            'и' to "..",
            'й' to ".---",
            'к' to "-.-",
            'л' to ".-..",
            'м' to "--",
            'н' to "-.",
            'о' to "---",
            'п' to ".--.",
            'р' to ".-.",
            'с' to "...",
            'т' to "-",
            'у' to "..-",
            'ф' to "..-.",
            'х' to "....",
            'ц' to "-.-.",
            'ч' to "---.",
            'ш' to "----",
            'щ' to "--.-",
            'ъ' to "--.--",
            'ы' to "-.--",
            'ь' to "-..-",
            'э' to "..-..",
            'ю' to "..--",
            'я' to ".-.-",

            // --- Цифры (общие для обоих языков) ---
            '0' to "-----",
            '1' to ".----",
            '2' to "..---",
            '3' to "...--",
            '4' to "....-",
            '5' to ".....",
            '6' to "-....",
            '7' to "--...",
            '8' to "---..",
            '9' to "----.",

            // --- Знаки препинания ---
            '.' to ".-.-.-",
            ',' to "--..--",
            '?' to "..--..",
            '!' to "-.-.--",
            '-' to "-....-",
            '/' to "-..-.",
            '@' to ".--.-.",
            '(' to "-.--.",
            ')' to "-.--.-",
            ':' to "---...",
            ';' to "-.-.-.",
            '=' to "-...-"
        )
    }

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Переводит текст в последовательность вибраций Морзе.
     * Неизвестные символы пропускаются.
     */
    fun play(text: String) {
        if (text.isBlank()) return

        val pattern = buildVibrationPattern(text)
        if (pattern.timings.size <= 1) return

        scope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(
                    pattern.timings,
                    pattern.amplitudes,
                    -1 // не повторять
                )
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern.timings, -1)
            }
        }
    }

    /** Собирает массивы длительностей и амплитуд для createWaveform */
    private fun buildVibrationPattern(text: String): VibrationPattern {
        val timings = mutableListOf<Long>()
        val amplitudes = mutableListOf<Int>()

        // createWaveform: первый элемент — задержка перед началом (0 = сразу)
        timings.add(0L)
        amplitudes.add(0)

        val normalized = text.lowercase()
        var letterIndex = 0

        for (char in normalized) {
            if (char.isWhitespace()) {
                appendPause(timings, amplitudes, WORD_GAP_MS)
                letterIndex = 0
                continue
            }

            val morse = MORSE_MAP[char] ?: continue

            if (letterIndex > 0) {
                appendPause(timings, amplitudes, INTER_LETTER_GAP_MS)
            }

            morse.forEachIndexed { symbolIndex, symbol ->
                if (symbolIndex > 0) {
                    appendPause(timings, amplitudes, INTRA_LETTER_GAP_MS)
                }

                when (symbol) {
                    '.' -> appendVibration(timings, amplitudes, DOT_MS)
                    '-' -> appendVibration(timings, amplitudes, DASH_MS)
                }
            }

            letterIndex++
        }

        return VibrationPattern(timings.toLongArray(), amplitudes.toIntArray())
    }

    /** Добавляет участок вибрации с максимальной амплитудой */
    private fun appendVibration(
        timings: MutableList<Long>,
        amplitudes: MutableList<Int>,
        durationMs: Long
    ) {
        timings.add(durationMs)
        amplitudes.add(VibrationEffect.DEFAULT_AMPLITUDE)
    }

    /** Добавляет паузу (амплитуда 0 — без вибрации) */
    private fun appendPause(
        timings: MutableList<Long>,
        amplitudes: MutableList<Int>,
        durationMs: Long
    ) {
        timings.add(durationMs)
        amplitudes.add(0)
    }

    private data class VibrationPattern(
        val timings: LongArray,
        val amplitudes: IntArray
    )
}
