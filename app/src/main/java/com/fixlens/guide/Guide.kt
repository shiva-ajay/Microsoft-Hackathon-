package com.fixlens.guide

import com.fixlens.alerts.Alerts
import com.fixlens.kb.KbEntry
import com.fixlens.kb.Retriever
import com.fixlens.kb.Severity
import com.fixlens.kb.StepAnim

/**
 * A guided repair from one KB entry (CLAUDE.md §5, §7): its safety lines first, each confirmed with "done",
 * then its steps one at a time. Everything Fixy says here is the KB's text, word for word; nothing is generated.
 * Pure state + transitions, so it's unit-tested.
 */
sealed interface GuideState {
    data object Idle : GuideState
    data class Safety(val entry: KbEntry, val index: Int) : GuideState
    data class Step(val entry: KbEntry, val index: Int) : GuideState
    data class Done(val entry: KbEntry) : GuideState
    /** A technician job ([Severity.CallTechnician]) or a danger sign from `escalate_if`: no repair steps. */
    data class Escalate(val entry: KbEntry, val reason: String?) : GuideState
}

/** What to show and say for a state, and what to point at. */
data class Instruction(
    val say: String,
    /** Grounding phrase for the VLM, or null when there's nothing to point at. */
    val target: String?,
    /** A visible sign the step is done, for the auto-check; null if only "done" can advance it. */
    val verify: String?,
    /** "Safety first · 1 of 2", "Step 3 of 6". */
    val progress: String?,
    val caution: String?,
    /** How to do it, as a small animation; from the KB. */
    val anim: StepAnim? = null,
)

enum class Command { Done, Next, Back, Repeat, Stop }

/** What the step card shows for a guide. */
data class GuideView(
    val entryId: String,
    val title: String,
    /** "Safety first · 1 of 2", "Step 3 of 6", "Done", "Technician". */
    val progress: String,
    val caution: String?,
    /** What the user can do next ("Say “done” once it's safe"), or null. */
    val prompt: String?,
    val technician: Boolean,
    /** The step's animation, for the how-to card and the cue on the pointed part. */
    val anim: StepAnim? = null,
    /** The KB appliance id ("car", "air_conditioner"…), e.g. to say which kind of technician to call. */
    val appliance: String? = null,
)

/** The entry a guide state belongs to (null when idle). */
fun GuideState.entry(): KbEntry? = when (this) {
    GuideState.Idle -> null
    is GuideState.Safety -> entry
    is GuideState.Step -> entry
    is GuideState.Done -> entry
    is GuideState.Escalate -> entry
}

object Guide {

    const val SAFETY_FIRST = "Safety first: please do this, then say done."
    const val ALL_DONE = "That was the last step. Nicely done!"
    const val TECHNICIAN = "This one is a job for a technician. Please don't try it yourself."

    fun start(entry: KbEntry): GuideState = when {
        entry.severity == Severity.CallTechnician -> GuideState.Escalate(entry, null)
        entry.safety.isNotEmpty() -> GuideState.Safety(entry, 0)
        entry.steps.isNotEmpty() -> GuideState.Step(entry, 0)
        else -> GuideState.Done(entry)
    }

    /** The next state after [command], and a reminder to say when the command isn't allowed (skipping safety). */
    fun onCommand(state: GuideState, command: Command): Pair<GuideState, String?> = when (command) {
        Command.Stop -> GuideState.Idle to null
        Command.Repeat -> state to null
        Command.Done -> advance(state) to null
        // Safety lines must be confirmed with "done"; "next" / "skip" doesn't unlock the repair steps.
        Command.Next -> if (state is GuideState.Safety) state to SAFETY_FIRST else advance(state) to null
        Command.Back -> back(state) to null
    }

    private fun advance(state: GuideState): GuideState = when (state) {
        is GuideState.Safety ->
            if (state.index + 1 < state.entry.safety.size) state.copy(index = state.index + 1)
            else if (state.entry.steps.isNotEmpty()) GuideState.Step(state.entry, 0)
            else GuideState.Done(state.entry)
        is GuideState.Step ->
            if (state.index + 1 < state.entry.steps.size) state.copy(index = state.index + 1)
            else GuideState.Done(state.entry)
        else -> state
    }

    private fun back(state: GuideState): GuideState = when (state) {
        is GuideState.Safety -> state.copy(index = maxOf(0, state.index - 1))
        is GuideState.Step ->
            if (state.index > 0) state.copy(index = state.index - 1)
            else if (state.entry.safety.isNotEmpty()) GuideState.Safety(state.entry, state.entry.safety.lastIndex)
            else state
        is GuideState.Done -> if (state.entry.steps.isNotEmpty()) GuideState.Step(state.entry, state.entry.steps.lastIndex) else state
        else -> state
    }

