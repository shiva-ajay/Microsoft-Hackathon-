# FixLens: Round 2 script — "self-diagnosis for your things"

This is the full Round 2 pitch, built around your idea: we self-treat a minor fever and only see
a doctor for something serious. FixLens does the same for appliances — small problems, fix it
yourself; serious ones, it tells you straight to call a technician.

For the tough-question answers (how often, Gemini, speed, remote readiness), see
[ROUND2_PITCH.md](ROUND2_PITCH.md). This file is the pitch itself.

**Total time:** about 6.5 minutes at a relaxed pace. A 3-minute cut is at the end.

**Before you rehearse this, check today's real status** against
[CLAUDE.md §15](../../CLAUDE.md#15-current-status-update-this-as-you-go): M0–M2 (model, box on
screen, tracker) are done. M3 (voice) and the IR remote are built but not all confirmed by hand on
the phone yet. Every "not yet confirmed" spot below is marked — read those before you go in and
say "it can" instead of "it does" for anything you haven't personally tried today.

---

## The shape of the pitch (read this once before rehearsing)

| # | Section | Time | New idea introduced |
|---|---|---|---|
| 1 | Opener — the self-diagnosis hook | ~20 s | the whole idea, in one breath |
| 2 | The problem | ~40 s | — |
| 3 | Meet Fixy | ~70 s | the five things it does |
| 4 | How it decides: fix it yourself, or call someone | ~55 s | **the alert / escalation** |
| 5 | More than a repair app | ~45 s | **the remote, and Fixy's memory** |
| 6 | How it works under the hood | ~80 s | architecture (two speeds) |
| 7 | Trust and privacy | ~35 s | — |
| 8 | Demo, roadmap, close | ~40 s | callback to the opener |

The **alert** goes in section 4, right when you explain the decision logic — it's the "go to the
doctor" half of the analogy. The **remote and memory** go in section 5, as the answer to "but I'm
not broken every day" — it's what makes the app useful even when nothing needs fixing.

---

## 1. Opener — the hook (about 20 seconds)

**Say:**
> For a mild fever, you don't run to a doctor. You self-check, maybe take a paracetamol, and
> you're fine. Only a serious problem sends you to one.
>
> FixLens brings that same idea to your appliances and your car. Small problems, you fix
> yourself, guided step by step. Serious ones, it tells you straight: call a technician.
>
> That's FixLens. Point, ask, fix.

**Tip:** Say "self-check" and "serious problem" a little slower — those two words are doing all
the work. Practice with a timer; this should land at 18 to 22 seconds, not less.

---

## 2. The problem (about 40 seconds)

**Say:**
> Here's what actually happens today. Your washing machine flashes "E4". Your bike makes a new
> noise. A warning light comes on in the car. What do most of us do? We call a technician, wait,
> and pay — for something that might take five minutes ourselves. It's like booking a doctor's
> appointment for a paper cut.
>
> The manual is long gone. Helplines mean queues. And it usually happens exactly where the
> network is worst — basements, parking lots, terraces. Most of these problems are small.
> People just don't know where to look.

**Tip:** Don't repeat the full doctor analogy again here — just the "paper cut" line. You already
made the point in the opener; this section's job is to make the pain feel real with specifics.

---

## 3. Meet Fixy (about 70 seconds)

**Say:**
> So think of FixLens as a self-diagnosis app — but for your things, not for you.
>
> You point your phone at the appliance or the engine, and ask what's wrong, out loud. Fixy —
> that's the assistant inside — does five things.
>
> It **sees**, through the camera. It **listens**, so your hands stay free. It **points** — it
> puts a marker on the exact part, and the marker stays locked on as you move the phone around.
> It **talks you through** the fix, one step at a time, and you say "done" to move on.
>
> And the fifth thing is the important one, and it's what section four is about: it **knows when
> to stop**.
>
> All of this runs completely on the phone. No internet.

**Tip:** End on "and it's what section four is about" as a natural bridge — don't explain the
fifth ability yet, just tease it. That keeps the audience listening for the payoff.

---

## 4. How it decides: fix it yourself, or call someone — **the alert** (about 55 seconds)

**This is where the alert / escalation feature goes.** It's the technical answer to "how does the
self-diagnosis analogy actually work in the app?"

