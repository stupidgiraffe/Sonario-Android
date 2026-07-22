package ai.focal.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.focal.app.SummaryService
import ai.focal.app.data.EngineChoice
import ai.focal.app.data.Settings
import ai.focal.app.data.SessionPreview
import ai.focal.app.data.SessionStatus
import ai.focal.app.data.SessionStore
import ai.focal.app.data.StoredQa
import ai.focal.app.data.SummarySession
import ai.focal.app.llm.BUNDLED_MODELS
import ai.focal.app.llm.CloudEngine
import ai.focal.app.llm.LlmEngine
import ai.focal.app.llm.LlmProvider
import ai.focal.app.llm.ModelDownloader
import ai.focal.app.llm.ModelInfo
import ai.focal.app.llm.RateLimiter
import ai.focal.app.llm.ProviderRateLimiters
import ai.focal.app.llm.SecureStorage
import ai.focal.app.source.FileTextExtractor
import ai.focal.app.source.SourceFetcher
import ai.focal.app.summarize.SummarizeEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

enum class SummaryView { NORMAL, DETAILED, BULLETS, CHAPTER }

/** One question and its cited answer, for the ask box. */
data class QaPair(val question: String, val answer: String)

/** What the model download is doing right now, for the picker UI. */
data class DownloadState(
    val model: ModelInfo? = null,
    val bytes: Long = 0,
    val total: Long = 0,
    val active: Boolean = false,
    val error: String? = null,
) {
    val fraction: Float get() = if (total > 0) bytes.toFloat() / total else 0f
}

data class UiState(
    val input: String = "",
    val busy: Boolean = false,
    val phase: String = "",
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val liveText: String = "",
    val rateWaitSeconds: Long = 0,
    val networkStatus: String = "",
    val estimateText: String = "",
    val qaHistory: List<QaPair> = emptyList(),
    val asking: Boolean = false,
    val askError: String? = null,
    val result: SummarizeEngine.Result? = null,
    val view: SummaryView = SummaryView.NORMAL,
    val error: String? = null,
    val model: ModelInfo = BUNDLED_MODELS.first(),
    val models: List<ModelInfo> = emptyList(),
    val hasAnyModel: Boolean = false,
    val download: DownloadState = DownloadState(),
    // engine + cloud settings
    val engineChoice: EngineChoice = EngineChoice.ON_DEVICE,
    val cloudProvider: LlmProvider = LlmProvider.GROQ,
    val providerKeySet: Boolean = false,
    val providerModel: String = RateLimiter.GROQ_QWEN_MODEL,
    val customBaseUrl: String = "",
    val providerIssue: String? = null,
    // Durable local sessions.
    val activeSessionId: String? = null,
    val recentSessions: List<SessionPreview> = emptyList(),
    val resumeAvailable: Boolean = false,
    val sessionNotice: String = "",
) {
    val cloudConfigured: Boolean
        get() = providerIssue == null && (!cloudProvider.needsKey || providerKeySet)
}

class SummaryViewModel(app: Application) : AndroidViewModel(app) {

    private val llm = LlmEngine.get(app)
    private val fetcher = SourceFetcher()
    private val downloader = ModelDownloader(llm.modelsDir())
    private val settings = Settings(app)
    private val sessionStore = SessionStore(app)
    private val rateLimiters = ProviderRateLimiters(app)
    private val cloud = CloudEngine(
        context = app,
        apiKeyProvider = { settings.keyFor(settings.cloudProvider) },
        configProvider = { settings.configFor(settings.cloudProvider) },
        rateLimiterProvider = rateLimiters::forConfig,
        onRateWait = { seconds -> onRateWait(seconds) },
        onNetworkStatus = { message -> onNetworkStatus(message) },
    )

    private var downloadJob: Job? = null

    // Long-running work is process-scoped rather than Activity/ViewModel-scoped.
    // A recreated Activity reconnects to the same StateFlow and job instead of
    // starting over and spending the same tokens again.
    private var summaryJob: Job?
        get() = processSummaryJob
        set(value) { processSummaryJob = value }
    private var askJob: Job?
        get() = processAskJob
        set(value) { processAskJob = value }
    private var progressJob: Job?
        get() = processProgressJob
        set(value) { processProgressJob = value }
    private var lastSourceText: String
        get() = processLastSourceText
        set(value) { processLastSourceText = value }
    private var lastSummarizer: SummarizeEngine?
        get() = processLastSummarizer
        set(value) { processLastSummarizer = value }

    private val appCtx: android.content.Context
        get() = getApplication<Application>().applicationContext

    /** Keep the process foreground-priority so long jobs survive backgrounding. */
    private fun startKeepAlive(text: String) {
        runCatching { SummaryService.start(appCtx, text) }
    }
    private fun stopKeepAlive() {
        runCatching { SummaryService.stop(appCtx) }
    }

    /** A requested export the Activity should fulfil by opening the file picker. */
    data class PendingExport(
        val format: ai.focal.app.data.Exporter.Format,
        val title: String,
        val body: String,
    )

