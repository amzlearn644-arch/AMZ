package com.amz.pace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyPlanTest {
    private fun lesson(name: String, start: String, end: String) = Lesson(name=name, topic="فصل اول", start=start, end=end)

    @Test fun detectsBreakBetweenLessons() {
        val gaps = studyBreaks(listOf(lesson("ریاضی","09:00","10:00"), lesson("فیزیک","10:20","11:00")))
        assertEquals(1, gaps.size)
        assertEquals(20, gaps.single().minutes)
        assertEquals("10:00", gaps.single().start)
        assertEquals("10:20", gaps.single().end)
    }

    @Test fun doesNotCreateBreakForAdjacentLessons() {
        assertTrue(studyBreaks(listOf(lesson("ریاضی","09:00","10:00"), lesson("فیزیک","10:00","11:00"))).isEmpty())
    }

    @Test fun rejectsOverlappingLessons() {
        assertFalse(isValidPlan(120, listOf(lesson("ریاضی","09:00","10:30"), lesson("فیزیک","10:00","11:00"))))
    }

    @Test fun rejectsEmptyOrInvalidPlan() {
        assertFalse(isValidPlan(0, listOf(lesson("ریاضی","09:00","10:00"))))
        assertFalse(isValidPlan(120, emptyList()))
        assertFalse(isValidPlan(120, listOf(lesson("ریاضی","10:00","09:00"))))
    }

    @Test fun acceptsValidPlan() {
        assertTrue(isValidPlan(120, listOf(lesson("ریاضی","09:00","10:00"), lesson("فیزیک","10:15","11:15"))))
    }
    @Test fun convertsNowruzAndLeapDayBoundaries() {
        assertEquals(JalaliParts(1403, 1, 1), jalaliParts(java.time.LocalDate.of(2024, 3, 20)))
        assertEquals(JalaliParts(1403, 12, 30), jalaliParts(java.time.LocalDate.of(2025, 3, 20)))
        assertEquals(JalaliParts(1404, 1, 1), jalaliParts(java.time.LocalDate.of(2025, 3, 21)))
    }

    @Test fun jalaliCalendarRoundTripsRepresentativeDates() {
        val dates = listOf(
            java.time.LocalDate.of(2024, 3, 20),
            java.time.LocalDate.of(2024, 8, 12),
            java.time.LocalDate.of(2025, 3, 20),
            java.time.LocalDate.of(2026, 11, 24)
        )
        dates.forEach { date ->
            val j = jalaliParts(date)
            assertEquals(date, jalaliToGregorian(j.year, j.month, j.day))
        }
    }

    @Test fun jalaliMonthLengthsAreValid() {
        assertEquals(31, jalaliMonthLength(1403, 1))
        assertEquals(30, jalaliMonthLength(1403, 7))
        assertEquals(30, jalaliMonthLength(1403, 12))
        assertEquals(29, jalaliMonthLength(1404, 12))
    }

    @Test fun speaksPersianTimeNaturally() {
        assertEquals("ساعت یازده و چهل و پنج دقیقه", speakTime("11:45"))
        assertEquals("ساعت صفر و پنج دقیقه", speakTime("00:05"))
    }

}