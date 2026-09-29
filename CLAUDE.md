# FixLens — Project Context for Claude Code

> Read this file fully before writing any code. The detailed tech stack, versions,
> model files and performance budgets live in `.claude/STACK.md`, imported below.

@.claude/STACK.md

---

## 1. TL;DR

**FixLens** is a fully offline Android self-repair assistant. You point the phone camera at a
broken appliance or a car's engine bay and ask a question out loud. The on-device assistant,
**Fixy**, answers by voice and on-screen text, and puts a marker on the exact part to touch.
The marker stays locked on that part as the phone moves.

Everything runs on the phone (iQOO 15). No internet, no cloud, no data leaves the device.

We are building this **solo** for the **iQOO City Battles on-device AI hackathon, Hyderabad
edition (26–27 Sept 2026)**. Build time is about 15 hours of remaining work.

---

## 2. The idea

### The problem
When an appliance breaks, it usually only shows a cryptic error code ("OE", "E4") or a blinking
light. The manual is lost, helplines mean queues, and a technician visit costs money and days.
Often the fix is simple (clogged filter, loose cover, low fluid), but the owner doesn't know
where to look. Breakdowns also happen where connectivity is worst: utility rooms, basements,
terraces, parking lots.

### The product
FixLens is a patient repair friend in your pocket that:
1. **Sees** what you see (camera) and reads error codes on displays.
2. **Listens** to your spoken question (hands-free).
3. **Points** at the exact part with an AR-style marker that tracks as you move.
4. **Talks** you through verified steps one at a time; you say "done" to advance.
5. **Refuses** when it doesn't know, and escalates dangerous jobs to a technician.

### Core principles (never violate)
- **Offline by design.** It must work in airplane mode. This is the product, not a feature.
- **Private.** Camera and audio never leave the device. The app has no INTERNET permission.
- **Trustworthy.** Repair steps come ONLY from the curated knowledge base (KB). The model never
  invents steps, parts, values or codes. Unknown → "I don't have verified guidance for that,
  please contact a technician."
- **Safety first.** Every guided repair starts with its safety steps (unplug, engine off and
  cool). Technician-only faults skip guidance entirely.

### Users
Households with an error code and no manual; building-maintenance staff; (future) junior field
technicians who can't upload video from customers' homes.

---

## 3. Demo scope (keep it narrow)

Language: **English only**. Two demo targets:

