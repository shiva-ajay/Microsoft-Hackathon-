package com.fixlens.kb

import android.content.Context
import kotlinx.serialization.json.Json

/** Loads and checks the knowledge base (CLAUDE.md §7). Parsing and validation are pure, so they're unit-tested. */
object KbRepository {

    const val ASSET = "kb/fixlens_kb.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): KnowledgeBase = json.decodeFromString(KnowledgeBase.serializer(), text)

    /** Reads the bundled KB. Call off the main thread. */
    fun load(context: Context): KnowledgeBase = parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })

    /**
     * Everything wrong with [kb] (empty = good): ids unique, severity with its rules, at least one safety line
     * unless it's a technician job, steps numbered 1..n, no repair steps for technician jobs, unique codes per
     * appliance and brand (none that read as ordinary words), spoken lines short enough to say.
     */
    fun problems(kb: KnowledgeBase): List<String> {
        val out = mutableListOf<String>()
        if (kb.version.isBlank()) out += "kb_version is missing"
        kb.entries.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { out += "duplicate id $it" }
        val codes = mutableMapOf<String, String>()
        for (e in kb.entries) {
            val at = "entry ${e.id}"
            if (e.id.isBlank()) out += "an entry has no id"
            if (e.title.isBlank()) out += "$at: no title"
            when (e.severity) {
                Severity.CallTechnician -> if (e.steps.isNotEmpty()) out += "$at: call_technician entries give no repair steps"
                else -> {
                    // The warning can instead sit on the first step as a caution (shown, not a "done" to confirm).
                    if (e.safety.isEmpty() && e.steps.firstOrNull()?.caution.isNullOrBlank()) out += "$at: needs at least one safety line, or a caution on step 1"
                    if (e.steps.isEmpty()) out += "$at: no steps"
                }
            }
            if (words(e.meaning) > MAX_LINE_WORDS) out += "$at: meaning is ${words(e.meaning)} words (max $MAX_LINE_WORDS)"
            e.safety.forEach { if (words(it) > MAX_LINE_WORDS) out += "$at: safety line is ${words(it)} words (max $MAX_LINE_WORDS): $it" }
            e.escalateIf.filter { sign -> Retriever.tokens(sign).none { it.length > 2 } }.forEach { out += "$at: escalate_if \"$it\" has no word to match" }
            e.steps.forEachIndexed { i, s ->
                if (s.n != i + 1) out += "$at: step ${s.n} should be number ${i + 1}"
                if (s.say.isBlank()) out += "$at: step ${s.n} has nothing to say"
                if (words(s.say) > MAX_SAY_WORDS) out += "$at: step ${s.n} is ${words(s.say)} words (max $MAX_SAY_WORDS)"
                if (s.target != null && s.target.isBlank()) out += "$at: step ${s.n} has a blank target"
            }
            if (e.safetyAnim.size > e.safety.size) out += "$at: safety_anim has more items than safety"
            e.safetyAnim.forEachIndexed { i, a -> a?.let { animProblem(it, e.safety[minOf(i, e.safety.lastIndex)]) }?.let { out += "$at: safety ${i + 1} $it" } }
            e.steps.forEach { s -> s.anim?.let { animProblem(it, s.say) }?.let { out += "$at: step ${s.n} $it" } }
            e.remind?.let { r ->
                if (e.severity == Severity.CallTechnician) out += "$at: call_technician entries set no reminder"
                if (r.afterDays !in 1..MAX_REMIND_DAYS) out += "$at: remind after_days must be 1..$MAX_REMIND_DAYS"
                if (r.say.isBlank()) out += "$at: remind has nothing to say"
                if (words(r.say) > MAX_SAY_WORDS) out += "$at: remind is ${words(r.say)} words (max $MAX_SAY_WORDS)"
            }
            if (e.source.isBlank()) out += "$at: no source"
            e.brandCodes.filter { it.brand.isBlank() || it.codes.isEmpty() }.forEach { out += "$at: a brand_codes item needs a brand and codes" }
            for ((brand, forms) in e.codeForms()) for (code in forms.filter { !it.contains(' ') }) {
                val norm = Codes.normalize(code)
                if (norm in WORD_CODES) out += "$at: code $code reads as an ordinary word"
                val key = "${e.appliance}|${brand.lowercase()}|$norm"
                val other = codes.put(key, e.id)
                if (other != null && other != e.id) out += "code $code ($brand) of ${e.id} clashes with $other"
            }
        }
        return out
    }

    private fun words(s: String) = s.split(Regex("""\s+""")).count { it.isNotEmpty() }

    /**
     * A wrong turning direction is worse than none, so a `turn` must say its direction and the step's own words must
     * say the same ("unscrew", "anticlockwise" / "screw it back on", "tighten", "clockwise").
     */
    private fun animProblem(a: StepAnim, say: String): String? {
        val t = say.lowercase()
        return when (a.kind) {
            AnimKind.Turn -> when (a.dir) {
                AnimDir.Ccw -> if (OPEN_WORDS.containsMatchIn(t) && !CLOSE_WORDS.containsMatchIn(t)) null
                    else "turns anticlockwise but its words don't say only unscrew or anticlockwise"
                AnimDir.Cw -> if (CLOSE_WORDS.containsMatchIn(t) && !OPEN_WORDS.containsMatchIn(t)) null
                    else "turns clockwise but its words don't say only tighten, screw on or clockwise"
                else -> "turn needs dir ccw or cw"
            }
            AnimKind.Pull, AnimKind.Push ->
                if (a.dir == AnimDir.Ccw || a.dir == AnimDir.Cw) "${a.kind.name.lowercase()} moves up, down, left or right" else null
            else -> if (a.dir != null) "${a.kind.name.lowercase()} takes no dir" else null
        }
    }

    private val OPEN_WORDS = Regex("""unscrew|anti-?clockwise|counter-?clockwise|loosen""")
    private val CLOSE_WORDS = Regex("""(?<!anti)(?<!anti-)(?<!counter)(?<!counter-)clockwise|tighten|\bscrew (\w+ )*(back )?(on|in)\b""")

    /** CLAUDE.md §7 / kb-collection-plan.md: one action per step, speakable in one breath. */
    const val MAX_SAY_WORDS = 20
    /** A routine check comes round at least once a year. */
    const val MAX_REMIND_DAYS = 365
        /** `meaning` and safety lines are spoken too, a little longer is fine. */
    const val MAX_LINE_WORDS = 25
    /** Normalized codes that are everyday words ("AC" the appliance, "ON"), so they'd match ordinary speech. */
    private val WORD_CODES = setOf("AC", "TV", "0N", "N0", "0K", "IF", "IS", "IT", "AT", "AN", "AS", "BE", "D0", "G0", "UP", "US", "WE", "MY", "BY", "T0", "S0", "0R", "0F", "IN", "A", "I")
}

/** Error-code normalization shared by validation and retrieval: uppercase, no spaces or dashes, letter O ≡ zero. */
object Codes {
    fun normalize(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }.replace('O', '0')
}
