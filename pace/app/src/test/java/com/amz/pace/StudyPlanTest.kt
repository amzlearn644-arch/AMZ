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
}