    private val _pendingExport = MutableStateFlow<PendingExport?>(null)
    val pendingExport: StateFlow<PendingExport?> = _pendingExport.asStateFlow()

    fun requestExport(
        format: ai.focal.app.data.Exporter.Format, title: String, body: String,
    ) {
        _pendingExport.value = PendingExport(format, title, body)
    }

    fun clearPendingExport() { _pendingExport.value = null }

    // ── ask a question about the summarized source ──────────────────────────────

    fun ask(question: String): Boolean {
        val q = question.trim()
        if (q.isEmpty() || _ui.value.asking || _ui.value.busy) return false
        val summarizer = lastSummarizer
        if (summarizer == null || lastSourceText.isBlank()) {
            _ui.value = _ui.value.copy(
                askError = "Ask is available after Focal finishes a summary.")
            return false
        }

        _ui.value = _ui.value.copy(
            asking = true,
            askError = null,
            networkStatus = "",
        )
        startKeepAlive("Answering your question…")
        askJob?.cancel()
        askJob = PROCESS_SCOPE.launch {
            try {
                val answer = summarizer.answer(q, lastSourceText)
                val updatedQa = _ui.value.qaHistory + QaPair(q, answer)
                _ui.value = _ui.value.copy(
                    qaHistory = updatedQa,
                    askError = null,
                )
                _ui.value.activeSessionId?.let { id ->
                    sessionStore.load(id)?.let { session ->
                        sessionStore.save(session.copy(
                            qaHistory = updatedQa.map { StoredQa(it.question, it.answer) },
                        ))
                    }
                    refreshSessionPreviewsNow()
                }
            } catch (_: CancellationException) {
                // User/app lifecycle cancellation is not an error to display.
            } catch (error: Exception) {
                _ui.value = _ui.value.copy(
                    askError = friendlyFailure(
                        error,
                        "Focal couldn't answer that question.",
                    )
                )
            } finally {
                _ui.value = _ui.value.copy(
                    asking = false,
                    networkStatus = "",
                    rateWaitSeconds = 0,
                )
                askJob = null
                stopKeepAlive()
            }
        }
        return true
    }

    // ── local file picker ───────────────────────────────────────────────────────

    private val extractor = FileTextExtractor(app)

    /** Set true to ask the Activity to open the system file picker. */
    private val _pickFile = MutableStateFlow(false)
    val pickFile: StateFlow<Boolean> = _pickFile.asStateFlow()

    fun requestPickFile() { _pickFile.value = true }
    fun clearPickFile() { _pickFile.value = false }

    /** Called by the Activity with the picked file's Uri. Extracts + summarizes. */
    fun onFilePicked(uri: android.net.Uri) {
        _pickFile.value = false
        if (_ui.value.busy) return

        val state = _ui.value
        val model = state.model
        if (!validateEngine(state.engineChoice, model)) return
        val summarizer = makeSummarizer(state.engineChoice)
        attachProgress(summarizer)

        var session = sessionStore.save(SummarySession(
            input = uri.toString(),
            title = "Reading file…",
            kind = "Document",
            engineChoice = state.engineChoice,
            modelFileName = model.fileName,
            cloudProviderId = settings.cloudProvider.id,
            cloudModel = settings.modelFor(settings.cloudProvider),
            phase = "reading file",
        ))
        prepareUiForSession(session)
        startKeepAlive("Reading and summarizing your file…")

        summaryJob?.cancel()
        summaryJob = PROCESS_SCOPE.launch {
            try {
                val ex = extractor.extract(uri)
                if (!ex.ok) {
                    throw IllegalStateException(ex.error ?: "Couldn't read that file.")
                }
                session = sessionStore.save(session.copy(
                    input = ex.name,
                    title = ex.name,
                    kind = if (ex.isEpub) "EPUB" else "Document",
                    sourceText = ex.text,
                    chapters = ex.chapters,
                    phase = "chunking",
                ))
                _ui.value = _ui.value.copy(
                    input = ex.name,
                    activeSessionId = session.id,
                    phase = "chunking",
                )
                refreshSessionPreviewsNow()
                runPreparedSession(session, summarizer, model)
            } catch (_: CancellationException) {
                val saved = sessionStore.save(session.copy(
                    status = SessionStatus.CANCELLED,
                    phase = _ui.value.phase,
                    progressCurrent = _ui.value.progressCurrent,
                    progressTotal = _ui.value.progressTotal,
                ))
                _ui.value = _ui.value.copy(
                    busy = false,
                    activeSessionId = saved.id,
                    resumeAvailable = saved.sourceText.isNotBlank(),
                    sessionNotice = if (saved.sourceText.isNotBlank())
                        "Stopped. The extracted file and completed sections were saved."
                    else "Stopped before the file finished loading.",
                    liveText = "",
                    error = null,
                )
                refreshSessionPreviewsNow()
                SummaryService.stop(appCtx)
                progressJob?.cancel()
                progressJob = null
                summaryJob = null
            } catch (error: Exception) {
                val message = friendlyFailure(error, "The summary failed.")
                val saved = sessionStore.save(session.copy(
                    status = SessionStatus.FAILED,
                    phase = _ui.value.phase,
                    progressCurrent = _ui.value.progressCurrent,
                    progressTotal = _ui.value.progressTotal,
                    error = message,
                ))
                _ui.value = _ui.value.copy(
                    busy = false,
                    error = message,
                    activeSessionId = saved.id,
                    resumeAvailable = saved.sourceText.isNotBlank(),
                    sessionNotice = if (saved.sourceText.isNotBlank())
                        "The file and completed sections were saved. You can resume."
                    else "",
                    liveText = "",
                )
                refreshSessionPreviewsNow()
                SummaryService.failed(appCtx, "Summary stopped: ${saved.title}")
                progressJob?.cancel()
                progressJob = null
                summaryJob = null
            }
        }
    }