| Target | Why | Example entries |
|---|---|---|
| **Car engine bay** (a real car in the venue parking lot) | Most impressive live demo | Check engine oil, coolant level, washer fluid, battery terminals, air-filter box |
| **Front-load washing machine** (an A3 printout of a control panel at the judges' table) | Reliable table demo; the tracker works well on a flat printout | 5–8 common error codes for one brand (drain, door lock, water inlet) |

Engine-bay guidance is limited to user-serviceable checks. **No wiring guidance.** Asking about
wiring must produce a refusal and a mechanic recommendation; this is a deliberate demo moment.

The two targets above are what we demo live. The **KB itself is generic** (user decision, 2026-09-27): common
household appliances and vehicles, not one brand or car model: washing machine, AC, fridge, TV, laptop, geyser, water
purifier, microwave, inverter, car, bike/scooter. See §7.

---

## 4. The hackathon (constraints that shape decisions)

- **Event:** iQOO City Battles, a phone-first on-device AI hackathon by iQOO with Reskilll.
  Hyderabad is the last city battle. The top 6 teams go to the Grand Finale in Bengaluru (9–11 Oct 2026).
- **Team:** solo builder. Phone: iQOO 15 (Snapdragon 8 Elite Gen 5, 16 GB RAM).
  Laptop: Ubuntu, 16 GB RAM, RTX 4050 6 GB.
- **Build phases:** "Red Light" = phone-only time; "Green Light" = phone + laptop.
  Laptop-heavy work (Android Studio builds) happens in Green Light. Red Light is used for
  on-phone model testing, KB writing, photos, demo recording and pitch.
- **Judging rubric:**

  | Criterion | Weight | Scored by |
  |---|---|---|
  | End product quality | 30% | Jury |
  | Novelty & impact | 20% | Jury |
  | Creative phone use (camera, voice, on-device AI) | 15% | HackTracker device data |
  | Technical depth | 15% | Jury |
  | Office Kit usage | 10% | HackTracker device data |
  | Demo & presentation | 10% | Jury |

  Local or open-source models at the core earn bonus credit.
- **Office Kit** has no Linux client; we use Chrome Remote Desktop and scrcpy instead.
  Not a code concern.
- **Positioning:** past winners were all "offline + on-device". That is expected now, not novel.
  Our novelty is **live voice conversation + the VLM pointing at the exact part + the marker
  tracking it in real time.** Features that strengthen this loop come first.

---

## 5. Architecture: two speeds

A VLM cannot run 30 times per second. So FixLens is a **slow brain** (VLM, runs once per
question, 1–3 s) plus **fast eyes** (a local tracker, runs every frame at 30 fps).

```
 Mic (push-to-talk) ─► VAD + STT (sherpa-onnx: Silero + Moonshine) ──┐
                                                                     ▼
 Knowledge base (JSON) ─────────────────────────────►  ORCHESTRATOR
                                                     (guide state machine + Fixy prompt)
 Camera (CameraX 30 fps) ──► Frame ring buffer (~3 s) ──┬─ keyframe ─┘
                                                        │            │
                                                        │            ▼
                                                        │   Grounding VLM (Qwen3-VL-4B, MNN)
                                                        │      │ box line        │ text stream
                                                        │      ▼                 ▼
                                                        └─► TRACKER          Captions + TTS
                                                          (OpenCV LK flow)  (sentence by sentence)
                                                              │
                                                              ▼
                                                       Marker overlay (Compose Canvas)
```

### Flow of one question
1. User taps Talk and speaks. The VAD finds the end of speech, Moonshine transcribes it, and
   the caption is shown. The orchestrator saves the **current frame as the keyframe** with its
   timestamp.
2. The orchestrator resolves the KB entry (see §7) and builds the prompt: Fixy system prompt +
   KB entry + current step + question + keyframe.
3. The VLM streams output: **box line first** (JSON), then 1–2 spoken sentences.
4. `GroundingParser` splits the stream. The box goes to the tracker immediately; the text goes to
   captions (token by token) and to TTS (one sentence at a time).
5. The tracker initializes on the keyframe, **fast-forwards through buffered frames to "now"**
   (latency compensation), then tracks live every frame.
6. If tracking confidence drops for more than ~1 s, the orchestrator silently re-asks the VLM
   with a fresh frame ("where is <target> now?") and re-seeds the tracker.
7. `GuideStateMachine` advances steps on "done"/"next", repeats on "repeat", and handles barge-in
   (user speech stops TTS).

### Guide state machine
`Idle → Greeting → Scanning → Confirming → Explaining → Step(n) → Done`,
plus `Escalate` (technician-only or `escalate_if` matched) and `Unknown` (no KB match → refusal).
Safety steps must be confirmed with "done" before repair steps unlock.

---

## 6. Package responsibilities

```
com.fixlens
├── app/        MainActivity, FixLensApp: load models once at startup behind a splash screen
├── camera/     CameraController (Preview + ImageAnalysis), FrameRingBuffer (timestamped, ~3 s)
├── vision/     VlmEngine (wraps the forked MNN LLM JNI), GroundingParser, BoxMapper
├── tracking/   FlowTracker (OpenCV LK + homography, fast-forward, confidence, re-ground trigger)
├── voice/      SpeechInput (AudioRecord → VAD → Moonshine), SpeechOutput (TTS sentence queue)
├── kb/         KbModels, KbRepository (load + validate JSON), Retriever (4-stage lookup)
├── guide/      Orchestrator, GuideStateMachine, FixyPrompts
├── alerts/     Alerts (pure rules), AlertStore, AlertScheduler + AlertReceiver (alarm, notification, reboot)
└── ui/         CameraScreen, MarkerOverlay, CaptionBar, StepCard, TalkButton, HomeShell (drawer), RemoteHome, AlertsScreen
```

---

## 7. Knowledge base

Location: `app/src/main/assets/kb/fixlens_kb.json`. Loaded once at startup, validated, and
indexed in a `HashMap` keyed by `appliance|brand|normalizedCode`.

**Generated, don't hand-edit:** the sources are `tools/kb/entries/<category>.json` (one per appliance), merged by
`python3 tools/kb/build_kb.py <kb_version>`, which also writes `tools/kb/test_queries.json` (run by `KbTest`).
How to write entries (consensus of ≥ 2 manufacturers' manuals, symptom-first, spoken-length limits):
[tools/kb/AUTHORING.md](tools/kb/AUTHORING.md). Per-entry sources: `tools/kb/sources/<category>.md`.
Entries are generic (`brand: "generic"`); a brand's error codes for the same fault are listed in `brand_codes`.

**Why JSON and not vector RAG (deliberate decision; don't change it without asking):**
the primary keys are exact identifiers (error codes). Vector search is weak on exact codes and
can return a neighbouring code, which is a safety issue. With 10–30 verified entries, exact lookup
is more accurate, explainable and uses no extra RAM. Hybrid FTS5 + vector search is the
post-hackathon plan, not now.

### Entry schema
```json
{
  "id": "car_check_engine_oil",
  "appliance": "car_engine_bay",
  "brand": "generic",
  "error_code": null,
  "code_aliases": [],
  "title": "Check engine oil level",
  "meaning": "Routine check of the engine oil level.",
  "symptoms": ["oil warning light", "low oil"],
  "aliases": ["how do I check the oil", "dipstick", "engine oil level"],
  "severity": "diy",
  "escalate_if": ["oil light stays on while driving", "burning smell"],
  "safety": ["Turn the engine off and remove the key.", "Wait until the engine is cool."],
  "steps": [
    {"n": 1, "say": "Find the dipstick with the yellow ring handle.",
     "target": "yellow ring handle of the engine oil dipstick", "verify": null, "caution": null}
  ],
  "source": "Owner's manual, engine oil section"
}
```
- `severity`: `diy` | `caution` | `call_technician`.
- `remind` (optional, routine checks only): `{"after_days": 7, "say": "..."}`. When the guide is finished Fixy
  schedules a local reminder that many days later; `say` is the notification text, verbatim. Intervals come from the
  manuals like everything else (tools/kb/sources, "Reminder intervals").
- `target` is a **descriptive grounding phrase** for the VLM ("yellow ring handle of the engine
  oil dipstick", not "dipstick"), or `null` for steps with nothing to point at.

### Retrieval: 4 stages, in order
0. **Appliance:** from the user's words, else the session notes (`MemoryRules.kbContext`); both stages search only
   that appliance's entries ("not cooling" is an AC or a fridge). None known → all entries.
1. **Exact:** normalize the code (uppercase, strip spaces, treat `0`/`O` as the same, apply
   `code_aliases` / `brand_codes[].spoken`) → match against each brand's codes. With the brand known, only its codes
   count; without, the code must lead to one entry. Letters-only codes ("OE", "dC") also need a cue word or a brand.
2. **Keyword/alias:** token-overlap score of the user text against `aliases` + `symptoms`,
   filtered by appliance ("won't"/"doesn't"/"no" read as "not"). Accept only if the top score clearly beats the second.
3. **LLM router:** send the VLM the list of `id — title` for that appliance and require JSON
   `{"id":"<id>|NONE","confidence":"high|medium|low"}`. Accept only if the id exists and
   confidence is not low.
4. **Refuse:** the `Unknown` state → the refusal line.

### KB rules
- Safety lines and `steps[].say` are **displayed and spoken verbatim** from the JSON.
- The VLM may only rephrase or answer follow-ups using the current entry.
- `call_technician` or a matched `escalate_if` → `Escalate` state; no repair steps.
- Validate at startup: every entry has an id, severity, ≥1 safety line (unless call_technician, or its first step
  carries the warning as a `caution`, as the car oil check does so it starts straight at the dipstick)
  and ordered steps. Fail loudly in debug builds.
- Log `entry_id` + `kb_version` with every answer.

---

## 8. Fixy persona

- **Greeting (templated, never generated, shown instantly when a new session opens):** the first-ever
  session gets "Hi, I'm Fixy! Point me at what's broken and tell me what's happening." Later ones get a
  time-of-day hello that asks after the latest recent repair ("Last time we looked at your washing machine
  (error OE) yesterday. Is it working fine now?"). See `guide/Recall.kt` and
  [docs/fixy-memory.md](docs/fixy-memory.md), which also covers `<past_repairs>` and the outcome loop.
- **System prompt (keep in `guide/FixyPrompts.kt`):**
```
You are Fixy, a friendly repair helper inside the FixLens app.
- Speak like a calm, patient friend standing next to the user. Max 2 short sentences.
- Your name is Fixy. Never call yourself an AI model, Qwen, or anything else.
- Repair facts come ONLY from <kb_entry>. If the answer is not there, say exactly:
  "I don't have verified guidance for that, please contact a technician."
- Never invent steps, parts, torque values, wiring or error codes.
- Always put safety first.
- When asked to point at something, output the box line first, then your spoken reply.
<kb_entry id="{id}" version="{kb_version}">{compact entry JSON}</kb_entry>
<state>current_step={n} of {total}</state>
```
- No fine-tuning. The persona is prompt + fixed greeting only.

---

## 9. VLM I/O contract

- **Model:** Qwen3-VL-4B-Instruct int4 on MNN (fallbacks in STACK.md).
- **Input image:** keyframe downscaled to 448–640 px on the long side.
- **Grounding request:**
```
Find: "{target phrase}"
First line: JSON only, {"bbox_2d":[x1,y1,x2,y2],"label":"..."} or {"bbox_2d":null}
Then: one or two short sentences as Fixy, using only the verified step.
```
- **Coordinates:** Qwen3-VL is expected to return a **0–1000 normalized** scale; Qwen2.5-VL returns
  **absolute pixels of the resized input**. `BoxMapper` must support both, selected by config.
  **Verify with a drawn test box before trusting either.**
- **Mapping chain:** model coords → keyframe pixels → (tracker space) → PreviewView/screen coords.
  Handle rotation and crop (use CameraX `CoordinateTransform` / `OutputTransform`).
- **Parser:** tolerate extra whitespace or markdown fences around the JSON; if the first line is
  not valid JSON, treat the whole output as text and show "Point the camera at the {part}".
- **Generation settings:** temperature 0.2–0.3, max new tokens 120–150.
- The VLM runs **only on events** (a question, a re-ground, a router call). **Never per frame.**
  One request at a time on a dedicated single-thread dispatcher.

---

## 10. Tracker

- `FrameRingBuffer` keeps the last ~3 s of downscaled grayscale frames plus timestamps.
- On a new box: find the buffered frame matching the keyframe timestamp, detect good features
  inside the box (`goodFeaturesToTrack`), then run LK optical flow frame to frame up to "now",
  estimating a homography (RANSAC) each step to move the box.
- Live: update every analysis frame. Smooth the box center with an EMA (α ≈ 0.4).
- Confidence = inlier ratio + point count. If the point count drops, re-detect features inside
  the current box. If confidence stays low for more than ~1 s → request a re-ground. If the target
  is lost, hold the marker ~500 ms and then fade it.
- The overlay draws a pulsing box plus a dimmed mask outside it.

---

## 11. Voice

- **Input:** push-to-talk (hold the mic button; always-listening picked up too much background
  noise). `AudioRecord` at 16 kHz mono, audio source `VOICE_COMMUNICATION` (echo cancellation).
  Audio is kept only while held (+0.3 s pre-roll and tail). On release, Silero VAD gates the take
  (no speech → dropped) and Moonshine transcribes the speech span. A keyboard button beside the mic
  is the typed fallback. Show a level-reactive "listening…" animation while held; captions live.
- **Commands:** plain string match on the transcript: "done"/"next" → advance,
  "repeat" → re-speak, "back" → previous step, "stop" → stop TTS. Anything else is a question.
- **Output:** **Piper** `en_US-lessac-medium` (VITS, 22.05 kHz, 63 MB) through sherpa-onnx `OfflineTts` (already in
  the AAR); model in `files/tts/piper/` (`<voice>.onnx`, `tokens.txt`, `espeak-ng-data/`). `voice/SpeechChunker` cuts
  the VLM's token stream into pieces (the first clause of a reply, then sentences); `voice/SpeechOutput` synthesizes
  them on `fixlens-tts` while the previous piece plays on `fixlens-tts-out` (`AudioTrack` float stream, `USAGE_MEDIA`,
  low-latency). Silence at clip edges is trimmed. KB text, the greeting and fixed lines are spoken verbatim
  (`SpeechOutput.speak`). The mic is push-to-talk, so no echo gating is needed.
- **Barge-in:** pressing the mic always stops the voice (`SpeechOutput.stop`: new epoch, queues dropped, track
  flushed), also after generation has ended; a new turn or guide step stops it too.
- **Latency masking:** a pre-rendered filler ("Let me look.", …) plays at once when a question goes to the VLM.

---

## 12. Non-negotiable rules for code in this repo

1. **No network.** Do not add the `INTERNET` permission, network libraries, analytics or model
   downloaders. Strip any download feature inherited from the MNN fork. Models are side-loaded
   with `adb push`.
2. **Never run the VLM per frame** or on the main thread.
3. **Never generate repair steps.** Steps and safety text come from the KB verbatim.
4. **Main thread = UI only.** Camera, tracker, audio and VLM each run on their own thread or dispatcher.
5. Build for `arm64-v8a` only.
6. Ask before adding any new dependency not listed in STACK.md.
7. Prefer small, testable increments that run on the device. After each milestone, update §15.

---

## 13. Out of scope (don't build unless asked)

Wake word ("Hey Fixy"), Telugu/Hindi, YOLO/detector training, vector
search/embeddings, LLM/VLM fine-tuning, ARCore, cloud anything, accounts or login.
Several of these are Grand Finale roadmap items and can be mentioned in the pitch.

---

## 14. Milestones and decision gates

| # | Milestone | Done when |
|---|---|---|
| M0 | Model check in the MNN app on the phone | Boxes correct in ≥4/5 photos per target, ≲3 s per answer, JSON format followed |
| M1 | Fork runs; camera frame → VLM → box drawn on preview | Box lands on the named part on screen. **Gate at hour 4:** if not working, switch to MediaPipe + Gemma 3n |
| M2 | Tracker with fast-forward | Marker stays locked while moving the phone around the target |
| M3 | Voice in/out + Fixy greeting | Spoken question → caption → spoken + captioned reply, with barge-in |
| M4 | KB + retrieval + guide state machine | Full guided flow incl. safety gate, "done", escalation and refusal |
| M5 | Freeze and polish | Airplane-mode run passes end to end; backup video recorded |

---

## 15. Current status (update this as you go)

- [x] M0 model check (scale 0..1000 confirmed, JSON format 11/11, boxes 8/11 with target phrases; speed NOT met: ~5 s to box line)
- [x] M1 frame → box on screen
- [x] M2 tracker
- [ ] M3 voice
- [ ] M4 KB + guide
- [ ] M5 freeze

Notes:
- **P0 build toolchain (2026-09-26):** Gradle 9.8.0 wrapper, AGP 9.4.1, Kotlin 2.4.20 (AGP 9 built-in
  Kotlin, so no `org.jetbrains.kotlin.android` plugin; Compose-compiler and serialization plugins at 2.4.20),
  Compose BOM 2026.09.00, activity-compose 1.13.0, lifecycle 2.11.0, coroutines 1.11.0, serialization-json
  1.11.0. compileSdk/targetSdk 37 (Android 17 is stable), minSdk 29, NDK 28.2.13676358, JDK 21 running with a Java 17 target.
- **Models** live in `/home/shiva-ajay/3D/fixlens-models/` (outside the repo):
  `qwen3-vl-4b/` = HF `taobao-mnn/Qwen3-VL-4B-Instruct-MNN` (~2.95 GB: llm.mnn.weight 2.71 GB + visual.mnn.weight 243 MB),
  `qwen3-vl-2b/` = `taobao-mnn/Qwen3-VL-2B-Instruct-MNN` (~1.48 GB), `sherpa-onnx/` = `sherpa-onnx-1.13.8.aar` (50 MB)
  + `silero_vad.onnx` (0.6 MB), `moonshine-base-en/` (int8, 275 MB), `moonshine-tiny-en/` (int8, 120 MB), both from
  the sherpa-onnx `asr-models` release. The MNN repos ship `tokenizer.txt` (no `embeddings_bf16.bin`).
  Gotcha: `hf download` (Xet backend) stalled on this network; plain `curl -C -` on `/resolve/main/` works.
- **MNN donor:** `alibaba/MNN` `apps/Android/MnnLlmChat`. Wrapper `LlmSession.kt` → `libmnnllmapp.so` (JNI) → prebuilt
  single `libMNN.so` from `project/android/build_64.sh`. Images go in the prompt as `<img>/abs/path.jpg</img>`; tokens
  stream via `GenerateProgressListener.onProgress` (return true = stop). Qwen3-VL `image_max_pixels` defaults to 16.7 MP,
  so cap it. Details and the M1 transplant plan: [docs/mnn-donor-notes.md](docs/mnn-donor-notes.md).
- **Sessions + memory (2026-09-26), see [docs/sessions-plan.md](docs/sessions-plan.md):** the app opens on a sessions
  list (models load behind it); each session is `filesDir/sessions/<id>/session.json` + keyframes. The VLM keeps one
  session's conversation in the KV cache (`reuse_kv`, `use_template:false`, `max_all_tokens` 4096; prompts are raw Qwen
  chat-template text built in `guide/FixyPrompts.kt`); cancelled turns and side requests are rolled back with
  `eraseHistory`. `guide/ConversationContext.kt` rebuilds from notes (`guide/MemoryRules.kt`) + last 2 turns on
  session switch or near the limit. **libMNN.so must include `tools/mnn-patches/omni-multiturn-positions.patch`**
  (upstream Qwen-VL decode positions ignore the KV offset, which breaks every turn after the first).
- MNN Chat on the phone: Play build `com.alibaba.mnnllm.android.release` v0.8.4. Local models are loaded from
  `/data/local/tmp/mnn_models/<name>/`.
- **Qwen3-VL M-RoPE fix (2026-09-26), required for grounding:** taobao-mnn's `llm.mnn` (4B and 2B) uses Qwen2-VL
  *chunked* M-RoPE; Qwen3-VL needs *interleaved*. Text is unaffected, but image positions are nearly invisible (boxes 0/12
  on synthetic shapes). `tools/mnn-patches/qwen3vl_mrope_fix.sh <model_dir>` patches the graph (weights untouched; the
  original is kept as `llm.mnn.chunked`; `tools/push_models.sh` runs it). After it: 12/12 synthetic, 8/11 real photos.
  Test harness: `tools/m1/run_ground_test.py`. Details: [docs/marker-tracking.md](docs/marker-tracking.md) §2.
- **Marker + tracking (M1/M2, 2026-09-26):** Preview + YUV analysis (1280×720, 16:9) share one ViewPort (`UseCaseGroup`),
  so the VLM keyframe is exactly what's on screen and analysis → view is a pure scale (test box verified). Box scale
  0..1000. Tracker (OpenCV 4.14 LK + RANSAC homography, 218×480 gray): **p50 1.6–3.6 ms, p95 3.9–8.6 ms**;
  fast-forward 140–180 frames in 66–90 ms; end of speech → box line **4.4–5.6 s**; re-ground 5–6 s.
  Debug over adb: `--es ask "point: <phrase>"`, `--es image <file>`, `--ez testbox true`, `--ez freeze true`.
- **Multi-part pointing (2026-09-27):** one answer can mark several parts (a JSON list, streamed part by part, tracked as
  one group). Good for a few distinct medium parts; tiny repeated parts (screws) get one box around their area and the
  count goes in the spoken step (the 4B model invents screw grids). Details and numbers: docs/marker-tracking.md §6.
- **Guided repair (M4, 2026-09-27; not ticked: KB content is DRAFT and the stage-3 LLM router isn't built):**
  `kb/` (models, loading + §7 validation, retrieval stages 1-2) and `guide/Guide.kt` (safety lines confirmed with "done",
  then steps; back/repeat/stop; `escalate_if` and `call_technician` escalate). A KB match starts a guide; each step's
  `target` is pointed at by a rolled-back side request (`ConversationContext.side`) and tracked; steps with `verify` are
  auto-checked by the VLM every 6 s while the user is idle. No KB match: the VLM answers and points, with no steps (user
  decision). Verified on the phone: match, safety gate, step pointing (~5 s), back, escalation, auto-check. Known issue:
  asked for a part that isn't in view, the VLM often points at something else anyway (keyboard as "engine").
  `assets/kb/fixlens_kb.json` was a DRAFT; replaced by the generic KB below.
- **Step animations (2026-09-27, not yet tried on the phone):** during a guide, a small how-to card (top right,
  moves left if the part is under it) shows how to do the step: turn ↺/↻, pull out, push in, level between marks,
  pour, unplug, switch off, engine off, call a technician. Turn/pull/push/pour also get a cue drawn on the tracked part
  (arrows circling the cap). What to show comes only from the KB (`steps[].anim`, `safety_anim`); a turn's direction
  must be stated in the step's words (validated). `ui/StepCues.kt`; SVGs in `design/anim/`; plan in
  [docs/step-animations-plan.md](docs/step-animations-plan.md).
- **Generic KB (2026-09-27, `2026-09-27.generic1`):** 60 entries over 11 appliances (car 14, washing machine 7, bike 7,
  AC 5, fridge 5, TV 5, laptop 4, geyser 4, microwave 3, purifier 3, inverter 3), each step backed by ≥ 2 makers'
  official manuals (sources per entry in `tools/kb/sources/`). Symptom-first; washer/AC/fridge/geyser brand error codes
  map onto the generic entries (`brand_codes`). 220 retrieval test queries pass (`KbTest`). **Not yet done:** the
  `target` phrases haven't been tried on real photos (M0/M1 method), and spoken codes ("five c", "d p e r") haven't
  been tried with Moonshine. A first question with no appliance word ("fix the wiring") searches every appliance and
  can tie → the VLM answers without a guide.
- **Voice out (M3, 2026-09-27; not ticked until a spoken question + mic barge-in is tried by hand):** Piper
  `en_US-lessac-medium` from the sherpa-onnx `tts-models` release (`vits-piper-en_US-lessac-medium.tar.bz2`); pick others
  with `tools/tts/audition.py`, push with `VOICE=<voice> tools/push_models.sh`. Supertonic 3 was tried first and dropped:
  ~0.3-0.8 s per clause, and its fast 2-step mode sounded metallic/echoey. Piper on the phone: load 490 ms + warm-up
  60 ms (before the VLM, so the greeting speaks while the VLM loads); a ~1.5-2 s clause in ~270 ms idle, ~370 ms while
  the VLM decodes (RTF 0.07-0.18). Filler at +0 ms; pieces gapless. A stop can't abort a piece mid-synthesis (≤ ~0.4 s).
  Debug: `--es say "<text>"`, `--ef ttsspeed F`. Not done: highlighting the caption being spoken.
- **Casual Fixy (2026-09-27, not yet tried on the phone):** questions are sorted by `guide/Intent.kt` into Chat (no
  picture, no pointing), Look ("what do you see": describe only) and Repair (points, as before). The persona has no device
  examples and doesn't pivot to repairs. A new session sees the earlier repair only for the reply to its greeting; then
  the cache is rebuilt without it. Details: [docs/fixy-memory.md](docs/fixy-memory.md) §1-2.
- **Lock-on + hand-off motion (2026-09-27, builds; not yet seen on the phone):** each part pointed at gets a comet
  from Fixy's orb (`ui/MarkerMotion.kt`, drawn over the cards, homing on the tracked box), then a lock-on where it lands
  (`ui/MarkerOverlay.kt`: flash + shockwaves, dim closes in like an iris, corner brackets spring in with a twist,
  outline traces round, scan line, name chip pops; settled = breathing ring + a glint lapping the outline) and a haptic
  tick. Silent re-grounds are `Seed(quiet = true)`: a short re-lock only. One frame clock drives it all (`ui/fx/Motion.kt`).
- **Fixy takes the remote (2026-09-27, builds + unit-tested; not yet tried on the phone):** the IR blaster pairs with and
  controls ACs, TVs, projectors and fans (`ir/`, `ui/remote/`). "Take the remote" → VLM reads device + brand (always
  confirmed; brand sheet if unread) → pairing card tries each *distinct* code (test 1 mute/menu/AC on 24°, test 2 vol+/
  back/25°; AC beeps auto-confirm via `ir/BeepProbe`, else buttons/voice/volume keys) → badge top-right ripples per send.
  Spoken commands ("set it to 24", "volume up by 3") go out with no VLM; routines: AC not cooling, TV no picture (cycles
  inputs, VLM checks the screen), no sound. Codes: `assets/ir/` (2.2 MB) built by `tools/ir/build_ir_assets.py` from
  `ir-dataset/` (IRext MIT + Flipper-IRDB CC0); AC states from the vendored IRext decoder (`cpp/irext/`, JNI
  `fixlens_ir.cpp`). Permission `TRANSMIT_IR` added. Debug: `--ez irinfo true`, `--ez irfake true`, `--es ir "tv/LG/mute"`,
  `--es irpair "tv:LG"`, `--es irtake "take the remote"`. Plan + edge cases: [docs/ir-remote-plan.md](docs/ir-remote-plan.md).
  Tried on the phone (2026-09-27): IR emitter present (carriers 30/33/36/38/40/56 kHz), TV and AC codes sent, the
  AC decoder runs on the phone, badge + pairing card render. **Remote agent (2026-09-27, builds + tested, not yet on the
  phone):** `ir/RemoteAgent.kt` + `RemoteController.agentLoop`: no agent SDK (they need a cloud model); Qwen-VL plans
  one JSON action per step (press / wait / ask / done) from the camera frame, the goal and the history; the controller
  checks it against the remote's real keys (power only if asked), sends it, max 8 steps. Goals like "switch to HDMI 2",
  "no sound" go to it; plain commands stay on the fast path. Pairing now scans codes by itself: TV test = volume up,
  seen by `ir/ScreenProbe` (24×24 brightness grid), AC by its beep; "It responded" / volume-up anytime. Every send drops
  a key pop-up under the badge (`ui/remote/KeyPressStack.kt`). Debug: `--ez irfake true` (no IR out), `--es irpress vol_up`.
  **Is it the TV or your remote?** During (or after) a KB "remote not working" guide, "still not working" / "diagnose
  it" (or anytime "test the TV with your remote") starts `RemoteController.startRemoteTest`: same device as the saved
  pairing? → aim ("tell me when you're ready") → press volume up (AC: temp up) → camera/beep, else ask → verdict (the
  device reacts: the user's remote is at fault; it doesn't: the device's sensor/the device) and undo the key.
- **Home drawer: universal remote + alerts (2026-09-27, builds + unit-tested; not yet tried on the phone):** the home
  screen has a side drawer (menu button top left): Repairs, Universal remote, Alerts. `ui/HomeShell.kt`.
  *Universal remote* (`ui/RemoteHome.kt`): the saved remotes (`files/remotes.json`, now with an optional user name;
  long-press to rename/forget), "add a remote" by device tile → brand sheet → pairing, then the pad. Same
  `RemoteController` as in a repair, with `cameraOn = false`: no VLM look, pairing waits for "It responded" / volume up;
  the mic opens only while pairing an AC, to hear its beep. AC "last sent" state is kept in the saved remote.
  *Alerts* (`alerts/`, `ui/AlertsScreen.kt`): when a KB guide reaches Done and the entry has `remind`, Fixy says
  "I'll remind you to check it again in a week." and schedules it (one per entry; doing the check again replaces it).
  AlarmManager (1 h window, re-armed on boot) → local notification with "Start the check", which opens a new session on
  that guide. Alerts page: due first, then "Scheduled by Fixy" (start now / test in 5 s / cancel). KB
  `2026-09-27.generic2`: `remind` on 10 entries (car oil/coolant/washer/brake fluid 7 d, car battery 30 d, air filter
  90 d, bike chain 14 d, bike battery 90 d, AC filters 14 d, washer drum clean 30 d); no reminder for the bikes' daily
  pre-ride checks. Permissions added: POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED. Debug: `--es remind <entry_id>
  --ei remindsec 5`.

---

## 16. Dev environment and commands

- OS: Ubuntu. IDE: Android Studio. Device: iQOO 15 over USB (`adb`); mirror with `scrcpy`.
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# side-load the VLM (no network in the app)
adb push qwen3-vl-4b-instruct-mnn/ /sdcard/Android/data/com.fixlens/files/models/qwen3-vl-4b/
adb logcat -s FixLens
scrcpy
```
- Log tag: `FixLens`. Log per question: STT ms, retrieval stage used, VLM time-to-first-token,
  total time, tracker confidence.
