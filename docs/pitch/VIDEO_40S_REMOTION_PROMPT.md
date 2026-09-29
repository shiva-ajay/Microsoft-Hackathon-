# FixLens: 40-second product video (prompt for a Remotion session)

> Paste everything below the line into a new Claude Code session. It is written for that session.

---

## 0. Your job

You are a senior motion designer and a React/Remotion engineer. Build a **40-second animated product video
for FixLens** in Remotion. It works as an ad and an explainer: it shows what the product is, how you use it,
every major feature, and how it works inside.

**Rebuild the app UI in React, pixel-faithful to the real Android app. Don't use screenshots or screen
recordings of the app.** The real UI is Jetpack Compose code in the repo. This prompt gives you the specs, and
the Kotlin files are the ground truth: when this prompt and the code disagree, **the code wins**. Rebuilding
the UI keeps it crisp when the camera zooms in, and lets every effect be animated frame by frame.

Before you write any code, load the `remotion-best-practices` skill.

### Deliverable
| Item | Value |
|---|---|
| Composition id | `FixLensAd` |
| Size / fps / length | **1920×1080, 30 fps, 1200 frames (exactly 40.0 s)** |
| Output | `out/fixlens-40s.mp4` (H.264, CRF 18, AAC audio) |
| Project folder | `/home/shiva-ajay/3D/fixlens-video/` (new, **outside** the Android repo) |
| Repo (read-only source of truth) | `/home/shiva-ajay/3D/IQOO-Hackathon/` |
| Optional, only after the main cut is approved | `FixLensAdVertical`, 1080×1920, same scenes re-laid out |

