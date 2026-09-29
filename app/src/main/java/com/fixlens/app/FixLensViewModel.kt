package com.fixlens.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fixlens.alerts.Alert
import com.fixlens.alerts.AlertScheduler
import com.fixlens.alerts.Alerts
import com.fixlens.camera.FrameGrabber
import com.fixlens.guide.ConversationContext
import com.fixlens.guide.FixyPrompts
import com.fixlens.guide.Command
import com.fixlens.guide.Commands
import com.fixlens.guide.Guide
import com.fixlens.guide.Intent
import com.fixlens.guide.GuideState
import com.fixlens.guide.GuideView
import com.fixlens.guide.entry
import com.fixlens.ir.DeviceKind
import com.fixlens.ir.RemoteController
import com.fixlens.ir.RemotePanel
import com.fixlens.ir.RemoteProfile
import com.fixlens.ir.RemoteUi
import com.fixlens.kb.KbRepository
import com.fixlens.kb.KnowledgeBase
import com.fixlens.kb.Retriever
import com.fixlens.guide.MemoryRules
import com.fixlens.guide.Recall
import com.fixlens.kb.KbEntry
import com.fixlens.session.RepairSession
import com.fixlens.session.SessionMemory
import com.fixlens.session.SessionRepository
import com.fixlens.session.TitleSource
import com.fixlens.session.Turn
import com.fixlens.tracking.FlowTracker
import com.fixlens.tracking.MarkerState
import com.fixlens.vision.BoxMapper
import com.fixlens.vision.CoordScale
import com.fixlens.vision.GroundingParser
import com.fixlens.vision.GroundingParser.Grounding
import com.fixlens.vision.ModelBox
import com.fixlens.vision.VlmEngine
import com.fixlens.voice.SpeechChunker
import com.fixlens.voice.SpeechInput
import com.fixlens.voice.SpeechOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

enum class Phase { Loading, Listening, Thinking, Answering, Error }

sealed interface Screen {
    data object Sessions : Screen
    data object Session : Screen
}

/** The home screen's pages, picked in the side drawer. */
enum class HomePage { Repairs, Remote, Alerts }

data class UiState(
    val screen: Screen = Screen.Sessions,
    // Engine (loads once at app start, while the user is on the sessions list)
    val engineReady: Boolean = false,
    val loadingStep: String = "Waking up Fixy",
    val error: String? = null,
    // Sessions list
    val sessions: List<RepairSession> = emptyList(),
    val sessionsLoaded: Boolean = false,
    // Home drawer pages
    val home: HomePage = HomePage.Repairs,
    /** Fixy's latest line about the remote on the home universal remote (there's no conversation card there). */
    val remoteNote: String? = null,
    /** Fixy's reminders for routine checks (alerts/), due ones first. */
    val alerts: List<Alert> = emptyList(),
    /** The alert a notification tap opened, highlighted on the alerts page. */
    val focusAlert: String? = null,
    /** A reminder was just scheduled: the activity asks for the notification permission if it's missing. */
    val askNotifications: Boolean = false,
    // Open session
    val session: RepairSession? = null,
    val phase: Phase = Phase.Loading,
    /** The talk button is held (push-to-talk): only then is audio kept. */
    val talking: Boolean = false,
    /** Released, waiting for the final transcript. */
    val transcribing: Boolean = false,
    /** The keyboard is open for a typed question; the mic is closed meanwhile. */
    val typing: Boolean = false,
    /** The user's words: live while speaking, final once the pause is detected. */
    val question: String = "",
    val questionFinal: Boolean = false,
    val answer: String = "",
    /** Fixy's voice is playing (a filler, an answer or a guide line). */
    val speaking: Boolean = false,
    val timing: String = "",
    /** Parts pointed at so far in this answer (they stream in one by one before the spoken reply). */
    val partsFound: Int = 0,
    /** The guided repair in progress (M4), for the step card; null when there's none. */
    val guide: GuideView? = null,
    /** "Point the camera at the …" when Fixy was asked to point but gave no usable box. */
    val hint: String? = null,
    // Debug overlays, switched over adb (see MainActivity.onNewIntent).
    val debugTestBox: Boolean = false,
    val debugFreeze: Boolean = false,
    val frozen: FrozenKeyframe? = null,
)

/** Debug: a question's keyframe and the raw VLM box, drawn over the preview to verify the mapping and scale. */
data class FrozenKeyframe(
    val path: String,
    val width: Int,
    val height: Int,
    /** 0 for a file keyframe (not from the camera). */
    val analysisWidth: Int,
    val analysisHeight: Int,
    val raw: List<ModelBox>,
    val note: String,
)

class FixLensViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Mic loudness 0..1, kept separate from [state] so the glow can update ~30×/s cheaply. */
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel

    private val tracker = FlowTracker(onReGround = ::onReGroundRequest)
    val frameGrabber = FrameGrabber(onFrame = { ring ->
        tracker.onFrame(ring)
        // Pairing a TV: the camera notices the screen reacting to a test code (ir/ScreenProbe).
        remote.screen.onFrame(ring)
    })
    /** The marker in analysis space, ~30 updates a second (kept out of [state] like [micLevel]). */
    val marker: StateFlow<MarkerState?> = tracker.state
    val analysisSize = frameGrabber.analysisSize
    private val vlm = VlmEngine()
    private val context = ConversationContext(vlm)
    private val filesDir = app.getExternalFilesDir(null)!!
    private val repo = SessionRepository(File(app.filesDir, "sessions"))
    private val speech = SpeechInput(
        sttDir = File(filesDir, "stt"),
        onPartial = ::onPartial,
        onFinal = ::onQuestion,
        onNothingHeard = ::onNothingHeard,
        onLevel = { _micLevel.value = it },
    )
    /** Fixy's voice (Piper). Optional: without its model Fixy answers in text only. */
    private val voice = SpeechOutput(
        dir = File(filesDir, "tts/piper"),
        onSpeaking = { on -> _state.update { it.copy(speaking = on) } },
    )
    /** "Fixy takes the remote": pairing and controlling ACs, TVs, projectors and fans over IR (ir/). */
    private val remoteHost = object : RemoteController.Host {
        override fun reply(userText: String?, answer: String) = remoteReply(userText, answer)
        override fun speak(line: String) = voice.speak(line, voice.epoch())
        override suspend fun identify() = lookAtDevice()
        override suspend fun check(sign: String) = lookYesNo(sign)
        override suspend fun plan(request: String, maxTokens: Int) = lookAndPlan(request, maxTokens)
        override fun onProfile(profile: RemoteProfile?) {
            _state.value.session?.let { s -> updateSession(s.id) { it.copy(remote = profile) } }
        }
    }
    private val remote = RemoteController(app, viewModelScope, remoteHost)
    val remoteUi: StateFlow<RemoteUi> = remote.ui

    /** The session whose greeting has been spoken, so it isn't said twice. */
    @Volatile private var greetedId: String? = null

    /** Incremented per question; output from an older (cancelled) turn is ignored. */
    private val turn = AtomicInteger(0)

    private var coordScale = COORD_SCALE
    private var groundingStyle = FixyPrompts.GroundingStyle.Parts
    private var reGroundJob: Job? = null
    /** Re-grounds used since the last question (capped, so a hopeless target doesn't keep the VLM busy). */
    private var reGrounds = 0
    /** The knowledge base (M4), loaded at start; null until then or if it's missing or invalid. */
    @Volatile private var kb: KnowledgeBase? = null
    @Volatile private var retriever: Retriever? = null
    private var guideState: GuideState = GuideState.Idle
    /** Points at the current step's part, then runs its auto-check. */
    private var guideJob: Job? = null
    /**
     * The earlier repair the greeting asked after (guide/Recall.kt), seen by the model only until the user answers
     * the greeting; null otherwise, so an old car repair never colors a new session about a laptop.
     */
    private var pastRepairs: String? = null
    /**
     * Set while (and after) a "remote not working" guide: the device whose remote the user says is broken. Their
     * "still not working" then starts the device-or-remote test (ir/RemoteController.startRemoteTest).
     */
    private var remoteTrouble: com.fixlens.ir.DeviceKind? = null
    private val alertStore = AlertScheduler.store(app)
    /** A check opened from a reminder before the engine was ready; it starts once the session is ready. */
    private var pendingCheck: KbEntry? = null
    /** "Start the check" tapped (a notification at a cold start) before the KB loaded. */
    private var pendingAlertStart: String? = null
    /** The mic is open on the home remote to hear an AC's pairing beep. */
    private var homeMic = false

    init {
        refreshSessions()
        viewModelScope.launch(Dispatchers.IO) { loadKb() }
        viewModelScope.launch(Dispatchers.IO) { remote.load() }
        speech.monitor = remote.beep::feed
        refreshAlerts()
        // An alert delivered while the app is open shows up at once.
        viewModelScope.launch { AlertScheduler.changes.collect { refreshAlerts() } }
        // Pairing an AC on the home remote: open the mic so its beep is heard (nothing is kept; see SpeechInput).
        viewModelScope.launch { remote.ui.collect { updateHomeMic(it) } }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loadingStep = "Loading speech recognition") }
            if (!speech.load()) {
                fail("Speech model not found on this phone.")
                return@launch
            }
            // The voice loads before the 3 GB VLM, so the greeting can be spoken while the VLM is still loading.
            _state.update { it.copy(loadingStep = "Loading Fixy's voice") }
            if (voice.load()) launch(Dispatchers.Main) { _state.value.session?.let(::greet) }
            _state.update { it.copy(loadingStep = "Loading Fixy's vision model") }
            if (!vlm.load(modelDir = File(filesDir, "models/qwen3-vl-4b"), tmpDir = File(app.cacheDir, "mnn"))) {
                fail("Vision model not found or failed to load.")
                return@launch
            }
            _state.update { it.copy(engineReady = true, phase = Phase.Listening) }
            launch(Dispatchers.Main) { onSessionScreenReady() }
        }
    }

    // ---- Sessions list ----

    fun sessionDir(id: String): File = repo.dir(id)

    /** A new session opens with Fixy's greeting, which may ask after the latest earlier repair. */
    fun newSession() {
        val greeting = Recall.greeting(Recall.candidates(_state.value.sessions, currentId = ""))
        open(repo.create().copy(greeting = greeting.text, followUpOf = greeting.followUpOf))
    }

    fun openSession(id: String) {
        viewModelScope.launch {
            repo.load(id)?.let(::open)
        }
    }

    private fun open(session: RepairSession) {
        frameGrabber.clear()
        clearMarker()
        endGuide()
        remoteTrouble = null
        stopHomeMic()
        remote.cameraOn = true
        remote.attach(session.remote)
        val last = session.turns.lastOrNull()
        pastRepairs = session.followUpOf?.takeIf { session.turns.isEmpty() }
            ?.let { id -> Recall.pastRepairs(_state.value.sessions.filter { it.id == id }) }
        Log.i(TAG, "Open ${session.id}: greeting \"${session.greeting}\", past repairs:\n${pastRepairs ?: "(none)"}")
        _state.update {
            it.copy(
                screen = Screen.Session,
                session = session,
                phase = if (it.engineReady) Phase.Listening else it.phase,
                question = last?.question.orEmpty(),
                questionFinal = true,
                answer = last?.answer ?: session.greeting.orEmpty(),
                timing = if (last != null) "Earlier" else "",
                hint = null,
                frozen = null,
                talking = false,
                transcribing = false,
            )
        }
        voice.stop()
        greet(session)
        onSessionScreenReady()
    }

    /** Says the greeting of a session that has no turns yet, once per opening (if the voice is ready). */
    private fun greet(session: RepairSession) {
        val greeting = session.greeting ?: return
        val s = _state.value
        if (!voice.ready || session.turns.isNotEmpty() || greetedId == session.id) return
        if (s.screen != Screen.Session || s.session?.id != session.id || s.question.isNotEmpty()) return
        greetedId = session.id
        voice.speak(greeting, voice.epoch())
    }

    /** Starts listening and warms the model's memory once both the screen and the engine are ready. */
    private fun onSessionScreenReady() {
        val s = _state.value
        if (s.screen != Screen.Session || !s.engineReady) return
        val session = s.session ?: return
        if (!s.typing) speech.start()
        viewModelScope.launch { runCatching { context.prewarm(session, pastRepairs) }.onFailure { Log.e(TAG, "Prewarm failed", it) } }
        pendingCheck?.let { entry ->
            pendingCheck = null
            showGuide(checkQuestion(entry), Guide.start(entry))
        }
    }

    fun closeSession() {
        cancelTurn()
        greetedId = null
        pendingCheck = null
        remote.detach()
        speech.stop()
        frameGrabber.clear()
        clearMarker()
        endGuide()
        _state.update {
            it.copy(
                screen = Screen.Sessions, session = null, question = "", answer = "", timing = "", questionFinal = false,
                hint = null, frozen = null, typing = false, talking = false, transcribing = false,
            )
        }
        refreshSessions()
    }

    fun renameSession(id: String, title: String) {
        val clean = title.trim().take(40)
        if (clean.isEmpty()) return
        updateSession(id) { it.copy(title = clean, titleSource = TitleSource.User) }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            context.invalidate(id)
            refreshSessions()
        }
    }

    private fun refreshSessions() {
        viewModelScope.launch {
            val list = repo.list()
            _state.update { it.copy(sessions = list, sessionsLoaded = true) }
        }
    }

    // ---- Home drawer: universal remote and alerts ----

    fun showHome(page: HomePage) {
        val from = _state.value.home
        if (from == HomePage.Remote && page != HomePage.Remote) remote.detach()
        // The home remote has no camera: pairing waits for the user's "It responded" (or an AC's beep).
        if (page == HomePage.Remote) remote.cameraOn = false
        _state.update { it.copy(home = page, remoteNote = null, focusAlert = if (page == HomePage.Alerts) it.focusAlert else null) }
        if (page == HomePage.Alerts) refreshAlerts()
    }

    /** Opens a saved remote's pad. */
    fun homeUseRemote(profile: RemoteProfile) {
        remote.cameraOn = false
        remote.attach(profile)
        _state.update { it.copy(remoteNote = null) }
    }

    /** Back to the saved remotes (the device stays saved). */
    fun homeCloseRemote() {
        remote.detach()
        _state.update { it.copy(remoteNote = null) }
    }

    /** "Add a remote": the device kind is tapped, so no camera look; the brand picker opens. */
    fun homeAddRemote(kind: DeviceKind) {
        remote.cameraOn = false
        _state.update { it.copy(remoteNote = null) }
        remote.chooseDevice(kind)
    }

    fun homeRenameRemote(profile: RemoteProfile, name: String) = remote.renameSaved(profile, name)
    fun homeForgetRemote(profile: RemoteProfile) = remote.forgetSaved(profile)

    private fun updateHomeMic(ui: RemoteUi) {
        val s = _state.value
        val pairingAc = s.screen == Screen.Sessions && s.home == HomePage.Remote &&
            ((ui.panel as? RemotePanel.Pair)?.kind == DeviceKind.Ac)
        if (pairingAc && !homeMic) {
            homeMic = true
            speech.start()
        } else if (!pairingAc) {
            stopHomeMic()
        }
    }

    private fun stopHomeMic() {
        if (!homeMic) return
        homeMic = false
        if (_state.value.screen == Screen.Sessions) speech.stop()
    }

    fun refreshAlerts() {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { alertStore.all() }
            _state.update { it.copy(alerts = Alerts.ordered(all)) }
        }
    }

    /** A finished routine check: schedule the next one, as the KB entry says. */
    private fun scheduleReminder(entry: KbEntry) {
        val session = _state.value.session
        val alert = Alerts.plan(entry, System.currentTimeMillis(), kb?.version, session?.id, session?.title) ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            val replaced = withContext(Dispatchers.IO) {
                val before = alertStore.all().filter { it.entryId == entry.id }
                alertStore.edit { Alerts.add(it, alert) }
                before
            }
            replaced.forEach { AlertScheduler.disarm(app, it.id) }
            AlertScheduler.arm(app, alert)
            Log.i(TAG, "Reminder scheduled by Fixy: ${entry.id} in ${entry.remind?.afterDays} days (kb ${kb?.version})")
            refreshAlerts()
            if (!AlertScheduler.notificationsAllowed(app)) _state.update { it.copy(askNotifications = true) }
        }
    }

    fun notificationsAsked() = _state.update { it.copy(askNotifications = false) }

    /** Cancels an upcoming reminder, or clears one that's due. */
    fun dismissAlert(id: String) {
        val app = getApplication<Application>()
        AlertScheduler.disarm(app, id)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { alertStore.edit { list -> list.filterNot { it.id == id } } }
            refreshAlerts()
        }
    }

    /** Demo: the reminder fires in a few seconds instead of in days, so the notification can be shown. */
    fun testAlert(id: String) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            var armed: Alert? = null
            withContext(Dispatchers.IO) {
                alertStore.edit { list ->
                    list.map { if (it.id == id) it.copy(dueAt = System.currentTimeMillis() + TEST_ALERT_MS, delivered = false).also { a -> armed = a } else it }
                }
            }
            armed?.let { AlertScheduler.arm(app, it) }
            refreshAlerts()
        }
    }

    /** "Start the check" on a reminder: a new session opens on the entry's guide (safety lines first). */
    fun startAlertCheck(id: String) {
        if (kb == null) {
            pendingAlertStart = id
            return
        }
        val alert = _state.value.alerts.firstOrNull { it.id == id } ?: alertStore.get(id) ?: return
        val entry = kb?.entries?.firstOrNull { it.id == alert.entryId } ?: run {
            Log.w(TAG, "Alert $id: KB entry ${alert.entryId} not found")
            return
        }
        AlertScheduler.disarm(getApplication(), id)
        viewModelScope.launch(Dispatchers.IO) { alertStore.edit { list -> list.filterNot { it.id == id } } }
        _state.update { it.copy(home = HomePage.Repairs, focusAlert = null) }
        remote.detach()
        val memory = SessionMemory(appliance = MemoryRules.applianceName(entry.appliance))
        open(repo.create().copy(memory = memory))
        if (_state.value.engineReady) showGuide(checkQuestion(entry), Guide.start(entry)) else pendingCheck = entry
    }

    /** A notification tap: the alerts page with that alert, or ([start]) straight into its check. */
    fun openAlert(id: String, start: Boolean) {
        if (start) {
            refreshAlerts()
            startAlertCheck(id)
            return
        }
        if (_state.value.screen == Screen.Session) closeSession()
        showHome(HomePage.Alerts)
        _state.update { it.copy(focusAlert = id) }
    }

    /** Debug (`--es remind <entry id> --ei remindsec N`): schedules an entry's reminder N seconds from now. */
    fun debugRemind(entryId: String, seconds: Int = 5, title: String? = null, body: String? = null) {
        val entry = kb?.entries?.firstOrNull { it.id == entryId }
        // A demo alert may carry its own title and text (`--es alerttitle`, `--es alertbody`); it still opens [entry].
        val alert = entry?.let { Alerts.plan(it.copy(remind = it.remind ?: com.fixlens.kb.KbRemind(7, "")), System.currentTimeMillis(), kb?.version, null, null) }
            ?.let { a -> a.copy(title = title ?: a.title, body = body ?: a.body) }
            ?.copy(dueAt = System.currentTimeMillis() + seconds * 1000L)
        if (alert == null) {
            Log.w(TAG, "Debug remind: no KB entry $entryId with a remind")
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { alertStore.edit { Alerts.add(it, alert) } }
            AlertScheduler.arm(getApplication(), alert)
            refreshAlerts()
        }
    }

    private fun checkQuestion(entry: KbEntry) = "Reminder: ${entry.title.replaceFirstChar { it.lowercase() }}"

    // ---- Conversation ----

    /** Push-to-talk: the mic button went down. A new question barges in on one still being answered. */
    fun startTalking() {
        val s = _state.value
        if (!s.engineReady || s.screen != Screen.Session || s.typing) return
        // Barge-in: the mic cuts Fixy off, also after the answer is generated and only the voice is still going.
        voice.stop()
        if (s.phase == Phase.Thinking || s.phase == Phase.Answering) cancelTurn()
        speech.press()
        _state.update {
            it.copy(
                talking = true, transcribing = false, phase = Phase.Listening,
                question = "", questionFinal = false, answer = "", timing = "", hint = null,
            )
        }
    }

    /** Push-to-talk: the mic button came up; the take is transcribed now. */
    fun stopTalking() {
        if (!_state.value.talking) return
        speech.release()
        _state.update { it.copy(talking = false, transcribing = true) }
    }

    /** Opens or closes the keyboard input. The mic is closed while typing, so speech can't cut in. */
    fun setTyping(open: Boolean) {
        if (_state.value.typing == open) return
        // A take in progress is dropped, and its half-heard words aren't left on the card.
        _state.update {
            it.copy(
                typing = open, talking = false, transcribing = false,
                question = if (open && !it.questionFinal) "" else it.question,
            )
        }
        if (!_state.value.engineReady || _state.value.screen != Screen.Session) return
        if (open) speech.stop() else speech.start()
    }

    /** A typed question: goes through the same turn as a spoken one, then returns to voice. */
    fun askTyped(text: String) {
        val question = text.trim()
        val s = _state.value
        if (question.isEmpty() || !s.engineReady || s.screen != Screen.Session) return
        setTyping(false)
        ask(question)
    }

    /**
     * Debug hook (see MainActivity.onNewIntent): a typed question, as if spoken. `point: <phrase>` forces a
     * grounding target; [imagePath] replaces the camera frame with an image file (nothing is tracked then).
     */
    fun debugAsk(text: String, imagePath: String? = null) {
        viewModelScope.launch {
            delay(DEBUG_CAMERA_SETTLE_MS) // the intent pauses/resumes the activity; let the camera expose again
            if (_state.value.screen != Screen.Session || !_state.value.engineReady) return@launch
            val target = if (text.startsWith("point:", ignoreCase = true)) text.substringAfter(':').trim() else null
            ask(if (target != null) "Where is the $target?" else text, target, imagePath?.let(::File))
        }
    }

    /** Debug hook: overlays and grounding settings (null leaves a setting as it is). */
    fun debugSettings(testBox: Boolean?, freeze: Boolean?, grounding: String?, coords: String?) {
        grounding?.let { g -> groundingStyle = FixyPrompts.GroundingStyle.entries.firstOrNull { it.name.equals(g, true) } ?: groundingStyle }
        coords?.let { coordScale = if (it.startsWith("px", true)) CoordScale.ABSOLUTE_PIXELS else CoordScale.NORMALIZED_1000 }
        _state.update {
            it.copy(
                debugTestBox = testBox ?: it.debugTestBox,
                debugFreeze = freeze ?: it.debugFreeze,
                frozen = if (freeze == false) null else it.frozen,
            )
        }
        Log.i(TAG, "Debug: testBox=${_state.value.debugTestBox} freeze=${_state.value.debugFreeze} grounding=$groundingStyle coords=$coordScale")
    }

    /** Debug hook: Fixy says [text] (cut and spoken like an answer; the log shows each piece's timing). */
    fun debugSay(text: String) {
        viewModelScope.launch {
            delay(DEBUG_CAMERA_SETTLE_MS) // the intent pauses/resumes the activity
            say(text)
        }
    }

    /** Debug hook: voice speed. */
    fun debugVoice(speed: Float) = voice.configure(speed)

    /**
     * Debug hooks for the IR remote (MainActivity): [info] logs the IR hardware and catalog, [fake] pretends to
     * send (a phone without IR), [send] sends one code (`tv/LG/mute`, `ac/LG/cool 24`), [pair] opens pairing
     * (`tv:LG`), [take] runs "take the remote" as if said.
     */
    fun debugRemote(info: Boolean, fake: Boolean?, send: String?, pair: String?, take: String?, press: String? = null) {
        viewModelScope.launch {
            delay(DEBUG_CAMERA_SETTLE_MS)
            fake?.let(remote::setFake)
            if (info || fake != null) Log.i(TAG, "IR debug: ${remote.describe()}")
            send?.let(remote::debugSend)
            pair?.let(remote::debugPair)
            press?.let(remote::debugPress)
            take?.let { if (_state.value.screen == Screen.Session) ask(it) }
        }
    }

    // ---- Remote (UI actions; see ir/RemoteController) ----

    fun remoteConfirmBrand() = remote.confirmBrand()
    fun remoteChangeBrand() = remote.changeBrand()
    fun remotePickBrand(brand: String) = remote.ui.value.picker?.let { remote.pickBrand(brand, it.kind) }
    fun remotePickerKind(kind: com.fixlens.ir.DeviceKind) = remote.pickerKind(kind)
    fun remoteClosePicker() = remote.closePicker()
    fun remoteSendTest() = remote.sendTest()
    fun remoteAnswer(responded: Boolean) = remote.answer(responded)
    fun remoteTryCommon() = remote.tryCommonCodes()
    fun remoteStartScan() = remote.startScan()
    fun remoteSameDevice(same: Boolean) = remote.sameDevice(same)
    fun remoteAimReady() = remote.aimReady()
    fun remoteAimAnswer(responded: Boolean) = remote.aimAnswer(responded)
    fun remotePauseScan() = remote.pauseScan()
    fun remoteOneByOne() = remote.retryOneByOne()
    fun remoteVolumeKeyUsable(up: Boolean): Boolean = remote.volumeKeyUsable(up)
    fun remoteCancel() = remote.cancel()
    fun remoteRelease() = remote.release(null)
    fun remotePairAgain() = remote.pairAgain()
    fun remotePad(open: Boolean) = remote.setPad(open)
    fun remoteCommand(command: com.fixlens.ir.RemoteCommand) = remote.run(command, null)

    /** Volume keys answer "Did it respond?" while pairing (up = yes). True if the key was used. */
    fun remoteVolumeKey(up: Boolean): Boolean = remote.volumeKey(up)

    /** Fixy's line about the remote in the conversation card, spoken; a user command is saved as a turn. */
    private fun remoteReply(userText: String?, answer: String) {
        val s = _state.value
        if (s.screen == Screen.Sessions) {
            // The home remote: Fixy's line shows under the page title and is spoken.
            if (s.home == HomePage.Remote) {
                _state.update { it.copy(remoteNote = answer) }
                voice.speak(answer, voice.epoch())
            }
            return
        }
        if (s.screen != Screen.Session) return
        if (userText != null) {
            cancelTurn()
            clearMarker()
        }
        _state.update {
            it.copy(
                question = userText ?: it.question, questionFinal = true, answer = answer,
                timing = "Remote · on-device", phase = Phase.Listening, hint = null,
            )
        }
        voice.speak(answer, voice.epoch())
        if (userText != null) s.session?.let { saveTurn(it.id, userText, answer, null, 0) }
    }

    /** One VLM look for the remote: which device and brand is in view (a rolled-back side request). */
    private suspend fun lookAtDevice(): RemoteController.Identified? {
        val session = _state.value.session ?: return null
        val start = System.currentTimeMillis()
        val keyframe = frameGrabber.captureKeyframe(File(getApplication<Application>().cacheDir, "remote.jpg")) ?: return null
        val out = StringBuilder()
        val result = context.side(session, keyframe.file.absolutePath, FixyPrompts.IDENTIFY_DEVICE, IDENTIFY_MAX_TOKENS, wait = true) {
            out.append(it)
        }
        val seen = FixyPrompts.parseDevice(out.toString())
        Log.i(TAG, "Remote identify: \"${out.toString().trim()}\" → $seen in ${System.currentTimeMillis() - start} ms (${result?.stats})")
        return seen?.let { RemoteController.Identified(com.fixlens.ir.DeviceKind.of(it.first), it.second) }
    }

    /** One step of the remote agent: the VLM sees the current frame and the agent's prompt; its raw reply. */
    private suspend fun lookAndPlan(request: String, maxTokens: Int): String? {
        val session = _state.value.session ?: return null
        val keyframe = frameGrabber.captureKeyframe(File(getApplication<Application>().cacheDir, "remote_plan.jpg")) ?: return null
        val out = StringBuilder()
        val result = context.side(session, keyframe.file.absolutePath, request, maxTokens, wait = true) {
            out.append(it)
        } ?: return null
        Log.i(TAG, "Remote agent step: ${result.stats}")
        return out.toString()
    }

    /** One VLM yes/no look for the remote's checks ("the screen shows a picture"). */
    private suspend fun lookYesNo(sign: String): Boolean? {
        val session = _state.value.session ?: return null
        val keyframe = frameGrabber.captureKeyframe(File(getApplication<Application>().cacheDir, "remote_check.jpg")) ?: return null
        val out = StringBuilder()
        val result = context.side(session, keyframe.file.absolutePath, FixyPrompts.verify(sign), VERIFY_MAX_TOKENS, wait = true) {
            out.append(it)
        } ?: return null
        val answer = out.toString().trim().lowercase()
        Log.i(TAG, "Remote check \"$sign\": \"$answer\" (${result.stats})")
        return when {
            answer.startsWith("yes") -> true
            answer.startsWith("no") -> false
            else -> null
        }
    }

    /** Live caption while the button is held (STT thread). */
    private fun onPartial(text: String) {
        val s = _state.value
        if (s.screen != Screen.Session || s.typing || !s.talking) return
        _state.update { it.copy(question = text, questionFinal = false) }
    }

    /** The final transcript of a take (STT thread). */
    private fun onQuestion(text: String) {
        val s = _state.value
        if (s.screen != Screen.Session || s.typing) return
        _state.update { it.copy(talking = false, transcribing = false) }
        ask(text)
    }

    /** A take with no speech in it (a tap, or only background noise): nothing is asked. */
    private fun onNothingHeard() {
        _state.update {
            it.copy(
                talking = false, transcribing = false, hint = HOLD_HINT,
                question = if (it.questionFinal) it.question else "",
            )
        }
        viewModelScope.launch {
            delay(HINT_MS)
            _state.update { if (it.hint == HOLD_HINT) it.copy(hint = null) else it }
        }
    }

    /**
     * One question turn: grab the keyframe, stream the reply through [GroundingParser], seed the tracker the
     * moment the box line is parsed (before the text finishes), and stream the rest into the answer card.
     * [target] is the phrase to point at (debug `point:` now, KB steps in M4); [image] stands in for the camera.
     */
    private fun ask(question: String, target: String? = null, image: File? = null) {
        val session = _state.value.session ?: return
        // The user's remote doesn't work and the guide's fix didn't help ("still not working", "diagnose it"): test
        // the device with Fixy's own remote, which tells whether the device or the user's remote is at fault.
        val trouble = remoteTrouble
        if (target == null && image == null && trouble != null && remote.ui.value.panel == null &&
            com.fixlens.ir.RemoteCommands.stillBroken(question)
        ) {
            endGuide()
            remoteTrouble = null
            remote.startRemoteTest(trouble, question)
            return
        }
        // Remote words first: a pairing answer, a brand, "take the remote", "set it to 24" (no VLM needed).
        if (target == null && image == null && remote.handle(question)) return
        // A KB match starts a guided repair, and "done", "back"… drive it: the words come from the KB, not the VLM.
        if (target == null && image == null && handleGuide(question)) return
        // Small talk and "what do you see?" are answered as such: no pointing, and small talk needs no picture.
        val intent = if (target != null || image != null) Intent.Repair else Intent.of(question)
        // A follow-up question during a guided step points at that step's part.
        val pointTarget = target ?: Guide.instruction(guideState)?.target
        val instruction = when (intent) {
            Intent.Chat -> FixyPrompts.CHAT_TURN
            Intent.Look -> FixyPrompts.LOOK_TURN
            Intent.Repair -> FixyPrompts.grounding(pointTarget, groundingStyle)
        }
        cancelTurn()
        clearMarker()
        reGrounds = 0
        val id = turn.incrementAndGet()
        // Fixy says "Let me look." at once, covering the seconds before the VLM's first words (not for "hi").
        val spoken = voice.epoch()
        if (intent != Intent.Chat) voice.filler(spoken)
        val start = System.currentTimeMillis()
        _state.update {
            it.copy(
                question = question, questionFinal = true, answer = "", timing = "", phase = Phase.Thinking,
                hint = null, frozen = null, partsFound = 0,
            )
        }

        viewModelScope.launch {
            val file = File(repo.dir(session.id).apply { mkdirs() }, "kf_${System.currentTimeMillis()}.jpg")
            val keyframe = when {
                image != null -> withContext(Dispatchers.IO) { FrameGrabber.keyframeFromFile(image, file) }
                intent == Intent.Chat -> null
                else -> frameGrabber.captureKeyframe(file)
            }
            val keyframeMs = System.currentTimeMillis() - start
            var firstPartMs = -1L
            var allPartsMs = -1L
            var firstTokenMs = -1L
            var tracked = 0
            val chunker = SpeechChunker()
            val parser = GroundingParser(
                onGrounding = { g ->
                    allPartsMs = System.currentTimeMillis() - start
                    if (intent == Intent.Repair) onGrounding(g, keyframe, pointTarget, id)
                },
                onText = text@{ chunk ->
                    if (turn.get() != id) return@text
                    if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis() - start
                    _state.update { it.copy(phase = Phase.Answering, answer = it.answer + chunk) }
                    // Each clause goes to the voice as soon as it's complete, while the rest is still generating.
                    chunker.feed(chunk).forEach { voice.say(it, spoken) }
                },
                // Each part goes to the tracker as soon as it's parsed, so markers appear one by one.
                onTarget = { raw ->
                    if (firstPartMs < 0) firstPartMs = System.currentTimeMillis() - start
                    if (trackPart(raw, keyframe, pointTarget, id, first = tracked == 0)) tracked++
                    if (turn.get() == id) _state.update { it.copy(partsFound = it.partsFound + 1) }
                },
            )
            val result = runCatching {
                context.ask(session, pastRepairs, keyframe?.file?.absolutePath, question, instruction) {
                    parser.feed(it)
                }
            }.onFailure { Log.e(TAG, "VLM failed", it) }.getOrNull()
            parser.finish()
            chunker.flush()?.let { voice.say(it, spoken) }
            val total = System.currentTimeMillis() - start
            Log.i(
                TAG,
                "Turn $id $intent \"$question\": keyframe $keyframeMs ms, first part $firstPartMs ms, all parts $allPartsMs ms " +
                    "(${summary(parser.grounding)}), first word $firstTokenMs ms, total $total ms, ${result?.stats}",
            )

            // The saved answer (history, titles, replays) never contains the box line.
            val finalAnswer = parser.text.trim()
            if (result == null || result.cancelled || finalAnswer.isEmpty()) {
                keyframe?.file?.delete()
                if (turn.get() == id) {
                    _state.update { it.copy(phase = Phase.Listening, answer = SORRY) }
                    voice.speak(SORRY, spoken)
                }
                return@launch
            }
            // The model finished this turn, so it is in the model's memory: store it even if the screen moved on.
            saveTurn(session.id, question, finalAnswer, keyframe?.file?.name, total)
            if (turn.get() != id) return@launch
            _state.update {
                it.copy(
                    phase = Phase.Listening,
                    answer = finalAnswer,
                    timing = "Answered in %.1f s · on-device".format(total / 1000.0),
                )
            }
        }
    }

    private fun saveTurn(sessionId: String, question: String, answer: String, keyframe: String?, ms: Long) {
        // The first reply to the greeting's "is it working fine now?" is about that earlier repair, not this one.
        val open = _state.value.session?.takeIf { it.id == sessionId }
        val followUpOf = open?.followUpOf?.takeIf { open.turns.isEmpty() }
        followUpOf?.let { id ->
            MemoryRules.followUpOutcome(question)?.let { outcome ->
                Log.i(TAG, "Follow-up: \"$question\" marks $id as $outcome")
                updateSession(id, touch = false) { it.copy(memory = it.memory.copy(outcome = outcome)) }
            }
        }
        val earlier = followUpOf?.let { id -> _state.value.sessions.firstOrNull { it.id == id }?.memory }
        updateSession(sessionId) { s ->
            val memory = if (followUpOf != null) MemoryRules.afterFollowUp(s.memory, question, earlier)
            else MemoryRules.update(s.memory, question, answer)
            val keywordTitle = MemoryRules.keywordTitle(memory)
            val retitle = s.titleSource <= TitleSource.Keyword && keywordTitle != null && keywordTitle != s.title
            s.copy(
                turns = s.turns + Turn(s.turns.size + 1, question, answer, keyframe, System.currentTimeMillis(), ms),
                memory = memory,
                title = if (retitle) keywordTitle!! else s.title,
                titleSource = if (retitle) TitleSource.Keyword else s.titleSource,
            )
        }?.let { saved ->
            // The greeting has been answered: the earlier repair leaves the model's view, rebuilt now while it's idle
            // and before the title request reads the conversation.
            val dropPast = followUpOf != null && pastRepairs != null
            if (dropPast) pastRepairs = null
            // A reply about the earlier repair says nothing about this session's topic, so it doesn't count.
            val topicTurns = saved.turns.size - if (MemoryRules.openedWithFollowUp(saved)) 1 else 0
            val title = saved.titleSource <= TitleSource.Keyword && topicTurns in TITLE_AT_TURNS
            viewModelScope.launch {
                if (dropPast) runCatching { context.prewarm(saved, null) }.onFailure { Log.e(TAG, "Prewarm failed", it) }
                if (title) requestTitle(saved)
            }
        }
    }

    /** Model box → tracker target in analysis space; null if the box is unusable. */
    private fun toTarget(raw: ModelBox, keyframe: FrameGrabber.Keyframe, fallbackLabel: String?): FlowTracker.Target? {
        val box = BoxMapper.modelToKeyframe(raw, coordScale, keyframe.width, keyframe.height) ?: return null
        return FlowTracker.Target(
            BoxMapper.keyframeToAnalysis(box, keyframe.width, keyframe.height, keyframe.analysisWidth, keyframe.analysisHeight),
            (raw.label ?: fallbackLabel ?: "here").take(MAX_LABEL),
            raw.isPoint,
        )
    }

    /**
     * VLM thread, the moment one part is parsed: the first part of a turn starts a new marker group, the rest
     * join it. Returns whether it went to the tracker.
     */
    private fun trackPart(raw: ModelBox, keyframe: FrameGrabber.Keyframe?, target: String?, id: Int, first: Boolean): Boolean {
        if (turn.get() != id || keyframe == null || keyframe.timestampNs == 0L) return false // file images aren't tracked
        val part = toTarget(raw, keyframe, target) ?: return false
        val seed = FlowTracker.Seed(listOf(part), keyframe.timestampNs) { turn.get() == id }
        if (first) tracker.seed(seed) else tracker.add(seed)
        return true
    }

    /** VLM thread, once the pointing JSON is complete: log it, feed the debug overlay, hint if nothing was found. */
    private fun onGrounding(g: Grounding, keyframe: FrameGrabber.Keyframe?, target: String?, id: Int) {
        if (turn.get() != id) return
        val raws = (g as? Grounding.Targets)?.boxes.orEmpty()
        Log.i(TAG, "Grounding: ${summary(g)} ${raws.joinToString { "${it.label}@[${it.x1.toInt()},${it.y1.toInt()},${it.x2.toInt()},${it.y2.toInt()}]" }} (${keyframe?.width}x${keyframe?.height}, $coordScale)")
        if (keyframe != null && _state.value.debugFreeze) {
            _state.update {
                it.copy(
                    frozen = FrozenKeyframe(
                        keyframe.file.absolutePath, keyframe.width, keyframe.height,
                        keyframe.analysisWidth, keyframe.analysisHeight, raws, summary(g),
                    ),
                )
            }
        }
        if (raws.isEmpty() && target != null) _state.update { it.copy(hint = pointHint(target)) }
    }

    private fun summary(g: Grounding?) = when (g) {
        is Grounding.Targets -> "${g.boxes.size} part(s)"
        null -> "no JSON"
        else -> g.toString()
    }

    /**
     * The tracker lost the parts for over a second (analysis thread). Silently asks the VLM where they are
     * now, as a side request that is rolled back from the KV cache. Only while Fixy is idle and the user
     * isn't talking, one at a time, and at most [MAX_REGROUNDS] per question.
     */
    private fun onReGroundRequest(labels: List<String>) {
        viewModelScope.launch {
            val s = _state.value
            val session = s.session ?: return@launch
            if (s.screen != Screen.Session || s.phase != Phase.Listening || !s.questionFinal) return@launch
            if (reGroundJob?.isActive == true || reGrounds >= MAX_REGROUNDS) return@launch
            reGrounds++
            val id = turn.get()
            val attempt = reGrounds
            reGroundJob = launch {
                val start = System.currentTimeMillis()
                val keyframe = frameGrabber.captureKeyframe(File(getApplication<Application>().cacheDir, "reground.jpg"))
                    ?: return@launch
                // Stop generating once the JSON is in; the rest is never used.
                val parser = GroundingParser(onGrounding = { vlm.cancel() }, onText = {})
                val result = runCatching {
                    context.locate(session, keyframe.file.absolutePath, labels) { parser.feed(it) }
                }.onFailure { Log.e(TAG, "Re-ground failed", it) }.getOrNull()
                parser.finish()
                val parts = (parser.grounding as? Grounding.Targets)?.boxes.orEmpty()
                    .mapNotNull { toTarget(it, keyframe, labels.firstOrNull()) }
                Log.i(
                    TAG,
                    "Re-ground $attempt/$MAX_REGROUNDS $labels: ${summary(parser.grounding)}, ${parts.size} usable in " +
                        "${System.currentTimeMillis() - start} ms (${result?.stats ?: "skipped, VLM busy"})",
                )
                if (parts.isEmpty() || turn.get() != id) return@launch
                tracker.seed(FlowTracker.Seed(parts, keyframe.timestampNs, quiet = true) { turn.get() == id })
            }
        }
    }

    // ---- Guided repair (M4): a KB entry's safety lines, then its steps, each pointing at the step's part ----

    private fun loadKb() {
        val app = getApplication<Application>()
        val loaded = runCatching { KbRepository.load(app) }
            .onFailure { Log.e(TAG, "KB failed to load", it) }.getOrNull() ?: return
        val problems = KbRepository.problems(loaded)
        if (problems.isNotEmpty()) {
            val message = "KB ${loaded.version} is invalid:\n" + problems.joinToString("\n")
            // CLAUDE.md §7: fail loudly in debug builds.
            check(app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) { message }
            Log.e(TAG, message)
            return
        }
        kb = loaded
        retriever = Retriever(loaded)
        Log.i(TAG, "KB ${loaded.version}: ${loaded.entries.size} entries")
        pendingAlertStart?.let { id ->
            pendingAlertStart = null
            viewModelScope.launch(Dispatchers.Main) { startAlertCheck(id) }
        }
    }

    /**
     * Main thread. A command ("done", "back"…) or a danger sign for the guide in progress, or a question that
     * matches a KB entry (which starts its guide). Returns false for anything else: a normal question, which
     * during a guide the VLM answers as a follow-up, pointing at the current step's part.
     */
    private fun handleGuide(text: String): Boolean {
        val entry = guideState.entry()
        if (entry != null && guideState !is GuideState.Done) {
            Commands.parse(text)?.let { command ->
                val (next, reminder) = Guide.onCommand(guideState, command)
                when {
                    next == GuideState.Idle -> {
                        endGuide()
                        clearMarker()
                        _state.update { it.copy(question = text, questionFinal = true, answer = GUIDE_STOPPED, timing = "") }
                        say(GUIDE_STOPPED)
                    }
                    reminder != null -> {
                        _state.update { it.copy(question = text, questionFinal = true, hint = reminder) }
                        say(reminder)
                    }
                    else -> showGuide(text, next)
                }
                return true
            }
            Guide.escalation(entry, text)?.let { sign ->
                showGuide(text, GuideState.Escalate(entry, sign))
                return true
            }
        }
        // "Not cooling" is an AC or a fridge: search the appliance the user named, or the session's.
        val (appliance, brand) = MemoryRules.kbContext(text, _state.value.session?.memory)
        val match = retriever?.find(text, appliance, brand) as? Retriever.Match.Found ?: return false
        // Asking about the repair already in progress is a follow-up, not a restart.
        if (match.entry.id == entry?.id && guideState !is GuideState.Done) return false
        Log.i(TAG, "KB match ${match.entry.id} (stage ${match.stage}: ${match.why}; appliance $appliance, brand $brand), kb ${kb?.version}")
        // "My TV remote isn't working": with an IR blaster, Fixy tests the device with its own remote right away,
        // which tells whether the device or the user's remote is at fault (the battery tips come with the verdict).
        val remoteKind = deviceOf(match.entry.appliance)
        if (match.entry.id.endsWith("remote_not_working") && remoteKind != null && remote.ui.value.available) {
            endGuide()
            remoteTrouble = null
            Log.i(TAG, "Remote not working: testing the ${remoteKind.id} with Fixy's remote instead of ${match.entry.id}")
            remote.startRemoteTest(remoteKind, text)
            return true
        }
        showGuide(text, Guide.start(match.entry))
        return true
    }

    /** Shows a guide state: the KB's words verbatim, then points at the step's part and starts its auto-check. */
    private fun showGuide(question: String, state: GuideState) {
        cancelTurn()
        clearMarker()
        guideJob?.cancel()
        guideState = state
        // A "remote not working" repair: if its fix doesn't help, Fixy can test the device with its own remote.
        state.entry()?.let { e -> remoteTrouble = if (e.id.endsWith("remote_not_working")) deviceOf(e.appliance) else null }
        val ins = Guide.instruction(state) ?: return endGuide()
        // A routine check was finished: Fixy schedules the next one (the Done line says when).
        if (state is GuideState.Done) scheduleReminder(state.entry)
        _state.update {
            it.copy(
                question = question, questionFinal = true, answer = ins.say, timing = "Repair guide · ${kb?.version}",
                phase = Phase.Listening, hint = null, frozen = null, partsFound = 0, guide = Guide.view(state),
            )
        }
        Log.i(TAG, "Guide ${state.entry()?.id} (kb ${kb?.version}): ${ins.progress} \"${ins.say}\" target=${ins.target} verify=${ins.verify}")
        // The KB's words, spoken verbatim; the step's part is pointed at meanwhile.
        voice.speak(ins.say, voice.epoch())
        _state.value.session?.let { saveTurn(it.id, question, ins.say, null, 0) }
        remoteTrouble?.let { kind ->
            if (remote.ui.value.available) {
                _state.update { it.copy(hint = "Still not working? Say so and I'll test the ${kind.noun} with my own remote") }
            }
        }
        if (ins.target == null && ins.verify == null) return
        guideJob = viewModelScope.launch {
            ins.target?.let { pointAtStep(it) }
            ins.verify?.let { verifyStep(state, it) }
        }
    }

    /** The KB's appliance name → the remote's device kind (only the ones Fixy has a remote for). */
    private fun deviceOf(appliance: String): com.fixlens.ir.DeviceKind? = when (appliance) {
        "television", "tv" -> com.fixlens.ir.DeviceKind.Tv
        "air_conditioner", "ac" -> com.fixlens.ir.DeviceKind.Ac
        "projector" -> com.fixlens.ir.DeviceKind.Projector
        "fan", "ceiling_fan" -> com.fixlens.ir.DeviceKind.Fan
        else -> null
    }

    private fun endGuide() {
        guideJob?.cancel()
        guideJob = null
        guideState = GuideState.Idle
        _state.update { it.copy(guide = null) }
    }

    /** "Point the camera at the engine oil dipstick handle": the target's name, without its description after a comma. */
    private fun pointHint(target: String): String =
        "Point the camera at the " + target.substringBefore(',').trim().removePrefix("the ").removePrefix("The ")

    /** Points at a guide step's target phrase: a rolled-back side request, streamed into the tracker. */
    private suspend fun pointAtStep(target: String) {
        val session = _state.value.session ?: return
        val id = turn.get()
        val start = System.currentTimeMillis()
        val keyframe = frameGrabber.captureKeyframe(File(getApplication<Application>().cacheDir, "step.jpg")) ?: return
        var tracked = 0
        val parser = GroundingParser(
            onGrounding = { vlm.cancel() }, // only the JSON is needed
            onText = {},
            onTarget = { raw ->
                if (trackPart(raw, keyframe, target, id, first = tracked == 0)) tracked++
                if (turn.get() == id) _state.update { it.copy(partsFound = it.partsFound + 1) }
            },
        )
        val result = runCatching {
            context.side(session, keyframe.file.absolutePath, FixyPrompts.pointAt(target), POINT_MAX_TOKENS, wait = true) {
                parser.feed(it)
            }
        }.onFailure { Log.e(TAG, "Step pointing failed", it) }.getOrNull()
        parser.finish()
        Log.i(TAG, "Step pointing \"$target\": ${summary(parser.grounding)} in ${System.currentTimeMillis() - start} ms (${result?.stats})")
        if (tracked == 0 && turn.get() == id) _state.update { it.copy(hint = pointHint(target)) }
    }

    /**
     * The step's visible sign of completion ("the filler cap is off"): every [VERIFY_EVERY_MS] while the user is
     * idle, ask the VLM (rolled back, skipped when it's busy); on "yes", go on as if they'd said "done".
     */
    private suspend fun verifyStep(state: GuideState, sign: String) {
        repeat(MAX_VERIFY_CHECKS) {
            delay(VERIFY_EVERY_MS)
            if (guideState != state) return
            val s = _state.value
            val session = s.session ?: return
            // The user is talking, or Fixy is: try later (and don't move on mid-sentence).
            if (s.phase != Phase.Listening || !s.questionFinal || s.speaking) return@repeat
            val keyframe = frameGrabber.captureKeyframe(File(getApplication<Application>().cacheDir, "verify.jpg"))
                ?: return@repeat
            val out = StringBuilder()
            val result = runCatching {
                context.side(session, keyframe.file.absolutePath, FixyPrompts.verify(sign), VERIFY_MAX_TOKENS, wait = false) {
                    out.append(it)
                }
            }.getOrNull()
            val answer = out.toString().trim()
            Log.i(TAG, "Verify \"$sign\": \"$answer\" (${result?.stats ?: "skipped, VLM busy"})")
            if (answer.lowercase().startsWith("yes") && guideState == state) {
                showGuide(SEEN_DONE, Guide.onCommand(state, Command.Done).first)
                return
            }
        }
    }

    private fun clearMarker() {
        tracker.clear()
        reGroundJob?.cancel()
        reGroundJob = null
    }

    /** Asks the model to name the session, in the background; a newer question simply cancels it. */
    private fun requestTitle(session: RepairSession) {
        viewModelScope.launch {
            val title = runCatching { context.suggestTitle(session) }.getOrNull() ?: return@launch
            updateSession(session.id) {
                if (it.titleSource == TitleSource.User) it else it.copy(title = title, titleSource = TitleSource.Model)
            }
        }
    }

    /**
     * Applies [transform] to a session (the open one, or the stored copy) and saves it. Runs on the main
     * thread, so updates never interleave. Returns the updated session. [touch] = false keeps its "last updated"
     * time, for a note that isn't new activity (a later session saying how this repair turned out).
     */
    private fun updateSession(id: String, touch: Boolean = true, transform: (RepairSession) -> RepairSession): RepairSession? {
        fun apply(s: RepairSession) = transform(s).let { if (touch) it.copy(updated = System.currentTimeMillis()) else it }
        val open = _state.value.session?.takeIf { it.id == id }
        val updated = if (open != null) {
            apply(open).also { s -> _state.update { it.copy(session = s) } }
        } else {
            null
        }
        viewModelScope.launch {
            val saved = updated ?: repo.load(id)?.let(::apply) ?: return@launch
            repo.save(saved)
            if (_state.value.screen == Screen.Sessions) refreshSessions()
        }
        return updated
    }

    /** Invalidates the current turn and stops its generation at the next token. */
    private fun cancelTurn() {
        turn.incrementAndGet()
        vlm.cancel()
        voice.stop()
    }

    /** Says a fixed line now, cutting off whatever Fixy was saying. */
    private fun say(text: String) {
        voice.stop()
        voice.speak(text, voice.epoch())
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        _state.update { it.copy(phase = Phase.Error, error = message) }
    }

    override fun onCleared() {
        speech.stop()
        voice.release()
        super.onCleared()
    }

    private companion object {
        /** Name the session after the first answer; try again at turn 3 if the model's first try was unusable. */
        val TITLE_AT_TURNS = setOf(1, 3)
        const val DEBUG_CAMERA_SETTLE_MS = 1500L
        /** How the VLM's box numbers are read. Verified on the phone, see docs/marker-tracking.md §2. */
        val COORD_SCALE = CoordScale.NORMALIZED_1000
        const val MAX_REGROUNDS = 5
        const val MAX_LABEL = 40
        const val POINT_MAX_TOKENS = 200
        /** "yes" or "no". */
        const val VERIFY_MAX_TOKENS = 3
        /** `{"device":"ac","brand":"Blue Star"}`. */
        const val IDENTIFY_MAX_TOKENS = 40
        /** A step's auto-check runs this often while the user is idle, at most [MAX_VERIFY_CHECKS] times. */
        const val VERIFY_EVERY_MS = 6000L
        const val MAX_VERIFY_CHECKS = 10
        const val GUIDE_STOPPED = "Okay, we'll stop the guide here."
        const val SORRY = "Sorry, I couldn't look at that. Please try again."
        /** Shown as the "question" when the auto-check moves on by itself. */
        const val SEEN_DONE = "✓ Looks done"
        const val HOLD_HINT = "Hold the mic while you talk"
        const val HINT_MS = 2500L
        /** "Test" on an alert: it fires this soon, to show the notification. */
        const val TEST_ALERT_MS = 5000L
    }
}