    fun view(state: GuideState): GuideView? {
        val entry = state.entry() ?: return null
        val ins = instruction(state) ?: return null
        val prompt = when (state) {
            is GuideState.Safety -> "Say “done” once it's safe"
            is GuideState.Step -> if (ins.verify != null) "Say “done”, or I'll see when it's done" else "Say “done” for the next step"
            else -> null
        }
        return GuideView(entry.id, entry.title, ins.progress.orEmpty(), ins.caution, prompt, state is GuideState.Escalate, ins.anim, entry.appliance)
    }

    fun instruction(state: GuideState): Instruction? = when (state) {
        GuideState.Idle -> null
        is GuideState.Safety -> {
            // The entry's meaning introduces the first safety line.
            val intro = if (state.index == 0) state.entry.meaning.trim().takeIf { it.isNotEmpty() } else null
            Instruction(
                say = listOfNotNull(intro, state.entry.safety[state.index]).joinToString(" "),
                target = null, verify = null,
                progress = "Safety first · ${state.index + 1} of ${state.entry.safety.size}",
                caution = null,
                anim = state.entry.safetyAnim.getOrNull(state.index),
            )
        }
        is GuideState.Step -> state.entry.steps[state.index].let { s ->
            // With no safety lines to open the guide, the entry's meaning introduces its first step.
            val intro = if (state.index == 0 && state.entry.safety.isEmpty()) state.entry.meaning.trim().takeIf { it.isNotEmpty() } else null
            Instruction(listOfNotNull(intro, s.say).joinToString(" "), s.target, s.verify, "Step ${state.index + 1} of ${state.entry.steps.size}", s.caution, s.anim)
        }
        // A routine check (the KB's `remind`) says when it comes round again; the app schedules that reminder.
        is GuideState.Done -> Instruction(
            listOfNotNull(ALL_DONE, state.entry.remind?.let { reminderLine(it.afterDays) }).joinToString(" "),
            null, null, "Done", null,
        )
        is GuideState.Escalate -> Instruction(
            // A danger sign names itself; a technician-only entry explains why with its meaning.
            say = listOfNotNull(
                state.reason?.let { "That's a warning sign: $it." } ?: state.entry.meaning.trim().takeIf { it.isNotEmpty() },
                TECHNICIAN,
            ).joinToString(" "),
            target = null, verify = null, progress = "Technician", caution = state.reason,
        )
    }

    /** Fixed wording; the interval comes from the KB entry. */
    fun reminderLine(days: Int): String = "I'll remind you to check it again in ${Alerts.spokenInterval(days)}."

    /** The `escalate_if` sign the user just described ("there's a burning smell"), if any. */
    fun escalation(entry: KbEntry, text: String): String? {
        val said = Retriever.tokens(text).map(Retriever::stem).toSet()
        return entry.escalateIf.firstOrNull { sign ->
            val words = Retriever.tokens(sign).map(Retriever::stem).filter { it.length > 2 }
            words.isNotEmpty() && words.count { it in said }.toDouble() / words.size >= ESCALATE_COVERAGE
        }
    }

    private const val ESCALATE_COVERAGE = 0.75
}

/** Plain string match of short utterances to guide commands (CLAUDE.md §11); anything else is a question. */
object Commands {
    private val PHRASES = listOf(
        Command.Done to listOf("done", "i m done", "im done", "i am done", "finished", "did it", "it s done", "ok done", "okay done", "all done"),
        Command.Next to listOf("next", "next step", "go on", "continue", "skip"),
        Command.Back to listOf("back", "go back", "previous", "previous step", "last step"),
        Command.Repeat to listOf("repeat", "again", "say again", "say that again", "pardon", "come again"),
        Command.Stop to listOf("stop", "cancel", "quit", "exit", "stop guide"),
    )
    private const val MAX_WORDS = 5
    /** "t" is what's left of n't ("can't" → "can", "t"). */
    private val NEGATIONS = setOf("not", "no", "t", "dont", "didnt", "isnt", "havent", "cant")

    fun parse(text: String): Command? {
        val words = text.lowercase().split(Regex("""[^a-z]+""")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > MAX_WORDS) return null
        // "I'm not done yet", "don't stop": a sentence, not a command.
        if (words.any { it in NEGATIONS }) return null
        val line = " ${words.joinToString(" ")} "
        return PHRASES.firstOrNull { (_, phrases) -> phrases.any { line.contains(" $it ") } }?.first
    }
}