    /** Called from the cloud engine while it waits for a provider rate-limit slot. */
    private fun onRateWait(seconds: Long) {
        _ui.value = _ui.value.copy(rateWaitSeconds = seconds)
        if (seconds > 0 && (seconds <= 5 || seconds % 10L == 0L)) {
            runCatching {
                val provider = settings.cloudProvider.displayName
                SummaryService.update(appCtx, "Waiting for $provider rate limit (${seconds}s)…")
            }
        }
    }

    private fun onNetworkStatus(message: String?) {
        val text = message.orEmpty()
        _ui.value = _ui.value.copy(networkStatus = text)
        if (text.isNotBlank()) {
            runCatching { SummaryService.update(appCtx, text) }
        }
    }

    private fun activeRateLimiter(): RateLimiter? =
        rateLimiters.forConfig(settings.configFor(settings.cloudProvider))

    /** Tokens used today against the free-tier daily cap, for display. */
    fun groqDailyUsage(): Pair<Long, Long> {
        val u = activeRateLimiter()?.dailyUsage() ?: return 0L to 0L
        return u.used to u.limit
    }

    fun hasTrackedDailyBudget(): Boolean = activeRateLimiter() != null

    /** Manually clear the app's daily token tally (e.g. if it drifts). */
    fun resetDailyBudget() {
        activeRateLimiter()?.resetDaily()
        // nudge UI to re-read
        _ui.value = _ui.value.copy()
    }

    /** Build a human-readable estimate line and put it in the UI (Groq only). */
    private fun setEstimate(sourceText: String) {
        val limiter = activeRateLimiter()
        if (limiter == null) {
            val tokens = formatCount((RateLimiter.estimateTokens(sourceText) * 2.4).toLong() + 5_000L)
            _ui.value = _ui.value.copy(estimateText = "~$tokens tokens · provider-managed quota")
            return
        }
        val e = limiter.estimate(sourceText)
        val tokens = formatCount(e.totalTokens)
        val eta = formatEta(e.etaSeconds)
        val line = buildString {
            append("~$tokens tokens · $eta")
            append(" · ${e.percentOfRemaining}% of today's remaining budget")
            if (e.exceedsDaily) {
                val rem = formatCount(e.dailyRemaining)
                append("\n⚠ This is more than today's remaining free-tier budget " +
                    "(~$rem left). It may stop partway; it resets tomorrow.")
            }
        }
        _ui.value = _ui.value.copy(estimateText = line)
    }

    private fun formatCount(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> "${(n / 1000)}K"
        else -> n.toString()
    }

    private fun formatEta(seconds: Long): String = when {
        seconds < 60 -> "est. ~${seconds}s"
        else -> {
            val m = seconds / 60
            val s = seconds % 60
            if (s == 0L) "est. ~${m} min" else "est. ~${m}m ${s}s"
        }
    }