**Say:**
> Here's how FixLens makes that call. Every entry in its knowledge base is tagged with a level:
> do-it-yourself, be careful, or call a technician.
>
> If it's a do-it-yourself fix, Fixy starts with safety, like "unplug it" or "let the engine
> cool", before any repair step. You confirm each one before moving on.
>
> But if the situation matches something risky, Fixy doesn't attempt it. It gives you a clear
> alert: "I don't have verified guidance for that, please contact a technician." No guessing, no
> improvising.
>
> A live example: ask it something about car wiring, and it refuses on purpose, and points you
> to a mechanic instead. That's a deliberate design choice, not a gap.
>
> It's the exact same judgment we use for our own health. Small things, we handle. Serious
> things, we call a professional. FixLens makes that same call for your appliances.

**Tip:** The wiring-refusal line is your strongest proof moment — if your demo includes asking
about wiring, point at the screen right when you say "it refuses on purpose". A visible refusal
is more convincing than any slide.

---

## 5. More than a repair app — **the remote, and Fixy's memory** (about 45 seconds)

**This is where the remote feature goes.** It answers "how often would I actually use this?" —
self-care isn't only for when something's broken, it's also a daily habit.

**Say:**
> And self-care isn't only for when you're sick — it's also a daily habit, like checking in on
> yourself. FixLens has that layer too.
>
> Fixy can double as a remote for your AC or TV, using the phone's own IR blaster. You just say
> "set it to 24" or "volume up", and it happens — no app switching, no hunting for the remote.
>
> And it remembers your last repair. So next time you open FixLens, it might ask, "Last time we
> looked at your washing machine, is it working fine now?"
>
> Small things like that mean this isn't an app you open once when something breaks and forget.
> It becomes a small daily habit.

**Check before you say this out loud:** the remote has sent real IR codes on the phone (TV and
AC), but hasn't been tried as a full end-to-end "say a command, watch it happen" flow yet, and the
memory check-in hasn't been tried live either. If you've tried either by the time of Round 2, say
"it does" and demo it in 10 seconds. If not, soften the tense: "Fixy is also built to work as a
remote for your AC or TV" instead of "Fixy can". Never demo a feature you haven't tried today.

---

## 6. How it works under the hood (about 80 seconds)

**On screen:** the same "two speeds" architecture diagram from Round 1, if you're reusing the
deck. If the same judges are in the room, you can open with "as before" and move a bit faster.

**Say:**
> Quickly, how does the pointing actually work — because this is the hardest technical problem
> in the app.
>
> The AI model that understands the camera image is smart, but slow — one to three seconds. But
> the camera runs at thirty frames a second. If the marker only updated every few seconds, it
> would jump around and feel broken.
>
> So FixLens runs at two speeds. A slow brain: the vision model, Qwen3-VL, running fully on the
> phone, answers once per question with a box around the part and a couple of spoken sentences.
> And fast eyes: a tracker using OpenCV optical flow, following that box in every single frame,
> thirty times a second.
>
> One more detail: by the time the brain answers, the phone has already moved. So the app keeps
> the last three seconds of frames, and the tracker catches up from the old frame to the live
> view. That's why the marker lands in the right place even though the answer was a little late.
>
> The brain only runs when you ask something — never on every frame. That keeps the phone fast
> and cool.

**Tip:** If you're short on time, cut the "keeps the last three seconds of frames" paragraph
first — it's the most impressive detail but also the most skippable under time pressure.

---

## 7. Trust and privacy (about 35 seconds)

**Say:**
> All of this happens with no internet at all — the app doesn't even have the permission to go
> online. Your camera and your voice never leave the phone.
>
> It's the same reason you trust a family doctor: what you tell them stays with them. FixLens
> works the same way, for your appliances.

---

## 8. Demo, roadmap, close (about 40 seconds)

**Say:**
> For today's demo: [say what you're actually showing — the car engine bay, the washing-machine
> panel, or both]. Watch what happens when I ask it something risky — you'll see the alert,
> instead of a guess.
>
> After this, I want to cover more appliances, add more languages, and reach people who don't
> even have a manual to lose in the first place.
>
> We already trust a doctor with the small decisions and the big ones about our health. FixLens
> does the same for your things.
>
> Point, ask, fix. Thank you.

