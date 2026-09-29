package com.fixlens.guide

import com.fixlens.kb.AnimDir
import com.fixlens.kb.AnimKind
import com.fixlens.kb.KbEntry
import com.fixlens.kb.KbStep
import com.fixlens.kb.StepAnim
import com.fixlens.kb.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideTest {

    private val oil = KbEntry(
        id = "oil", appliance = "car_engine_bay", title = "Check oil", meaning = "Let's check the oil.",
        severity = Severity.Diy, escalateIf = listOf("burning smell", "oil light stays on while driving"),
        safety = listOf("Engine off.", "Let it cool."),
        steps = listOf(
            KbStep(1, "Find the dipstick.", target = "yellow ring handle of the engine oil dipstick"),
            KbStep(2, "Open the filler cap.", target = "oil filler cap", verify = "the cap is off"),
        ),
        source = "test",
    )
    private val wiring = KbEntry(
        id = "wiring", appliance = "car_engine_bay", title = "Wiring", meaning = "Wiring can hurt you.",
        severity = Severity.CallTechnician, source = "test",
    )

    @Test fun `safety lines come first and must be confirmed with done`() {
        var s = Guide.start(oil)
        assertEquals(GuideState.Safety(oil, 0), s)
        assertEquals("Let's check the oil. Engine off.", Guide.instruction(s)!!.say)
        val (afterNext, reminder) = Guide.onCommand(s, Command.Next)
        assertEquals(s, afterNext) // "next" can't skip safety
        assertEquals(Guide.SAFETY_FIRST, reminder)
        s = Guide.onCommand(s, Command.Done).first
        assertEquals(GuideState.Safety(oil, 1), s)
        assertEquals("Let it cool.", Guide.instruction(s)!!.say)
        s = Guide.onCommand(s, Command.Done).first
        assertEquals(GuideState.Step(oil, 0), s)
    }

    @Test fun `steps carry their target, verify and progress verbatim`() {
        val i = Guide.instruction(GuideState.Step(oil, 1))!!
        assertEquals("Open the filler cap.", i.say)
        assertEquals("oil filler cap", i.target)
        assertEquals("the cap is off", i.verify)
        assertEquals("Step 2 of 2", i.progress)
    }

    @Test fun `animations come with their safety line or step, and technician jobs show the technician`() {
        val unplug = StepAnim(AnimKind.Unplug)
        val turn = StepAnim(AnimKind.Turn, AnimDir.Ccw)
        val e = oil.copy(safetyAnim = listOf(unplug), steps = listOf(KbStep(1, "Unscrew the cap.", anim = turn)))
        assertEquals(unplug, Guide.view(GuideState.Safety(e, 0))!!.anim)
        assertNull(Guide.view(GuideState.Safety(e, 1))!!.anim)
        assertEquals(turn, Guide.view(GuideState.Step(e, 0))!!.anim)
        val tech = Guide.view(Guide.start(wiring))!!
        assertEquals(true, tech.technician)
        assertNull(tech.anim)
    }

    @Test fun `with no safety lines the guide starts at step 1, introduced by the meaning`() {
        val quick = oil.copy(safety = emptyList(), steps = listOf(KbStep(1, "Find the dipstick.", caution = "Engine off first.")))
        val s = Guide.start(quick)
        assertEquals(GuideState.Step(quick, 0), s)
        assertEquals("Let's check the oil. Find the dipstick.", Guide.instruction(s)!!.say)
        assertEquals("Engine off first.", Guide.instruction(s)!!.caution)
    }

    @Test fun `next, back, repeat and done at the end`() {
        var s: GuideState = GuideState.Step(oil, 0)
        s = Guide.onCommand(s, Command.Next).first
        assertEquals(GuideState.Step(oil, 1), s)
        assertEquals(s, Guide.onCommand(s, Command.Repeat).first)
        assertEquals(GuideState.Step(oil, 0), Guide.onCommand(s, Command.Back).first)
        assertEquals(GuideState.Safety(oil, 1), Guide.onCommand(GuideState.Step(oil, 0), Command.Back).first)
        s = Guide.onCommand(s, Command.Done).first
        assertEquals(GuideState.Done(oil), s)
        assertEquals(Guide.ALL_DONE, Guide.instruction(s)!!.say)
        assertEquals(GuideState.Idle, Guide.onCommand(s, Command.Stop).first)
    }

    @Test fun `technician jobs escalate with no steps`() {
        val s = Guide.start(wiring)
        assertEquals(GuideState.Escalate(wiring, null), s)
        val i = Guide.instruction(s)!!
        assertEquals("Wiring can hurt you. ${Guide.TECHNICIAN}", i.say)
        assertNull(i.target)
    }

    @Test fun `a danger sign escalates naming the sign`() {
        val i = Guide.instruction(GuideState.Escalate(oil, "burning smell"))!!
        assertEquals("That's a warning sign: burning smell. ${Guide.TECHNICIAN}", i.say)
    }

    @Test fun `escalate_if signs are recognised in what the user says`() {
        assertEquals("burning smell", Guide.escalation(oil, "there's a burning smell now"))
        assertEquals("oil light stays on while driving", Guide.escalation(oil, "the oil light stays on when I'm driving"))
        assertNull(Guide.escalation(oil, "done"))
    }

    @Test fun `commands are short utterances only`() {
        assertEquals(Command.Done, Commands.parse("Done."))
        assertEquals(Command.Done, Commands.parse("OK, I'm done"))
        assertEquals(Command.Next, Commands.parse("Next step please"))
        assertEquals(Command.Back, Commands.parse("go back"))
        assertEquals(Command.Repeat, Commands.parse("Can you say that again?"))
        assertEquals(Command.Stop, Commands.parse("stop"))
        assertNull(Commands.parse("I'm not done yet"))
        assertNull(Commands.parse("how do I know when the oil level is done being checked"))
        assertNull(Commands.parse("where is the dipstick"))
    }
}