    private val _ui: MutableStateFlow<UiState> = synchronized(PROCESS_LOCK) {
        processUi ?: MutableStateFlow(initialState()).also { processUi = it }
    }
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        val shouldRestore = synchronized(PROCESS_LOCK) {
            if (restoreStarted) false else {
                restoreStarted = true
                true
            }
        }
        if (shouldRestore) restoreRecentSessions()
    }

    private fun restoreRecentSessions() {
        PROCESS_SCOPE.launch {
            val previews = sessionStore.previews()
            _ui.value = _ui.value.copy(recentSessions = previews)
            if (_ui.value.result == null && !_ui.value.busy) {
                previews.firstOrNull()?.let { latest ->
                    sessionStore.load(latest.id)?.let {
                        applySession(it, restoredAtLaunch = true)
                    }
                }
            }
        }
    }

    private fun refreshSessionPreviews() {
        PROCESS_SCOPE.launch { refreshSessionPreviewsNow() }
    }

    private fun refreshSessionPreviewsNow() {
        _ui.value = _ui.value.copy(recentSessions = sessionStore.previews())
    }

    private fun applySession(session: SummarySession, restoredAtLaunch: Boolean = false) {
        val sessionProvider = LlmProvider.fromIdOrNull(session.cloudProviderId)
        val restoreIssue = session.migrationIssue ?: if (
            session.engineChoice == EngineChoice.CLOUD && sessionProvider == null
        ) {
            "Saved session uses unsupported cloud provider '${session.cloudProviderId}'."
        } else null
        val models = llm.availableModels()
        val chosenModel = llm.modelForFileName(session.modelFileName)
            ?: _ui.value.model
        val canResume = session.sourceText.isNotBlank() && session.result == null &&
            restoreIssue == null
        val interrupted = session.status == SessionStatus.RUNNING && processSummaryJob?.isActive != true
        val notice = when {
            session.result != null && restoredAtLaunch ->
                "Restored your most recent summary."
            interrupted ->
                "This summary was interrupted, but its completed sections were saved. Resume continues from the last checkpoint."
            session.status == SessionStatus.FAILED && canResume ->
                "The previous run stopped, but its completed sections were saved. You can resume without starting over."
            session.status == SessionStatus.CANCELLED && canResume ->
                "This session was cancelled. Completed sections are still saved and resumable."
            else -> ""
        }

        if (restoreIssue == null) settings.engine = session.engineChoice
        if (session.engineChoice == EngineChoice.CLOUD && sessionProvider != null && restoreIssue == null) {
            settings.cloudProvider = sessionProvider
            if (session.cloudModel.isNotBlank()) {
                settings.setModelFor(sessionProvider, session.cloudModel)
            }
        }
        lastSourceText = session.sourceText
        lastSummarizer = if (session.sourceText.isNotBlank() && restoreIssue == null) {
            makeSummarizer(session.engineChoice)
        } else null

        val activeProvider = sessionProvider ?: settings.cloudProvider
        val credential = settings.credentialStateFor(activeProvider)
        val activeConfig = settings.configFor(activeProvider)

        _ui.value = _ui.value.copy(
            input = session.input,
            busy = processSummaryJob?.isActive == true && _ui.value.activeSessionId == session.id,
            phase = if (interrupted) session.phase else _ui.value.phase,
            progressCurrent = session.progressCurrent,
            progressTotal = session.progressTotal,
            liveText = "",
            rateWaitSeconds = 0,
            networkStatus = "",
            estimateText = "",
            qaHistory = session.qaHistory.map { QaPair(it.question, it.answer) },
            asking = false,
            askError = null,
            result = session.result,
            view = SummaryView.NORMAL,
            error = when {
                restoreIssue != null -> restoreIssue
                session.result != null -> null
                interrupted -> null
                session.status == SessionStatus.FAILED -> session.error
                else -> null
            },
            model = chosenModel,
            models = models,
            hasAnyModel = models.any { it.present },
            engineChoice = session.engineChoice,
            cloudProvider = activeProvider,
            providerKeySet = credential.isSet,
            providerModel = session.cloudModel.ifBlank { activeConfig.model },
            customBaseUrl = activeConfig.customBaseUrl,
            providerIssue = restoreIssue ?: credential.error ?: activeConfig.validationError(),
            activeSessionId = session.id,
            resumeAvailable = canResume && processSummaryJob?.isActive != true,
            sessionNotice = notice,
        )
    }

    fun openSession(id: String) {
        if (_ui.value.busy || _ui.value.asking) return
        PROCESS_SCOPE.launch {
            val session = sessionStore.load(id) ?: return@launch
            applySession(session)
        }
    }

    fun deleteSession(id: String) {
        if (_ui.value.activeSessionId == id && _ui.value.busy) return
        PROCESS_SCOPE.launch {
            sessionStore.delete(id)
            if (_ui.value.activeSessionId == id) {
                lastSourceText = ""
                lastSummarizer = null
                _ui.value = _ui.value.copy(
                    activeSessionId = null,
                    result = null,
                    qaHistory = emptyList(),
                    resumeAvailable = false,
                    sessionNotice = "",
                    error = null,
                )
            }
            refreshSessionPreviewsNow()
        }
    }

    /** Permanently delete all locally saved sessions and their source files. */
    fun clearAllSessions() {
        if (_ui.value.busy || _ui.value.asking) return
        PROCESS_SCOPE.launch {
            sessionStore.clearAll()
            lastSourceText = ""
            lastSummarizer = null
            _ui.value = _ui.value.copy(
                activeSessionId = null,
                recentSessions = emptyList(),
                resumeAvailable = false,
                sessionNotice = "Recent sessions and saved source data were cleared.",
                qaHistory = emptyList(),
                result = null,
                view = SummaryView.NORMAL,
                error = null,
                askError = null,
                phase = "",
                progressCurrent = 0,
                progressTotal = 0,
                liveText = "",
                estimateText = "",
                networkStatus = "",
                rateWaitSeconds = 0,
            )
        }
    }

    fun resumeSession(id: String? = _ui.value.activeSessionId) {
        if (id == null || _ui.value.busy || _ui.value.asking) return
        PROCESS_SCOPE.launch {
            val loaded = sessionStore.load(id) ?: return@launch
            if (loaded.result != null) {
                applySession(loaded)
                return@launch
            }
            if (loaded.sourceText.isBlank()) {
                _ui.value = _ui.value.copy(
                    error = "This session stopped before the source was saved, so it cannot be resumed.")
                return@launch
            }
            if (loaded.migrationIssue != null) {
                _ui.value = _ui.value.copy(error = loaded.migrationIssue)
                return@launch
            }
            val sessionProvider = LlmProvider.fromIdOrNull(loaded.cloudProviderId)
            if (loaded.engineChoice == EngineChoice.CLOUD && sessionProvider == null) {
                _ui.value = _ui.value.copy(
                    error = "Saved session uses unsupported cloud provider '${loaded.cloudProviderId}'.")
                return@launch
            }
            if (loaded.engineChoice == EngineChoice.CLOUD) {
                val provider = sessionProvider ?: return@launch
                settings.cloudProvider = provider
                if (loaded.cloudModel.isNotBlank()) {
                    settings.setModelFor(provider, loaded.cloudModel)
                }
            }
            if (!validateEngine(loaded.engineChoice, modelFor(loaded))) return@launch

            val session = sessionStore.save(loaded.copy(
                status = SessionStatus.RUNNING,
                error = null,
                phase = loaded.phase.ifBlank { "resuming" },
            ))
            settings.engine = session.engineChoice
            val summarizer = makeSummarizer(session.engineChoice)
            val model = modelFor(session)
            attachProgress(summarizer)
            prepareUiForSession(session, "Resuming from saved checkpoint…")
            startKeepAlive("Resuming ${session.title}…")

            summaryJob?.cancel()
            summaryJob = PROCESS_SCOPE.launch {
                runPreparedSession(session, summarizer, model)
            }
        }
    }

    private fun modelFor(session: SummarySession): ModelInfo {
        return llm.modelForFileName(session.modelFileName)
            ?: _ui.value.model
    }

    private fun makeSummarizer(choice: EngineChoice): SummarizeEngine =
        if (choice == EngineChoice.CLOUD) {
            val config = settings.configFor(settings.cloudProvider)
            SummarizeEngine(cloud, SummarizeEngine.profileFor(config))
        } else {
            SummarizeEngine(llm, SummarizeEngine.Profile.LOCAL)
        }

    private fun validateEngine(choice: EngineChoice, model: ModelInfo): Boolean {
        val provider = settings.cloudProvider
        if (choice == EngineChoice.CLOUD) {
            settings.providerSelectionIssue?.let { issue ->
                _ui.value = _ui.value.copy(error = issue, providerIssue = issue)
                return false
            }
            settings.configFor(provider).validationError()?.let { issue ->
                _ui.value = _ui.value.copy(error = issue, providerIssue = issue)
                return false
            }
            val credential = settings.credentialStateFor(provider)
            if (credential.error != null) {
                _ui.value = _ui.value.copy(error = credential.error, providerIssue = credential.error)
                return false
            }
            if (provider.needsKey && !credential.isSet) {
                _ui.value = _ui.value.copy(
                    error = "This session uses ${provider.displayName}, but no API key is currently set.")
                return false
            }
        }
        if (choice == EngineChoice.ON_DEVICE && !llm.isModelPresent(model)) {
            _ui.value = _ui.value.copy(
                error = "The on-device model used by this session is not installed.")
            return false
        }
        return true
    }

    private fun attachProgress(summarizer: SummarizeEngine) {
        progressJob?.cancel()
        progressJob = PROCESS_SCOPE.launch {
            summarizer.progress.collect { p ->
                _ui.value = _ui.value.copy(
                    phase = p.phase,
                    progressCurrent = p.current,
                    progressTotal = p.total,
                    liveText = p.live,
                    rateWaitSeconds = if (p.live.isNotBlank()) 0 else _ui.value.rateWaitSeconds,
                )
            }
        }
    }

    private fun prepareUiForSession(session: SummarySession, notice: String = "") {
        val provider = settings.cloudProvider
        val credential = settings.credentialStateFor(provider)
        val config = settings.configFor(provider)
        _ui.value = _ui.value.copy(
            input = session.input,
            busy = true,
            error = null,
            result = null,
            phase = session.phase,
            liveText = "",
            rateWaitSeconds = 0,
            networkStatus = "",
            estimateText = "",
            askError = null,
            qaHistory = session.qaHistory.map { QaPair(it.question, it.answer) },
            engineChoice = session.engineChoice,
            cloudProvider = provider,
            providerKeySet = credential.isSet,
            providerModel = session.cloudModel.ifBlank { config.model },
            customBaseUrl = config.customBaseUrl,
            providerIssue = credential.error ?: config.validationError(),
            activeSessionId = session.id,
            resumeAvailable = false,
            sessionNotice = notice,
        )
        refreshSessionPreviewsNow()
    }

    private suspend fun runPreparedSession(
        initial: SummarySession,
        summarizer: SummarizeEngine,
        model: ModelInfo,
    ) {
        var session = initial
        try {
            if (session.engineChoice == EngineChoice.CLOUD) {
                val cp = session.checkpoint
                val hasSavedWork = cp.notes.isNotEmpty() || cp.normal.isNotBlank() ||
                    cp.bullets.isNotBlank() || cp.detailed.isNotBlank() ||
                    cp.detailedParts.isNotEmpty() || cp.chapterParts.isNotEmpty()
                if (hasSavedWork) {
                    _ui.value = _ui.value.copy(
                        estimateText = "Resuming from a local checkpoint. Completed model calls will be skipped.")
                } else {
                    setEstimate(session.sourceText)
                }
            }
            var checkpoint = session.checkpoint
            var result = summarizer.run(
                model = model,
                text = session.sourceText,
                title = session.title,
                kind = session.kind,
                approxMinutes = session.approxMinutes,
                initialCheckpoint = checkpoint,
                onCheckpoint = { saved ->
                    checkpoint = saved
                    session = sessionStore.save(session.copy(
                        status = SessionStatus.RUNNING,
                        phase = _ui.value.phase,
                        progressCurrent = _ui.value.progressCurrent,
                        progressTotal = _ui.value.progressTotal,
                        checkpoint = saved,
                        error = null,
                    ))
                    refreshSessionPreviewsNow()
                },
            )

            if (session.chapters.isNotEmpty()) {
                val chapterText = summarizer.summarizeChapters(
                    chapters = session.chapters,
                    bookOverview = result.normal,
                    existingParts = checkpoint.chapterParts,
                    onCheckpoint = { parts ->
                        checkpoint = checkpoint.copy(chapterParts = parts)
                        session = sessionStore.save(session.copy(
                            status = SessionStatus.RUNNING,
                            phase = "chapters",
                            progressCurrent = parts.size,
                            progressTotal = session.chapters.size,
                            checkpoint = checkpoint,
                        ))
                        refreshSessionPreviewsNow()
                    },
                )
                result = result.copy(chapters = chapterText)
            }

            session = sessionStore.save(session.copy(
                status = SessionStatus.COMPLETE,
                phase = "done",
                progressCurrent = 0,
                progressTotal = 0,
                checkpoint = checkpoint,
                result = result,
                error = null,
            ))
            lastSourceText = session.sourceText
            lastSummarizer = summarizer
            _ui.value = _ui.value.copy(
                busy = false,
                result = result,
                view = SummaryView.NORMAL,
                phase = "done",
                liveText = "",
                rateWaitSeconds = 0,
                networkStatus = "",
                estimateText = "",
                qaHistory = session.qaHistory.map { QaPair(it.question, it.answer) },
                activeSessionId = session.id,
                resumeAvailable = false,
                sessionNotice = "Saved locally in Recent sessions.",
                error = null,
            )
            refreshSessionPreviewsNow()
            SummaryService.complete(appCtx, "Summary ready: ${session.title}")
        } catch (_: CancellationException) {
            session = sessionStore.save(session.copy(
                status = SessionStatus.CANCELLED,
                phase = _ui.value.phase,
                progressCurrent = _ui.value.progressCurrent,
                progressTotal = _ui.value.progressTotal,
                error = null,
            ))
            _ui.value = _ui.value.copy(
                busy = false,
                liveText = "",
                networkStatus = "",
                rateWaitSeconds = 0,
                activeSessionId = session.id,
                resumeAvailable = session.sourceText.isNotBlank(),
                sessionNotice = "Stopped. Completed sections were saved and can be resumed.",
                error = null,
            )
            refreshSessionPreviewsNow()
            SummaryService.stop(appCtx)
        } catch (error: Exception) {
            val message = friendlyFailure(error, "The summary failed.")
            session = sessionStore.save(session.copy(
                status = SessionStatus.FAILED,
                phase = _ui.value.phase,
                progressCurrent = _ui.value.progressCurrent,
                progressTotal = _ui.value.progressTotal,
                error = message,
            ))
            _ui.value = _ui.value.copy(
                busy = false,
                liveText = "",
                networkStatus = "",
                rateWaitSeconds = 0,
                error = message,
                activeSessionId = session.id,
                resumeAvailable = session.sourceText.isNotBlank(),
                sessionNotice = if (session.sourceText.isNotBlank())
                    "Completed sections were saved. Resume will continue from the checkpoint."
                else "",
            )
            refreshSessionPreviewsNow()
            SummaryService.failed(appCtx, "Summary stopped: ${session.title}")
        } finally {
            progressJob?.cancel()
            progressJob = null
            summaryJob = null
            _ui.value = _ui.value.copy(networkStatus = "", rateWaitSeconds = 0)
        }
    }

    private fun initialState(): UiState {
        val models = llm.availableModels()
        val present = models.firstOrNull { it.present }
        val provider = settings.cloudProvider
        val credential = settings.credentialStateFor(provider)
        val config = settings.configFor(provider)
        return UiState(
            models = models,
            hasAnyModel = present != null,
            model = present ?: models.first(),
            engineChoice = settings.engine,
            cloudProvider = provider,
            providerKeySet = credential.isSet,
            providerModel = config.model,
            customBaseUrl = config.customBaseUrl,
            providerIssue = settings.migrationIssue ?: settings.providerSelectionIssue
                ?: credential.error ?: config.validationError(),
        )
    }

    fun onInput(s: String) {
        _ui.value = _ui.value.copy(input = s, error = null)
    }

    fun clearAskError() {
        if (_ui.value.askError != null) {
            _ui.value = _ui.value.copy(askError = null)
        }
    }
    fun setView(v: SummaryView) { _ui.value = _ui.value.copy(view = v) }
    fun setModel(m: ModelInfo) { _ui.value = _ui.value.copy(model = m) }

    fun refreshModels() {
        val models = llm.availableModels()
        _ui.value = _ui.value.copy(
            models = models,
            hasAnyModel = models.any { it.present },
        )
    }

    // ── engine + cloud settings ────────────────────────────────────────────────

    fun setEngine(choice: EngineChoice) {
        if (_ui.value.busy || _ui.value.asking) return
        settings.engine = choice
        _ui.value = _ui.value.copy(engineChoice = choice, error = null)
    }

    fun setCloudProvider(provider: LlmProvider) {
        if (_ui.value.busy || _ui.value.asking) return
        settings.cloudProvider = provider
        refreshProviderState(provider)
    }

    fun setProviderKey(key: String) {
        if (_ui.value.busy || _ui.value.asking) return
        val provider = settings.cloudProvider
        try {
            val changed = key.trim() != (settings.keyFor(provider) ?: "")
            settings.setKeyFor(provider, key)
            if (changed) activeRateLimiter()?.resetDaily()
            refreshProviderState(provider)
        } catch (error: Exception) {
            _ui.value = _ui.value.copy(
                providerIssue = error.message ?: "The credential could not be saved.")
        }
    }

    fun clearProviderKey() {
        if (_ui.value.busy || _ui.value.asking) return
        val provider = settings.cloudProvider
        try {
            settings.setKeyFor(provider, null)
            refreshProviderState(provider)
        } catch (error: Exception) {
            _ui.value = _ui.value.copy(
                providerIssue = error.message ?: "The credential could not be removed.")
        }
    }

    fun setProviderModel(model: String) {
        if (_ui.value.busy || _ui.value.asking) return
        val provider = settings.cloudProvider
        settings.setModelFor(provider, model)
        refreshProviderState(provider)
    }

    fun setCustomBaseUrl(url: String) {
        if (_ui.value.busy || _ui.value.asking) return
        val provider = settings.cloudProvider
        settings.setCustomBaseUrlFor(provider, url)
        refreshProviderState(provider)
    }

    fun maskedProviderKey(): String = runCatching {
        SecureStorage.masked(settings.keyFor(settings.cloudProvider))
    }.getOrDefault("Unavailable")

    private fun refreshProviderState(provider: LlmProvider) {
        val credential = settings.credentialStateFor(provider)
        val config = settings.configFor(provider)
        _ui.value = _ui.value.copy(
            cloudProvider = provider,
            providerKeySet = credential.isSet,
            providerModel = config.model,
            customBaseUrl = config.customBaseUrl,
            providerIssue = credential.error ?: config.validationError(),
            error = null,
        )
    }

    // ── model download (first-run picker + Models screen) ──────────────────────

    fun downloadModel(model: ModelInfo) {
        if (_ui.value.download.active) return
        _ui.value = _ui.value.copy(
            download = DownloadState(model = model, active = true))
        downloadJob = viewModelScope.launch {
            downloader.download(model).collect { st ->
                when (st) {
                    is ModelDownloader.State.Progress ->
                        _ui.value = _ui.value.copy(
                            download = _ui.value.download.copy(
                                bytes = st.bytes, total = st.total))
                    is ModelDownloader.State.Done -> {
                        val models = llm.availableModels()
                        _ui.value = _ui.value.copy(
                            models = models,
                            hasAnyModel = true,
                            model = models.firstOrNull { it.fileName == model.fileName }
                                ?: model,
                            download = DownloadState(),
                        )
                    }
                    is ModelDownloader.State.Failed ->
                        _ui.value = _ui.value.copy(
                            download = DownloadState(model = model, error = st.message))
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _ui.value = _ui.value.copy(download = DownloadState())
    }

    // ── summarize ──────────────────────────────────────────────────────────────

    fun summarize() {
        val state = _ui.value
        if (state.input.isBlank() || state.busy) return

        val model = state.model
        if (!validateEngine(state.engineChoice, model)) return
        val summarizer = makeSummarizer(state.engineChoice)
        attachProgress(summarizer)

        var session = sessionStore.save(SummarySession(
            input = state.input,
            title = state.input.lineSequence().firstOrNull()?.take(80)
                ?.ifBlank { "New summary" } ?: "New summary",
            kind = "Source",
            engineChoice = state.engineChoice,
            modelFileName = model.fileName,
            cloudProviderId = settings.cloudProvider.id,
            cloudModel = settings.modelFor(settings.cloudProvider),
            phase = "fetching",
        ))
        prepareUiForSession(session)
        startKeepAlive("Fetching source…")

        summaryJob?.cancel()
        summaryJob = PROCESS_SCOPE.launch {
            try {
                val src = fetcher.fetch(state.input)
                if (!src.ok) {
                    throw IllegalStateException(src.error ?: "Could not read that source.")
                }
                session = sessionStore.save(session.copy(
                    title = src.title,
                    kind = src.kind,
                    approxMinutes = src.approxMinutes,
                    sourceText = src.text,
                    phase = "chunking",
                ))
                _ui.value = _ui.value.copy(
                    activeSessionId = session.id,
                    phase = "chunking",
                )
                refreshSessionPreviewsNow()
                runPreparedSession(session, summarizer, model)
            } catch (_: CancellationException) {
                val saved = sessionStore.save(session.copy(
                    status = SessionStatus.CANCELLED,
                    phase = _ui.value.phase,
                    progressCurrent = _ui.value.progressCurrent,
                    progressTotal = _ui.value.progressTotal,
                ))
                _ui.value = _ui.value.copy(
                    busy = false,
                    activeSessionId = saved.id,
                    resumeAvailable = saved.sourceText.isNotBlank(),
                    sessionNotice = if (saved.sourceText.isNotBlank())
                        "Stopped. The source and completed sections were saved."
                    else "Stopped before the source finished loading.",
                    liveText = "",
                    error = null,
                )
                refreshSessionPreviewsNow()
                SummaryService.stop(appCtx)
                progressJob?.cancel()
                progressJob = null
                summaryJob = null
            } catch (error: Exception) {
                val message = friendlyFailure(error, "The summary failed.")
                val saved = sessionStore.save(session.copy(
                    status = SessionStatus.FAILED,
                    phase = _ui.value.phase,
                    progressCurrent = _ui.value.progressCurrent,
                    progressTotal = _ui.value.progressTotal,
                    error = message,
                ))
                _ui.value = _ui.value.copy(
                    busy = false,
                    error = message,
                    activeSessionId = saved.id,
                    resumeAvailable = saved.sourceText.isNotBlank(),
                    sessionNotice = if (saved.sourceText.isNotBlank())
                        "The source and completed sections were saved. You can resume."
                    else "",
                    liveText = "",
                )
                refreshSessionPreviewsNow()
                SummaryService.failed(appCtx, "Summary stopped: ${saved.title}")
                progressJob?.cancel()
                progressJob = null
                summaryJob = null
            }
        }
    }

    fun cancelSummary() {
        val job = summaryJob
        if (job?.isActive == true) {
            job.cancel()
            return
        }
        _ui.value = _ui.value.copy(
            busy = false,
            phase = "",
            liveText = "",
            rateWaitSeconds = 0,
            networkStatus = "",
            error = null,
        )
        stopKeepAlive()
    }

    private fun friendlyFailure(error: Throwable, fallback: String): String {
        val message = error.message?.trim().orEmpty()
        return when {
            message.isNotBlank() -> message
            else -> "$fallback (${error.javaClass.simpleName})"
        }
    }

    override fun onCleared() {
        // Do not cancel process-scoped summary/ask work here. Android may destroy
        // and recreate the Activity while the foreground task is still running.
        downloadJob?.cancel()
        super.onCleared()
    }

    fun reset() {
        askJob?.cancel()
        askJob = null
        stopKeepAlive()
        _ui.value = _ui.value.copy(
            result = null,
            error = null,
            input = "",
            phase = "",
            liveText = "",
            qaHistory = emptyList(),
            askError = null,
            networkStatus = "",
            rateWaitSeconds = 0,
            activeSessionId = null,
            resumeAvailable = false,
            sessionNotice = "",
        )
        lastSourceText = ""
        lastSummarizer = null
        refreshSessionPreviews()
    }

    companion object {
        private val PROCESS_LOCK = Any()
        private val PROCESS_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile private var processUi: MutableStateFlow<UiState>? = null
        @Volatile private var processSummaryJob: Job? = null
        @Volatile private var processAskJob: Job? = null
        @Volatile private var processProgressJob: Job? = null
        @Volatile private var processLastSourceText: String = ""
        @Volatile private var processLastSummarizer: SummarizeEngine? = null
        @Volatile private var restoreStarted: Boolean = false
    }
}