Tools already on the laptop: Node 22, npx, ffmpeg. The Piper voices are in `/home/shiva-ajay/3D/fixlens-models/`.
The laptop has internet, so `npx create-video@latest` and `pip` work. (The app itself must never use the network;
that's a product rule, not a rule for this video tooling.)

---

## 1. The product in one minute (what the video must communicate)

**FixLens** is a fully offline Android repair assistant. You point the phone camera at something broken (a
car's engine bay, a washing machine) and ask a question out loud. The on-device assistant, **Fixy**, answers
by voice and on-screen text, and puts an AR-style marker on the exact part to touch. The marker stays locked on
that part as the phone moves. Everything runs on the phone (iQOO 15, Snapdragon 8 Elite Gen 5). There's no
internet, no cloud, no account, and the app doesn't even have the INTERNET permission.

What makes it special: **live voice conversation + the vision model pointing at the exact part + the marker
tracking it in real time.**

Features the video must cover, in this order of importance:
1. **Point**: the vision model (Qwen3-VL-4B, on the phone) finds the exact part and a marker locks on.
2. **Track**: the marker stays locked while the phone moves (OpenCV optical flow, every frame).
3. **Voice**: push-to-talk (hold the mic), Fixy talks back. Moonshine speech-to-text, Piper voice, all on-device.
4. **Verified, safe guidance**: steps come word for word from a checked knowledge base (61 entries, 11 appliance
   types, each step backed by at least 2 makers' manuals). Safety steps come first and must be confirmed with "done".
5. **How-to animations**: a small card and a cue drawn on the part show how to do the step (turn anticlockwise,
   pull out, pour a little).
6. **Knows its limits**: a danger sign ("burning smell") stops the guide at once. Fixy says to call a technician
   and shows technicians nearby. It never guesses.
7. **Any brand's error code**: LG OE, Samsung 5C, Bosch E18, IFB dPEr and Whirlpool F9E1 all map to one
   verified fix ("washing machine not draining").
8. **Beyond repairs**: Fixy takes the remote (IR blaster: AC, TV, projector, fan), schedules reminders for
   routine checks (alerts), and remembers your last repair.
9. **How it works**: two speeds. A slow brain (the vision model, once per question) plus fast eyes (the tracker, every frame).
10. **Offline and private**: airplane mode, no INTERNET permission.

Tagline: **Point. Ask. Fix.**

### Claims: only what is true today (non-negotiable)
Allowed on screen:
- "Runs 100% on the phone", "Works in airplane mode", "No internet permission", "No cloud, no account".
- Model names: Qwen3-VL-4B (int4, MNN), Moonshine, Silero VAD, Piper, OpenCV.
- "Tracker: 1.6–3.6 ms per frame" (measured median), "every frame, ~30 fps".
- "61 verified fixes · 11 appliances", "each step from 2+ makers' manuals".
- "LG · Samsung · Bosch · IFB · Whirlpool codes".

**Not allowed:** any answer-latency number ("instant", "1 second", "in 2 s"), accuracy percentages, user counts,
comparisons with other products or companies, or features that aren't in this prompt. The real wait before the
marker appears is about 5 s. The video shortens it, so the end card carries the small footnote
"Waits shortened for this video."

**Logos:** don't draw iQOO, vivo, Qwen/Alibaba, Google or any other third-party logo. Plain-text names are fine.
The technician cards are sample data and keep the app's **"Sample"** tag.

---

## 2. Brand system

### Colours (exact, from `app/src/main/java/com/fixlens/ui/Palette.kt`)
| Token | Hex / value | Use |
|---|---|---|
| `Ink` | `#0F1C2E` | App background, card fills (usually at 0.55–0.95 alpha) |
| `InkRaised` | `#17263B` | Dialogs, avatar circles, thumbnails' empty state |
| `Amber` | `#FF9F1C` | The one accent: marker, mic, Fixy's orb, labels, primary buttons |
| `Paper` | `#F6F1E7` | Text and icons on dark |
| `Muted` | Paper @ 56% alpha | Secondary text |
| `Hairline` | Paper @ 10% alpha | 1 dp borders and dividers |
| `Online` | `#3DDC97` | "On-device" dot, level-gauge OK band |
| `Danger` | `#FF5A5F` | Technician / escalation accent |
| Icon glow | radial `#1B2E48` → Ink | Video background glow |

Rules: amber is an accent, never body text on light backgrounds. The video background outside the phone is Ink
with a soft radial glow (`#1B2E48` at the centre, 35% of the frame) plus a very faint, slowly drifting dot lattice
(Paper at 4%, 28 px grid), which echoes Fixy's dotted orb. Add subtle film grain (2–3% noise, seeded per frame).

### Type
- **Inside the phone:** `Roboto` (Android's default, and what Compose renders), weights 300/400/500/600/700,
  via `@remotion/google-fonts/Roboto`. **1 sp = 1 px at phone scale 1.0.**
- **Outside the phone (kinetic headlines and callouts):** `Space Grotesk` 600 for headlines, and
  `Roboto Mono` 500 for small tech labels (uppercase, letter-spacing 0.12em).
- Headline 72 px (key word in Amber), sub-line 30 px Paper @ 72%, tech label 20 px Muted. At most 7 words per headline.

### Logo and mascot (use the real files; copy them into `public/`)
- `design/logo/fixlens-logo-dark.svg`: horizontal lockup in Paper (the wrench-lens mark + wordmark, amber i-dot).
  It contains `<g id="mark">`: the Paper wrench whose ring end is a camera lens holding **Fixy's amber iris
  with a white catchlight**. Read `design/logo/README.md` for the concept and the don'ts (never recolour the eye,
  never rotate the mark, never re-set the wordmark in a live font).
- `design/logo/fixlens-mark-mono.svg`: single-colour mark (the notification icon).
- `design/logo/fixlens-app-icon.svg`: launcher icon (Paper mark on an Ink squircle).
- **In the app, Fixy is the amber dotted orb** (see §4.6). That is the Fixy to use inside the phone.
- Optional, outside the phone and sparingly: the hard-hat Fixy mascot poses in `design/mascot/concepts/hardhat/`
  (`fixy-greeting.svg`, `fixy-pointing.svg`, `fixy-thinking.svg`, `fixy-caution.svg`, `fixy-happy.svg`,
  `fixy-listening.svg`). Use at most two, for example greeting on the brand reveal and caution on the limits scene.

### Motion language
- Every animation is a pure function of `useCurrentFrame()`. No CSS transitions or animations, no `setTimeout`,
  no `Math.random()` (use Remotion's `random(seed)` or the `hash01` below).
- **Port `app/src/main/java/com/fixlens/ui/fx/Motion.kt` to TypeScript 1:1.** The app's effects are timelines of
  "ms since a cue" built from these curves:
  ```ts
  const window = (e, start, dur) => clamp((e - start) / dur, 0, 1);
  const easeOutCubic = t => 1 - (1 - t) ** 3;
  const easeInOutCubic = t => t < .5 ? 4*t*t*t : 1 - (-2*t + 2) ** 3 / 2;
  const easeInSine = t => 1 - Math.cos(t * Math.PI / 2);
  const easeOutBack = (t, o = 1.7) => 1 + (o + 1) * (t - 1) ** 3 + o * (t - 1) ** 2;
  // under-damped spring step response, `s` in seconds
  const springOut = (s, d = .55, w = 15) => s <= 0 ? 0 :
    1 - Math.exp(-d*w*s) * (Math.cos(w*Math.sqrt(1-d*d)*s) + d/Math.sqrt(1-d*d) * Math.sin(w*Math.sqrt(1-d*d)*s));
  ```
  Convert with `ms = (frame - cueFrame) * 1000 / 30`.
- UI moves use `Easing.bezier(0.2, 0, 0, 1)`. Pops use `spring({ damping: 14 })`. Big phone and camera moves use
  `spring({ damping: 200 })`, which has no overshoot.
- Cuts land on a 120 BPM grid (every 15 frames); the scene table below already follows it.

---

## 3. The phone

### Device frame (a generic flagship; no brand marks)
- Screen **412 × 916 dp**, rendered at **1 px per dp at phone scale 1.0**.
- Flat graphite frame (`#1A1D22`, with a 1 px highlight gradient to `#3A3F47` on the edges), an even 12 px black
  bezel, 56 px outer corner radius and 44 px screen radius. A 10 px punch-hole camera sits centred 12 px from the
  top. Volume and power buttons on the right edge.
- Shadow: `0 40px 90px rgba(0,0,0,.55)`.
- Default placement: phone centre at **x = 560, y = 540**, scale 1.0 (the phone is about 436 × 940 px). Callouts
  and captions use the right side (x 900–1824).
- **Camera moves** scale and translate the whole phone group (the frame is allowed to crop it). Push-ins go up to
  1.8× on hero moments. Build the phone as one component with a `camera={ scale, focusX, focusY }` prop, and
  check that text stays crisp at 1.8× (render SVG/DOM at native size inside the scaled group; don't rasterize).

### System bars (drawn over the app, in Paper)
- Status bar, 24 dp: `7:42` at the left (13 px, weight 500); at the right an **airplane-mode glyph** and a battery
  at 84%. **No Wi-Fi or signal bars**, because the phone is in airplane mode. That is a story point; ring the
  airplane glyph in Amber in scene 3.
- Gesture bar: a 108 × 4 dp pill in Paper @ 50%, 8 dp above the bottom. `navigationBarsPadding` = 24 dp.

### The "camera" feed
The live camera is one of the real demo photos, filling the screen **`object-fit: cover`** (the app uses
`FILL_CENTER`). It sits inside a transform that simulates holding the phone by hand: slow drift of ±6 px and
±0.6° from smooth seeded noise, and bigger moves in the tracking scene. Overscan the photo by 8% so the edges
never show.

**The marker boxes are computed through the same transform as the photo**, so the marker looks truly locked
on while the "phone" moves. That is exactly what the product does. Keep one function:
`imageBoxToScreen(box01, feedTransform) → screen rect in dp`.

Part boxes, normalized to each photo (x1, y1, x2, y2), checked by drawing them on the images:

| Photo (copy from the repo into `public/photos/`) | Size | Part | Box |
|---|---|---|---|
| `demo/1_car_oil/01_engine_bay.jpg` | 1200×1600, portrait | Oil filler cap (round, black, oil-can symbol) | `0.427, 0.336, 0.625, 0.445` |
| same | | Dipstick (orange loop, bottom left) | `0.125, 0.695, 0.260, 0.780` |
| `demo/1_car_oil/02_dipstick_handle.jpg` | 1600×1067, close-up | Orange dipstick loop handle | `0.410, 0.130, 0.628, 0.600` |
| `demo/1_car_oil/04_dipstick_level.jpg` | 2592×4608, portrait (optional shot) | Dipstick tip with the two marks | `0.460, 0.240, 0.580, 0.530` |
| `demo/2_washer_drain/01_front_loader.jpg` | 3024×4032, portrait | LG front-loader, whole machine | (no marker in this scene) |

**Aim zone:** the bottom card stack covers the lower ~45% of the screen. Frame each shot (scale + offset) so the
target box sits between **y = 110 and 460 dp** and x = 24–388 dp, and so the part's name chip clears the top
bar (box top > 88 dp). For `02_dipstick_handle`, scale the photo by about 0.95–1.0 of its height and keep the
ring fully above the cards. **Render a still of each shot and look at it** before moving on.

Photo licences are CC BY / CC BY-SA (Wikimedia Commons, Flickr). The per-photo credits are in each folder's
`CREDITS.md`; the end card carries a one-line credit.

---

## 4. App UI components (build each as its own React component)

Ground truth for each is in `app/src/main/java/com/fixlens/ui/`. Read the file before you build its component.
Sizes are in dp (= px at scale 1.0).

### 4.1 Camera screen: layer order (`CameraScreen.kt`), bottom to top
1. Camera feed (photo, cover).
2. **MarkerOverlay**: dim layer plus marker (§4.7). It sits under the cards.
3. **Scrims**: top 160 dp gradient, Ink @ 75% → transparent; bottom 420 dp gradient, transparent → Ink @ 85%.
4. **How-to card** (§4.9), top right: `top = 24 (status) + 112`, `right = 12`, width 112.
5. **VoiceGlow** (§4.5), full screen, rising from the bottom edge while the mic is held.
6. **TopBar** (§4.2), plus an optional HintChip under it.
7. **Bottom column** (padding 16 h / 12 v above the 24 dp nav inset), from top to bottom:
   StepBanner → TechnicianStrip (technician jobs only) → ConversationCard → 16 dp gap → controls row.
8. **Comets** (§4.8), drawn **over** the cards.
9. **On-part step cues** (§4.9), also over the cards.
10. KeyPressStack (remote), top right under the badge, `top = 24 + 62`, `right = 12`.

### 4.2 TopBar
Row, padding 12 h / 10 v under the status bar.
- Back button: 44 dp circle, Ink @ 55%, a Paper chevron 16 dp (stroke 13% of its width, round caps).
- 12 dp gap, then the title: 17 px / 600, Paper, one line with ellipsis. It changes with fade-in + slide-up (260 ms).
- 12 dp gap, then the **"On-device" chip**: pill, Ink @ 55%, 1 dp Hairline border, padding 12 h / 6 v, a 6 dp
  `Online` dot, 8 dp gap, "On-device" 12 px / 500 Paper. When the remote badge is showing, the chip folds to its dot only.
- HintChip (optional): 14 px / 500 Paper, centred, Ink @ 75%, 1 dp Amber @ 60% border, radius 20, padding 16 h / 8 v.

### 4.3 ConversationCard (Fixy's card)
Full width, radius 24, Ink @ 88%, padding 20 h / 18 v. It animates its height (220 ms). While Fixy is thinking or
answering it has a **BorderBeam** (§4.5).
- Header row: **Fixy's orb 22 dp** (§4.6), 10 dp gap, then the **status label** in UPPERCASE, Amber, 12 px / 600,
  letter-spacing 1.2 px. It cross-fades (180/120 ms) between: `Hold to talk` · `Listening` · `Got it` ·
  `Looking` · `Found 1` · `Answering` · `Speaking` · `Typing`.
- Empty state (Muted, 15/21 px): "Point the camera at the problem, hold the mic and ask Fixy what's wrong."
- Question: 12 dp gap, "You" (11 px / 600, letter-spacing 1.2, Muted), 2 dp gap, then `“question”` in 15/21 px
  Paper. Alpha is **0.6 while the transcript is live and 0.9 once final**.
- Answer: 14 dp gap, 1 dp Hairline divider, 14 dp gap, then a row with "Fixy" (11 px / 600, Amber) and, while
  speaking, **SpeakingBars**: three amber rounded bars in a 14 × 10 dp box, each bouncing between 30% and 100%
  height out of step (periods 320, 410, 500 ms, reversing). 4 dp gap, then the answer text in **19/27 px, weight
  500, Paper**. The answer streams in word by word, as the model streams tokens.

### 4.4 Controls row
- Centre: **mic button**. A 76 dp box holding a 60 dp Amber circle with an Ink mic glyph (26 dp: a capsule, a
  U-shaped cradle arc and a stand; stroke 9%). **While held:** the whole button scales to 1.18 (160 ms) and a 76 dp
  ring of Amber @ 25% fades in behind it.
- Left: the history pill (only when there are earlier turns): Ink @ 60%, 1 dp Hairline, padding 14 h / 10 v, a
  3-line glyph, then "2 earlier" (13 px / 500).
- Right (16 dp from the mic): **keyboard button**. 48 dp circle, Ink @ 60%, 1 dp Paper @ 30% border, keyboard
  glyph 22 dp. It is the typed fallback; show it and don't use it.
- A **touch indicator** for the "user's finger": a 44 px circle, Paper @ 35% fill with a 2 px Paper @ 80% ring.
  Press = scale 0.9 plus a ripple ring growing from 44 to 90 px and fading over 12 frames. On a hold, it stays.

### 4.5 BorderBeam and VoiceGlow (ported from Libraries.dev, MIT)
- **BorderBeam** (`fx/BorderBeam.kt`): a soft amber light segment travelling round the card's rounded border,
  **one lap per 3.2 s**, over a faint static hairline. Stroke 1.5 dp, plus an inner glow built from stacked wide
  strokes (8 and 18 dp) at low alpha. Fade in 600 ms, fade out 500 ms. A rotating conic gradient masked to the
  border is fine.
- **VoiceGlow** (`fx/VoiceGlow.kt`): an amber glow rising from the bottom edge of the screen, driven by the voice.
  Seven soft radial lobes fan out from the bottom centre, their heights driven by three wobbling bands; there's a
  white-hot core and a bright edge line along the bottom. Envelope: attack 0.325 s, release 0.86 s, idle breathing
  5.2 s. Fade in 450 ms, fade out 600 ms. **Drive the level from the user's voice WAV** with
  `@remotion/media-utils` (`useAudioData` + `visualizeAudio`) so the glow truly follows the words.

### 4.6 Fixy's orb (`fx/ThinkingOrb.kt`; port the math, it's short)
A dotted 3D sphere of amber dots, near dots brighter and larger (dot colour = Amber × luminance, z-sorted far to
near). Three states cross-fade over 520 ms:
- **Idle**: a lat/long globe lattice turning very slowly (10% of the thinking speed), at 50% alpha.
- **Thinking**: the same globe spinning, with a scan meridian that rippling-brightens and enlarges the dots as it sweeps.
- **Listening**: a different lattice of rings that breathe in and out with a rolling two-tempo wave.
Size presets change the dot counts (22 dp for the card, 36 dp for the loading card, 56 dp for empty states).
Draw it in SVG circles from the frame time.

### 4.7 Marker lock-on (`MarkerOverlay.kt` + `MarkerMotion.kt`). This is the hero effect; port it exactly.
The box is the part's rect plus 6 dp padding. Corner radius = min(16 dp, minSide / 3). Timeline in **ms since the
comet lands** (impact):

| Element | Timing | Look |
|---|---|---|
| Impact flash | 0–240 | Amber disk @ 0.32 (radius × 1.5) and Paper core @ 0.55; radius 8 dp → 0.55·minSide + 18 dp (easeOutCubic); fades (1−p)² |
| Two shockwave rings | start 0 and 110, 520 each, peak alpha 0.85 / 0.45 | radius 0.3·minSide → 0.75·diagonal + 48 dp; stroke 4 → 0.5 dp |
| Dim "iris" | 0–560 easeOutCubic | Ink @ 42% over the whole screen; its hole starts as a screen-covering circle and closes onto the rounded box (use an SVG mask) |
| Corner brackets | spring `springOut(s, .55, 15)` | scale 1.9 → 1 with twist −14° → 0°, fade in over 90 ms; arm = min(24 dp, 0.32·minSide). Four stacked strokes: 13 dp Amber @ 0.10, 7 dp @ 0.22, 3.5 dp @ 1.0, then a 1.2 dp Paper core whose alpha cools 0.95 → 0.35 once settled |
| Outline trace | 120–600 easeInOutCubic | Traced from both ends at once, with a spark on each tip (7 dp Amber @ 0.3 glow + 2 dp Paper dot), then a steady 1.5 dp Amber @ 0.6 outline |
| Scan line | 380–1000 easeInOutCubic | Sweeps top to bottom, clipped to the box: 7 dp Amber @ 0.4 plus a 1.5 dp Paper line, with an amber wake gradient 40% of the box tall |
| Name chip | 520–860 | easeOutBack scale 0.55 → 1: pill Ink @ 85%, 6 dp Amber dot, 8 dp gap, label 13 px / 600 Paper, padding 12 h / 6 v, 10 dp above the box (below it if there's no room) |
| Settled | from 950 (fades in over 400) | A ring breathes out 0 → 10 dp every **1400 ms** (Amber 0.5 → 0, 2 dp); brackets breathe ±1.8%; a **glint** laps the outline every 3200 ms, lasting 900 ms (a segment 14% of the perimeter: 6 dp Amber @ 0.35 + 1.5 dp Paper @ 0.9) |

"Holding" (tracking wavers) means a dashed outline and 75% alpha. It isn't needed in this video. The marker
fades out over 350 ms.

### 4.8 The comet hand-off (`MarkerMotion.kt`, `drawComet`)
Each time Fixy points, an amber comet flies **from Fixy's orb in the card to the part** and the lock-on plays
where it lands. This is the "slow brain hands the part to the fast eyes" moment.
- Flight **460 ms** (about 14 frames). The path is a quadratic Bézier whose control point sits 0.28 × distance
  to the side (alternate sides for several parts). The head uses easeInSine, so it speeds up into the target, and
  it homes on the part's *current* screen position.
- Launch flare on the orb (280 ms): a disk 10 → 22 dp at Amber 0.35 fading, plus a ring 8 → 40 dp (stroke 3 → 0.5 dp, Amber 0.9).
- Tail: 42% of the path × √progress, 22 segments, three passes: bloom (width × 3.2, Amber 0.16·f²), body
  (Amber f²), and a white-hot core (Paper, where f > 0.65). Width 0.6 → 5 dp along the tail (f^1.6).
- Head: glows of 20 dp Amber 0.12, 10 dp Amber 0.3, 5 dp Amber, 2.5 dp Paper.
- 8 sparks shed along the path (life 420 ms), drifting with drag and a little gravity, cooling from Paper to Amber.
- On impact the tail snaps into the target (150 ms) and the lock-on starts. Play a haptic "tick" sound.

### 4.9 Step cues (`StepCues.kt`; `design/anim/*.svg` are animated reference exports)
**How-to card:** width 112, radius 16, Ink @ 93%, 1 dp border in the accent colour @ 40%, padding 8 h / 10 v. A 72 dp
line pictogram (Paper @ 92% strokes, 5% stroke width, round caps; the one moving part in Amber), 6 dp gap, then
the label (13/16 px / 600, centred, up to 2 lines). It enters with fade 220 ms + scale 0.9 → 1 (260 ms) and
changes by cross-fade (200 ms). Each pictogram loops every **2600 ms**: fade in 160 ms, act for about 1050 ms
(easeInOutCubic), rest, then fade out over the last 380 ms.

| Label (exact) | Pictogram |
|---|---|
| `Engine off` | Ignition circle; the key turns back 60° from ON to OFF; the ON dot dims; an arrow arcs |
| `Unplug first` | A plug slides left out of a wall socket; an arrow shows the way |
| `Pull out` | A rod with a ring handle slides up out of a tube; a chevron rises beside it |
| `Push back in` | The same, going in |
| `Between the marks` | A gauge with MAX/MIN marks; the fill rises (easeOutBack) into a green band; then a green tick |
| `Turn anticlockwise` / `Turn clockwise` | A cap seen from above with 16 grip ridges and a dot turning a quarter; an arrow arc draws round it |
| `Pour a little` | A can tips over a funnel; three drops fall in |
| `Call a technician` | A spanner in a **red** ring (Danger) that sends one soft pulse per loop; nothing else moves |

**Cues on the tracked part** start 900 ms after impact and fade in over 320 ms. Every amber stroke has a darker
halo under it (Ink @ 55%, 3 dp wider) so it reads on any photo:
- **Turn**: two 96° arrow arcs circle the part in the turning direction, going **half a turn per 2600 ms**.
  Radius = half the box diagonal + 12 dp (at most 120 dp). Stroke 3.5 dp, arrowheads 11 dp.
- **Pull / push**: three chevrons (arms 14 dp at ±45°, 3.5 dp) stream up (pull) or down (push) beside the part,
  22 dp to its right, travelling box height + 40 dp per loop, their alpha peaking mid-way.
- **Pour**: three drops (4.5 dp) fall from 48 dp above the box into its top, speeding up.
- **Level**: a 14 dp-wide rounded gauge 18 dp beside the part, green band (Online @ 35%) between 30% and 62% of
  its height, amber fill rising into it, Paper outline 2.5 dp, MIN/MAX ticks.

### 4.10 StepBanner (`StepBanner.kt`)
Sits above the conversation card, 10 dp below it. Radius 18, Ink @ 88%, 1 dp border in the accent colour @ 50%
(accent = Amber, or **Danger** for a technician job), padding 16 h / 12 v.
- Row: **progress pill** (UPPERCASE, Ink text 11 px / 600, letter-spacing 1.2, accent background, pill shape,
  padding 10 h / 4 v), 10 dp gap, then the title (15 px / 600 Paper, one line).
- Caution (optional): 8 dp gap, `⚠  text` in the accent colour, 14/19 px / 500.
- Prompt: 6 dp gap, Muted 13 px.
- Progress strings (exact): `Safety first · 1 of 2`, `Step 2 of 9`, `Technician`, `Done`.
- Prompt strings (exact, with curly quotes): `Say “done” once it's safe`, `Say “done” for the next step`,
  `Say “done”, or I'll see when it's done`.

### 4.11 TechnicianStrip (`TechnicianStrip.kt`), for technician jobs only
Header: "Technicians near you" (15 px / 600 Paper), then a "Sample" tag (11 px / 500 Muted, 1 dp Hairline pill,
padding 8 h / 2 v). Then a horizontal row (10 dp gaps) of cards 212 dp wide: radius 14, Ink @ 93%, 1 dp Hairline,
padding 12.
- A 38 dp avatar circle (InkRaised, 1 dp Amber @ 60% ring) with initials (13 px / 600), 10 dp gap, then the
  name (15 px / 600) over the trade (12 px Muted).
- Then `★ 4.8` (the star in Amber) + `  412 jobs  ·  1.2 km` in Muted, 12 px / 500.
- Then `₹499` (Amber, 17 px / 600) + `  visiting charge` (Muted, 12 px).
- For the car (trade "Car mechanic", base ₹499): Ravi Kumar 4.8 / 412 jobs / 1.2 km / ₹499 · Imran Shaikh 4.7 /
  268 / 2.4 km / ₹449 · Suresh Reddy 4.9 / 530 / 3.1 km / ₹549 · Anil Varma 4.6 / 187 / 4.0 km / ₹399.

### 4.12 Home: Repairs (`SessionsScreen.kt`) and the drawer (`HomeShell.kt`)
Ink background, padding 20 h.
- Top row (14 dp below the status bar): menu button (40 dp circle, Paper @ 6%, 1 dp Hairline, a 3-line glyph),
  14 dp gap, **logo lockup** 28 dp tall (`fixlens-logo-dark.svg`), then flush right the **engine chip** (pill,
  Paper @ 6%, 1 dp Hairline, padding 12 h / 6 v). The chip first shows a 14 dp thinking orb + "Getting ready",
  then cross-fades to a 6 dp Online dot + **"Ready · on-device"** (12 px / 500).
- 36 dp gap, then **"Your repairs"** (30 px / 600, letter-spacing −0.5), 6 dp gap, then "Fixy remembers each
  repair. Everything stays on this phone." (Muted 14 px).
- 24 dp gap, then the **New repair** button: full width, radius 22, **Amber fill**, padding 20 h / 18 v; a 40 dp
  circle (Ink @ 12%) with an Ink plus; 14 dp gap; "New repair" (18 px / 600, Ink) over "Point the camera and ask
  Fixy" (13 px, Ink @ 70%).
- 28 dp gap, "RECENT" (11 px / 600, letter-spacing 1.2, Muted), then session cards (12 dp apart): radius 20,
  Paper @ 4%, 1 dp Hairline, padding 12; a 68 dp thumbnail (radius 14; a photo, or InkRaised with an idle 22 dp
  orb when there is none); 14 dp gap; title (16 px / 600), the last answer (13/18 Muted, 2 lines), and
  "3 questions · Yesterday" (11 px / 500, Paper @ 38%).
  Cards to show: "Washing machine OE" (thumbnail `01_front_loader`, last answer "Plug it in and run a spin-only
  or drain cycle. If it still won't drain, please call a technician.") and "AC not cooling" (no thumbnail, so the
  orb shows; last answer "Put the dry filters back, close the cover, and switch on. Cooling can take three
  minutes to start.").
- **Drawer** (304 dp wide, Ink, right corners radius 28, scrim black @ 55%): logo 26 dp tall, then items (radius
  18, padding 12, 28 dp icon + title 16 px / 600 + detail 12 px Muted; the selected item gets an Amber @ 12% fill
  and an Amber icon): **Repairs** "2 saved repairs" (wrench), **Universal remote** "1 saved remote" (remote),
  **Alerts** "1 scheduled by Fixy" (bell). At the bottom: a 6 dp Online dot + "Offline · everything stays on this phone".

### 4.13 Universal remote pad (`RemoteHome.kt`, `remote/RemoteSheets.kt`, `remote/RemoteGlyphs.kt`)
- Page header: menu button, then "Universal remote" (30 px / 600) and "Fixy sends the codes from this phone's IR
  blaster. No internet, no extra app." (Muted 14 px).
- Pad sheet: radius 24, Ink @ 95%, 1 dp Hairline, padding 18 h / 16 v. Header: **RemoteGlyph** 22 dp in Amber (a
  small tilted remote with three signal arcs off its top that light up one after another as a ripple on every
  send), 10 dp gap, "LG AC" (17/22 px / 600) over "Last sent: Temperature 24°" (12 px Muted), and a close X.
- AC pad: **"24°" at 52/56 px, weight 300**, over "Cool, fan auto" (Muted 13 px); at the right, − and + keys
  (60 dp circles, Paper @ 7%, 1 dp Hairline, 12 dp apart). 14 dp gap, then a segmented control
  `Cool | Dry | Fan | Auto | Heat` (radius 12, Paper @ 5%, padding 3; the selected segment has radius 10,
  Paper @ 12%, 13 px / 600 Paper; the others 13 px / 500 Muted). 12 dp gap, then wide keys (radius 14, Paper @ 7%,
  1 dp Hairline, min height 48, 14 px / 500): `Fan auto` · `Swing off` · `Turn off` (this one in Amber text with an
  Amber @ 50% border).

### 4.14 Notification (the Alerts feature)
An Android heads-up notification dropping over the top of the screen: radius 24, `#1E2A3B`, padding 16. Top line:
the mono Fixy mark in Amber (16 dp) + "FixLens · now" (12 px Muted). Title: **"Check and top up engine oil"**
(15 px / 600). Body: **"Time for your weekly engine oil check. It only takes a couple of minutes."** (14 px Paper
@ 85%). Action: **"Start the check"** (14 px / 600 Amber).

---

## 5. Storyboard (1200 frames, 30 fps)

The scene cuts sit on the 15-frame beat grid. `f` is the absolute frame. **VO** is a voice line (see §6).
**CC** is the burned-in caption. **SFX** is a sound effect.

**Captions:** every spoken line also appears as a caption in the lower right (x 900–1824, bottom 110 px safe):
Space Grotesk 500, 32/42 px, at most 2 lines, with a small speaker tag: "You" in Paper @ 60%, **"Fixy"** in
Amber. They fade in over 4 frames and hold for the length of the line. This makes the video work muted.

| # | Scene | Frames | Seconds |
|---|---|---|---|
| 1 | Hook | 0–105 | 0.0–3.5 |
| 2 | Brand reveal | 105–180 | 3.5–6.0 |
| 3 | Home, offline | 180–255 | 6.0–8.5 |
| 4 | Ask Fixy | 255–390 | 8.5–13.0 |
| 5 | Safety first | 390–450 | 13.0–15.0 |
| 6 | Point + lock-on (hero) | 450–600 | 15.0–20.0 |
| 7 | Tracking + pull cue | 600–690 | 20.0–23.0 |
| 8 | Turn cue on the cap | 690–765 | 23.0–25.5 |
| 9 | Knows its limits | 765–885 | 25.5–29.5 |
| 10 | Any brand's error code | 885–960 | 29.5–32.0 |
| 11 | Beyond repairs | 960–1065 | 32.0–35.5 |
| 12 | How it works | 1065–1140 | 35.5–38.0 |
| 13 | End card | 1140–1200 | 38.0–40.0 |

### Scene 1: Hook (f0–105), full frame, no phone
- f0: a washing-machine LCD centred (a 360×180 rounded dark panel with 7-segment digits) shows **`OE`** in Amber
  segments with a soft glow. It blinks: on f0–12, off f12–18, on f18–30, off f30–36, then stays on. SFX: two
  soft appliance beeps at f0 and f18.
- f8–40: the headline builds word by word beneath it: **"Your washer just flashed OE."** ("OE" in Amber).
- f45: the LCD and headline slide up and out. Three lines slam in, left-aligned at x 520, at f48, f56 and f64,
  each with a thin Paper line icon: **"No manual."** (a book with a slash) · **"No technician."** (a clock with
  "3 days") · **"No signal."** (signal bars dropping to zero). SFX: three low thuds.
- f90–105: an **amber scan line** (the marker's scan line: 7 px Amber @ 40% + 1.5 px Paper, with an amber wake)
  sweeps top to bottom and wipes the text away. SFX: whoosh at f92.

### Scene 2: Brand reveal (f105–180)
- f105–123: the mark (`<g id="mark">`) draws in centred at 240 px: stroke-dash reveal of its outline in Paper,
  then the fill fades in (f115–125).
- f120: the **amber iris springs in** (`spring({ damping: 12 })`), the catchlight pops at f126, and one marker
  shockwave ring races out from the iris (f122–138). SFX: logo sting (chord swell + glass chime).
- f132–144: the wordmark reveals left to right from behind the mark (use the full `fixlens-logo-dark.svg`
  lockup; mask wipe).
- f145 / f152 / f159: **"Point."** **"Ask."** **"Fix."** appear under the logo, 56 px, each with an amber dot
  pop. SFX: a soft tick per word.
- f162: sub-line **"Your repair friend. It lives on your phone."** (30 px, Paper @ 72%).
- (Optional) the `fixy-greeting.svg` mascot pops in beside the lockup at f150 and waves once.

### Scene 3: Home, offline (f180–255)
- f180–205: the logo lockup shrinks and flies into the phone's header logo position (a match-cut) while the phone
  rises from below (translateY 1100 → 0, rotateX 18° → 0°, perspective 2000 px, `spring({ damping: 200 })`).
  The phone settles at x = 560.
- The screen shows **Home (§4.12)**. The engine chip reads "Getting ready" with its thinking orb, then flips to
  **"Ready · on-device"** at f215.
- f200: right-side callout. Headline **"Everything runs on the phone."** Sub **"No cloud. No account. No internet
  permission."** Tech label `AIRPLANE MODE · ON`. At f210–240 an amber ring pulses around the phone's
  status-bar airplane glyph.
- f238: the touch indicator taps **New repair** (the button scales to 0.97). f245–258: the button expands into the
  camera screen (a container transform: the amber rectangle grows to fill the screen and fades into the camera
  feed). SFX: tap f238, soft whoosh f245.

### Scene 4: Ask Fixy (f255–390)
- The camera screen shows `01_engine_bay` with the filler cap in the aim zone, plus a gentle handheld drift. The
  TopBar title is "New repair" (it changes to **"Engine oil check"** at f370).
- f262: the card shows the greeting as Fixy's answer: **"Hi, I'm Fixy! Point me at what's broken and tell me
  what's happening."** Status "HOLD TO TALK", orb idle.
- f282: the finger **holds the mic**. The mic scales to 1.18, the ring shows, the VoiceGlow rises, the orb
  becomes Listening, the status reads "LISTENING" and the greeting clears.
- f288–352: **VO user_q1** "How do I check the engine oil in my car?" The transcript fills in word by word at
  alpha 0.6, in sync with the audio.
- f356: release. The glow fades, the status shows "GOT IT" for 6 frames and the question goes to alpha 0.9. Then
  at f362 the status reads **"LOOKING"**, the orb is Thinking and the BorderBeam fades in.
  **VO fixy_filler** "Let me look." (f364–384).
- Camera: push in to 1.25× on the lower half of the phone (card + mic) during f282–356, then ease back to 1.0 by f390.
- f290: callout. **"Just ask. Out loud."** Sub **"Hold the mic, speak, let go."** Tech label
  `MOONSHINE SPEECH-TO-TEXT · ON-DEVICE`.

### Scene 5: Safety first (f390–450)
- f390: the beam fades and the status reads "SPEAKING". The **StepBanner** grows in above the card (220 ms):
  pill **`SAFETY FIRST · 1 OF 2`**, title **"Check and top up engine oil"**, prompt **`Say “done” once it's safe`**.
  The **How-to card "Engine off"** pops in at top right.
- The answer streams in (caption only, no voice): **"Let's check your engine oil on the dipstick, and top it up
  if it's low. Park on level ground and switch the engine off."**
- f395: callout. **"Safety first. Always."** Sub **"Every step is read word for word from checked manuals. Never
  made up."**
- f428: the question row shows “Done” (optional **VO user_done**). The pill rolls to `SAFETY FIRST · 2 OF 2`, then
  to `STEP 1 OF 9`. A small `>>` skip mark (Muted) next to the phone makes the skip honest.

### Scene 6: Point + lock-on, the hero (f450–600)
- f450: a whip-pan (the feed slides up with 8 px of vertical motion blur for 6 frames) to the
  `02_dipstick_handle` close-up. The question row shows “Next”. The banner reads **`STEP 2 OF 9`** with prompt
  `Say “done” for the next step`. The how-to card fades out (this step has no animation).
- f456: status "LOOKING", orb Thinking, beam on.
- f470: the answer streams in and **VO fixy_step2** plays: **"Find the dipstick. Its handle is a bright ring or
  loop, usually yellow or orange."**
- f478: **the comet launches from the orb** and lands on the loop at **f492** (§4.8). Status **"FOUND 1"**.
- f492: **lock-on** (§4.7). Chip label **"Dipstick"**. SFX: haptic tick + lock-on (a low thump plus a bright
  two-note chime).
- Camera: push to 1.35× (f465–478) so the comet's whole flight is in frame, then push to **1.8× centred on the
  part** (f492–530). Hold while it settles (breathing ring, one glint at about f560), then pull back to 1.2× by f600.
- f495: callout. **"Points at the exact part."** Sub **"Fixy's vision model finds it in the camera view."**
  Tech label `QWEN3-VL-4B · INT4 · MNN · ON THE PHONE`.

### Scene 7: Tracking + pull cue (f600–690)
- f600–660: **the "phone" moves**: the feed transform swings x ±40 px, y ±24 px, rotation ±4° and scale 0.96–1.08
  on smooth seeded noise. The marker stays glued to the loop because it is computed through the same transform.
  This is the proof shot, so make it clearly visible.
- f605: callout. **"Stays locked on as you move."** Tech label `OPENCV OPTICAL FLOW · EVERY FRAME · 1.6–3.6 MS`.
- f650: the question shows “Next” (optional **VO user_next**). The banner reads `STEP 3 OF 9` and the answer is
  "Pull the dipstick all the way out and wipe it clean with a cloth." The how-to card shows **"Pull out"**, and
  from f660 amber chevrons stream up beside the loop (the marker stays; there's no new comet for the same part).

### Scene 8: Turn cue on the cap (f690–765)
- f690: a whip-pan back to `01_engine_bay` with the **filler cap** in the aim zone. The banner rolls to
  **`STEP 6 OF 9`** (with the `>>` skip mark). The answer reads **"If it's near or below the lower mark, unscrew
  the oil filler cap on top of the engine."** Prompt `Say “done”, or I'll see when it's done`.
- f696: comet from the orb → impact at f710 → lock-on with chip **"Oil filler cap"**.
- How-to card **"Turn anticlockwise"**. At f737 (900 ms after impact) **two amber arrows circle the cap
  anticlockwise**.
- f700: callout. **"Shows you how."** Sub **"Turn, pull, push, pour. Every move comes from the manual."**

### Scene 9: Knows its limits (f765–885)
- f765: the finger holds the mic and the VoiceGlow rises. **VO user_smell** "There's a burning smell." (f770–808).
- f812: release. The marker fades out (350 ms). **Everything turns to the Danger accent:** the banner border goes
  red, the pill reads **`TECHNICIAN`** (red background, Ink text), the title stays "Check and top up engine oil",
  and a caution line shows **`⚠  burning smell`** in red. The how-to card cross-fades to **"Call a technician"**.
- The answer, with **VO fixy_escalate** (f816, running into the start of scene 10 is fine): **"That's a warning
  sign: burning smell. This one is a job for a technician. Please don't try it yourself."**
- f825: the **TechnicianStrip** grows in between the banner and the card. During f840–875 the card row scrolls
  left by 120 dp so a third card peeks in.
- Camera: push to 1.3× on the lower two-thirds of the phone so the cards read.
- f820: callout, with the accent switched to Danger. **"Knows its limits."** Sub **"A danger sign stops the
  guide. No guessing. It sends you to a pro."** Optional: the `fixy-caution.svg` mascot beside the callout.

### Scene 10: Any brand's error code (f885–960)
- f885: the feed slides sideways to `01_front_loader` (the whole LG washer). **VO user_oe** "My LG washing
  machine shows OE." (f890–950). The banner reads `SAFETY FIRST · 1 OF 2` · **"Washing machine not draining"**,
  the how-to card shows **"Unplug first"**, and the answer (caption only) reads **"Let's find out why the water
  isn't draining. Switch the machine off and unplug it."**
- Right side: five **code chips** fly in from different directions with a 4-frame stagger starting at f890. Each
  chip has an InkRaised fill, a 1 px Amber @ 50% border, radius 14, the brand in Paper 22 px and the code in
  Amber, Roboto Mono 26 px: `LG · OE` · `Samsung · 5C` · `Bosch · E18` · `IFB · dPEr` · `Whirlpool · F9E1`.
  During f920–940 they **converge into one card**: **"Washing machine not draining"** / "1 verified fix".
- f895: headline **"Any brand's error code."** Sub **"Different codes. Same fault. One checked fix."**
- f940: tech label `61 VERIFIED FIXES · 11 APPLIANCES · EXACT CODE LOOKUP`.

### Scene 11: Beyond repairs (f960–1065), three beats with a growing callout list on the right
The callout list grows one line per beat, and the current line is lit in Amber:
**"Takes the remote."** · **"Reminds you."** · **"Remembers you."**
- **a. Remote (f960–995).** The drawer slides in (f960–970) and "Universal remote" gets the selected highlight.
  The drawer closes into the **Universal remote** page with the **AC pad (§4.13)**. The finger taps **+** at
  f978: the temperature rolls 23° → **24°**, the RemoteGlyph arcs ripple, and "Last sent: Temperature 24°"
  updates. SFX: key click + three rising IR blips. Sub: **"Your AC and TV, through the phone's IR blaster."**
- **b. Alerts (f995–1030).** Back on Home, the **notification (§4.14)** drops in from the top of the phone
  (spring). SFX: a gentle two-note chime. Sub: **"Routine checks, as often as the makers' manuals say."**
- **c. Memory (f1030–1065).** A new session's camera screen (the washer feed, dimmed) with the card showing
  Fixy's templated greeting, status "SPEAKING": **"Good evening! Last time we looked at your washing machine
  (error OE) yesterday. Is it working fine now? And what are we fixing today?"** Sub: **"It asks how your last
  repair went."**

### Scene 12: How it works (f1065–1140)
- f1065: the phone slides left, scales to 0.7 and fades to 25%. A diagram builds on the right inside a large
  rounded phone outline (Paper @ 20% stroke), with nodes popping in on a 3-frame stagger from f1068 and links
  drawing in from f1080:
  - Top lane, labelled `SLOW BRAIN · ONCE PER QUESTION`:
    `Mic → Moonshine` → `Verified KB` → `Qwen3-VL-4B` → `Piper voice`
  - Bottom lane, labelled `FAST EYES · EVERY FRAME`: `Camera` → `OpenCV tracker` → `Marker`
  - An Amber arrow labelled `box` runs from `Qwen3-VL-4B` down to `OpenCV tracker`.
  - Lock badge: **"No INTERNET permission"** (a padlock over a crossed-out globe).
  - A light pulse travels the slow lane once, while pulses loop quickly along the fast lane.
- Headline: **"Two speeds."** Sub: **"A slow brain that understands. Fast eyes that keep up."**
- **VO fixy_greeting** (the bookend, f1072 to about f1190): **"Hi, I'm Fixy! Point me at what's broken and tell
  me what's happening."**

### Scene 13: End card (f1140–1200)
- f1140: the diagram collapses into a point that becomes the **amber iris**, and the logo lockup blooms out of it
  (centred, 620 px wide).
- f1152: **"Point. Ask. Fix."** (48 px, amber dots). f1160: **"Fully offline · Runs on iQOO 15"** (24 px Muted).
- f1165: small, near the bottom: **"iQOO City Battles · Hyderabad · 2026"** (18 px Muted).
- Footnote (13 px, Paper @ 38%, bottom 40 px): **"Demo photos: Wikimedia Commons & Flickr (CC BY / CC BY-SA).
  Technician cards are sample data. Waits shortened for this video."**
- f1185: the catchlight twinkles. Hold to f1199. The music resolves on f1200.

---

## 6. Audio

### Voice: use Fixy's **real on-device voice** (Piper `en_US-lessac-medium`)
Render the lines on the laptop with sherpa-onnx (the same engine the app uses). The user's lines use a second
Piper voice, `en_US-amy-medium`.

```bash
python3 -m venv /tmp/tts-venv && /tmp/tts-venv/bin/pip install sherpa-onnx==1.13.8 numpy soundfile
```
Write `scripts/make_vo.py`. For each voice it builds
`sherpa_onnx.OfflineTts(OfflineTtsConfig(model=OfflineTtsModelConfig(vits=OfflineTtsVitsModelConfig(model=f"{d}/{v}.onnx", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data"), num_threads=4), max_num_sentences=1))`
with `d = /home/shiva-ajay/3D/fixlens-models/piper-{v}`. Then `audio = tts.generate(text, sid=0, speed=S)`. Trim
edge silence (threshold 0.01, keep 30 ms), write 16-bit WAVs to `public/audio/vo/<id>.wav`, and write
`public/audio/vo/durations.json` with each line's length. `tools/tts/audition.py` in the repo is a working example.

| id | Voice | Speed | Text (the caption shows the "Caption" column when it differs) | Slot (frames) |
|---|---|---|---|---|
| `user_q1` | amy | 1.0 | How do I check the engine oil in my car? | 288–352 |
| `fixy_filler` | lessac | 1.0 | Let me look. | 364–384 |
| `user_done` (optional) | amy | 1.0 | Done. | 428–440 |
| `fixy_step2` | lessac | 1.05 | Find the dipstick. Its handle is a bright ring or loop, usually yellow or orange. | 470–598 |
| `user_next` (optional) | amy | 1.0 | Next. | 650–662 |
| `user_smell` | amy | 1.0 | There's a burning smell. | 770–808 |
| `fixy_escalate` | lessac | 1.08 | That's a warning sign: burning smell. This one is a job for a technician. Please don't try it yourself. | 816–~945 |
| `user_oe` | amy | 1.0 | My LG washing machine shows O E. (Caption: "…shows OE.") | 890–950 |
| `fixy_greeting` | lessac | 1.0 | Hi, I'm Fixy! Point me at what's broken and tell me what's happening. | 1072–~1190 |

- Never let two voices overlap. If a line overruns its slot, raise its speed (up to 1.15) before moving anything.
  If it still doesn't fit, move the next slot and tell me.
- On-screen text stays **verbatim** even when a line is only shown and not spoken. Fixy's lines come word for
  word from the knowledge base (`app/src/main/assets/kb/fixlens_kb.json`, entries `car_check_engine_oil` and
  `washing_machine_not_draining`) and from `guide/Guide.kt` / `guide/Recall.kt`.
- Answer text streams onto the screen in sync with its VO (words spread evenly over the line's length).

### Sound effects: generate them yourself (numpy → WAV, `scripts/make_sfx.py`), keep them subtle
tap (a 5 ms click with a short high-passed noise burst) · mic-on (a 120 ms rising sine blip, 600 → 900 Hz) ·
whoosh (300 ms of band-passed noise sweeping 400 → 3 kHz, fading in and out) · comet launch (a 200 ms rising
shimmer) · **lock-on** (a 60 Hz thump with 80 ms decay plus a two-note chime, 1320 then 1760 Hz, soft attack) ·
haptic tick (a 3 ms click) · appliance beep (a 2 kHz square wave, 120 ms, low-passed) · warning (two low tones,
330 → 262 Hz) · IR blips (three 40 ms blips rising 1.2 / 1.5 / 1.8 kHz) · notification chime · logo sting
(a soft major-chord swell with a bell).

### Music
If `public/audio/music.mp3` exists (a royalty-free track the user supplies, about 120 BPM, modern, minimal and
warm, with a drop around 15 s for the lock-on), use it: volume 0.35, **ducked to 0.12 under every voice line**
(6-frame ramps), and faded out over the last 20 frames. If it doesn't exist, render without music and say so.

---

## 7. Build plan

1. `npx create-video@latest` (blank TypeScript template) in `/home/shiva-ajay/3D/fixlens-video`. Add
   `@remotion/google-fonts`, `@remotion/media-utils` and `@remotion/transitions`.
2. Copy the assets into `public/`: the photos listed in §3, the logo SVGs, the (optional) mascot SVGs, and
   `design/anim/*.svg` (reference only).
3. Structure:
   ```
   src/
     Root.tsx               // registers FixLensAd (1920x1080, 30 fps, 1200 frames)
     FixLensAd.tsx          // <Sequence> per scene, the audio tracks, captions
     theme.ts               // colours, fonts, the dp helper
     motion.ts              // port of fx/Motion.kt + hash01
     phone/Phone.tsx        // device frame, status bar, gesture bar, camera prop
     phone/CameraFeed.tsx   // photo + handheld transform + imageBoxToScreen()
     app/                   // one file per app component in §4
       CameraScreen.tsx TopBar.tsx ConversationCard.tsx MicButton.tsx StepBanner.tsx
       HowToCard.tsx PartCues.tsx MarkerOverlay.tsx Comet.tsx ThinkingOrb.tsx
       BorderBeam.tsx VoiceGlow.tsx TechnicianStrip.tsx Home.tsx Drawer.tsx RemotePad.tsx Notification.tsx
     scenes/S01Hook.tsx … S13End.tsx
     overlays/Callout.tsx Captions.tsx TouchIndicator.tsx CodeChips.tsx HowItWorks.tsx
   ```
4. Drive the app components with a small **per-scene state timeline** (phase, status label, question, answer
   words shown, banner, how-to cue, marker cue frame, and so on). Keep the scene timings in one `timeline.ts` so
   they are easy to retime.
5. Build and check **the lock-on + comet first** (scene 6), in isolation, against §4.7–4.8. It's the heart of the
   video. Then build the camera screen around it, then the rest.
6. Preview with `npx remotion studio`. Check stills with
   `npx remotion still FixLensAd --frame=<n> out/still-<n>.png` and **open each PNG and look at it** (with the
   Read tool) at these frames at least: 30, 150, 230, 320, 420, 500, 530, 630, 740, 850, 930, 1000, 1045, 1100, 1180.
7. Render: `npx remotion render FixLensAd out/fixlens-40s.mp4 --codec=h264 --crf=18`.

## 8. Done when
- [ ] Exactly 1200 frames, 1920×1080, 30 fps, and the audio is in sync.
- [ ] The UI matches §4 and the Kotlin files: colours by hex, radii, paddings, font sizes and weights, the exact strings.
- [ ] Scene 6's lock-on follows the §4.7 timeline, the comet launches from the orb in the card, and the marker
      stays glued to the part through all of scene 7's movement.
- [ ] Every Fixy line on screen is verbatim from the KB/code. The captions match the audio. No voices overlap.
- [ ] No banned claims (§1). The footnote is on the end card. No third-party logos. The "Sample" tag shows on the
      technician strip.
- [ ] No text on screen for less than ~1 s (except the hook's slam words), and nothing important outside the
      5% safe area.
- [ ] Deterministic: rendering twice gives the same frames (no `Math.random`, no wall-clock time).
- [ ] Report back: the output path, the frames you checked, anything you changed from this plan, and why.
