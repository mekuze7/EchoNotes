package com.example.data.speech

import java.util.Calendar
import java.util.Locale

sealed class VoiceCommand(open val phrase: String) {
    data class Tag(
        override val phrase: String,
        val category: String
    ) : VoiceCommand(phrase)

    data class Reminder(
        override val phrase: String,
        val triggerAtMillis: Long
    ) : VoiceCommand(phrase)
}

object VoiceCommandParser {
    private val tagPattern = Regex(
        pattern = """\btag\s+this\s+as\s+(ideas|meetings|tasks|personal)\b""",
        option = RegexOption.IGNORE_CASE
    )
    private val reminderPattern = Regex(
        pattern = """\bremind\s+me\b.*?\b(today|tomorrow|on\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday))\s+at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""",
        option = RegexOption.IGNORE_CASE
    )

    fun findCommands(text: String, nowMillis: Long = System.currentTimeMillis()): List<VoiceCommand> {
        val commands = mutableListOf<VoiceCommand>()
        tagPattern.findAll(text).forEach { match ->
            val category = match.groupValues[1].lowercase(Locale.ROOT)
                .replaceFirstChar { it.uppercase(Locale.ROOT) }
            commands.add(VoiceCommand.Tag(match.value, category))
        }
        reminderPattern.findAll(text).forEach { match ->
            val triggerAt = parseReminderDateTime(
                dayText = match.groupValues[1],
                hourText = match.groupValues[2],
                minuteText = match.groupValues[3],
                meridiem = match.groupValues[4],
                nowMillis = nowMillis
            ) ?: return@forEach
            commands.add(VoiceCommand.Reminder(match.value, triggerAt))
        }
        return commands.sortedBy { text.indexOf(it.phrase, ignoreCase = true) }
    }

    fun removeCommandPhrases(text: String, phrases: List<String>): String {
        var result = text
        phrases.forEach { phrase ->
            val expression = phrase.trim().split(Regex("\\s+"))
                .joinToString("\\s+") { Regex.escape(it) }
            if (expression.isNotBlank()) {
                result = result.replace(
                    Regex("\\b$expression\\b", RegexOption.IGNORE_CASE),
                    " "
                )
            }
        }
        return result.replace(Regex("\\s+([,.!?])"), "$1")
            .replace(Regex("([.!?])\\s*[.!?]+"), "$1")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun parseReminderDateTime(
        dayText: String,
        hourText: String,
        minuteText: String,
        meridiem: String,
        nowMillis: Long
    ): Long? {
        val enteredHour = hourText.toIntOrNull() ?: return null
        val minute = minuteText.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        if (minute !in 0..59) return null

        val hour = when (meridiem.lowercase(Locale.ROOT)) {
            "am" -> if (enteredHour == 12) 0 else enteredHour.takeIf { it in 1..11 } ?: return null
            "pm" -> if (enteredHour == 12) 12 else enteredHour.takeIf { it in 1..11 }?.plus(12) ?: return null
            else -> enteredHour.takeIf { it in 0..23 } ?: return null
        }

        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        when {
            dayText.equals("tomorrow", ignoreCase = true) -> calendar.add(Calendar.DAY_OF_YEAR, 1)
            dayText.equals("today", ignoreCase = true) -> Unit
            else -> {
                val weekday = dayText.substringAfterLast(' ').uppercase(Locale.ROOT)
                val targetDay = when (weekday) {
                    "MONDAY" -> Calendar.MONDAY
                    "TUESDAY" -> Calendar.TUESDAY
                    "WEDNESDAY" -> Calendar.WEDNESDAY
                    "THURSDAY" -> Calendar.THURSDAY
                    "FRIDAY" -> Calendar.FRIDAY
                    "SATURDAY" -> Calendar.SATURDAY
                    "SUNDAY" -> Calendar.SUNDAY
                    else -> return null
                }
                var daysAhead = (targetDay - calendar.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (daysAhead == 0) daysAhead = 7
                calendar.add(Calendar.DAY_OF_YEAR, daysAhead)
            }
        }

        if (calendar.timeInMillis <= nowMillis && dayText.equals("today", ignoreCase = true)) {
            return null
        }
        return calendar.timeInMillis
    }
}