**Tip:** End on "Thank you" and stop talking. Look at the jury, not the screen.

---

## 2-minute version

Use this for a short, standalone pitch — a single tight block, said as one continuous piece
rather than paced slide by slide.

**Say (~2 minutes, about 300 words):**
> For a mild fever, you don't run to a doctor — you self-check and you're fine. Only something
> serious sends you to one. FixLens brings that idea to your appliances and your car. Small
> problems, fix it yourself. Serious ones, it tells you straight: call a technician. Point, ask,
> fix.
>
> Here's why that matters. Your washing machine flashes "E4". Your bike makes a new noise. Most
> of us call a technician and pay for a five-minute fix, because the manual's gone, and it
> always happens where the signal is worst.
>
> So here's how FixLens works. Point your phone, ask out loud. Fixy sees through the camera,
> listens to you, and points a marker at the exact part — it stays locked on as you move. It
> talks you through the fix, one step at a time. And here's the key part: every fix is tagged
> do-it-yourself, be careful, or call a technician. Ask about something risky, like car wiring,
> and Fixy refuses on purpose and sends you to a mechanic instead — the same judgment we use for
> our own health.
>
> And it's not just for emergencies. Fixy can also work as a remote for your AC or TV through
> the phone's IR blaster — just say "set it to 24". And it remembers your last repair, and
> checks back on you. So it becomes a small daily habit, not a once-and-forget app.
>
> Under the hood, it runs at two speeds: a vision model that answers once per question, and a
> fast tracker that keeps the marker locked at thirty frames a second — all fully on the phone,
> no internet.
>
> We trust a doctor with the small decisions and the big ones about our health. FixLens does the
> same for your things. Point, ask, fix. Thank you.

**Tip:** Time yourself — this should land between 1:50 and 2:10. If you're running long, the
architecture paragraph ("Under the hood…") is the easiest one to cut; the alert and the daily-use
lines are the two that answer the tough questions, so keep those.

**Check before you say the daily-use paragraph:** same caveat as section 5 above — say "is also
built to work as a remote" instead of "can" if you haven't tried the remote or the memory
check-in live yet.

---

## Short version (3 minutes)

Use this if Round 2 has a tight time limit.

1. **Opener (20 s):** "For a mild fever, you self-check. Only something serious sends you to a
   doctor. FixLens does that for your appliances — small problems, fix it yourself; serious
   ones, it tells you straight to call a technician. Point, ask, fix."
2. **Problem (25 s):** "Your washer flashes 'E4', your bike makes a noise — and we call a
   technician for something that might take five minutes ourselves. The manual's gone, and it
   always happens where the signal is worst."
3. **Fixy (30 s):** "Point your phone, ask out loud. Fixy sees, listens, points at the exact part
   with a marker that stays locked on, and talks you through it step by step."
4. **The alert (30 s):** "Every fix is tagged do-it-yourself, be careful, or call a technician. Ask
   it about something risky, like car wiring, and it refuses on purpose and sends you to a
   mechanic. Same judgment we use for our own health."
5. **Daily use (25 s):** "It's not only for emergencies — Fixy can also work as a remote for your
   AC or TV, and it remembers your last repair and checks back on you."
6. **How it works (30 s):** "The AI model is smart but slow, one to three seconds. The camera is
   thirty frames a second. So there's a slow brain that answers once, and fast eyes — a tracker —
   that keep the marker locked at thirty frames a second."
7. **Close (20 s):** "No internet, ever. We trust a doctor with our health. FixLens does the same
   for your things. Point, ask, fix. Thank you."

---

## Before Round 2

- [ ] Confirm today's real status for M3 (voice) and the IR remote against
      [CLAUDE.md §15](../../CLAUDE.md#15-current-status-update-this-as-you-go) — say "it can", not
      "it does", for anything not tried by hand yet.
- [ ] Say the opener out loud with a timer, five times, before you go in.
- [ ] Decide what you're actually demoing (the bracket in section 8) and fill it in — don't leave
      it as a placeholder on the day.
- [ ] Keep [ROUND2_PITCH.md](ROUND2_PITCH.md) open on your phone for the Q&A — it has the "how
      often" and "what about Gemini" soundbites this script doesn't repeat.
