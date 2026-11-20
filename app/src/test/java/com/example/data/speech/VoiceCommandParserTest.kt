package com.example.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class VoiceCommandParserTest {
    @Test
    fun parsesTomorrowReminderAtRequestedTime() {
        withUtcTimeZone {
            val now = calendar(2026, Calendar.OCTOBER, 29, 21, 0).timeInMillis
            val command = VoiceCommandParser.findCommands("remind me tomorrow at 9", now)
                .filterIsInstance<VoiceCommand.Reminder>()
                .single()

            val expected = calendar(2026, Calendar.OCTOBER, 30, 9, 0).timeInMillis
            assertEquals(expected, command.triggerAtMillis)
        }
    }

    @Test
    fun parsesReminderWithDescriptionAndMeridiem() {
        withUtcTimeZone {
            val now = calendar(2026, Calendar.OCTOBER, 29, 12, 0).timeInMillis
            val command = VoiceCommandParser.findCommands(
                "remind me to call the team tomorrow at 9:30 pm",
                now
            ).filterIsInstance<VoiceCommand.Reminder>().single()

            val expected = calendar(2026, Calendar.OCTOBER, 30, 21, 30).timeInMillis
            assertEquals(expected, command.triggerAtMillis)
        }
    }

    @Test
    fun parsesCategoryAndRemovesSpokenCommandsFromTranscript() {
        val commands = VoiceCommandParser.findCommands(
            "Discuss the launch. Tag this as Ideas. Remind me tomorrow at 9."
        )

        assertEquals("Ideas", (commands.filterIsInstance<VoiceCommand.Tag>().single()).category)
        assertEquals(1, commands.filterIsInstance<VoiceCommand.Reminder>().size)
        assertEquals(
            "Discuss the launch.",
            VoiceCommandParser.removeCommandPhrases(
                "Discuss the launch. Tag this as Ideas. Remind me tomorrow at 9.",
                commands.map { it.phrase }
            )
        )
    }

    @Test
    fun rejectsInvalidTimes() {
        val commands = VoiceCommandParser.findCommands("remind me tomorrow at 29:70")
        assertTrue(commands.none { it is VoiceCommand.Reminder })
    }

    private fun calendar(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month, day, hour, minute)
        }

    private fun withUtcTimeZone(block: () -> Unit) {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
