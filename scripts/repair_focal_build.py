from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] if Path(__file__).resolve().parent.name == "scripts" else Path.cwd()


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old in text:
        path.write_text(text.replace(old, new, 1), encoding="utf-8")
        return
    if new in text:
        return
    raise RuntimeError(f"Expected source block not found in {path}: {old[:80]!r}")


def replace_all(path: Path, old: str, new: str, expected_min: int = 1) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count == 0 and new in text:
        return
    if count < expected_min:
        raise RuntimeError(f"Expected at least {expected_min} matches in {path}, found {count}")
    path.write_text(text.replace(old, new), encoding="utf-8")


settings = ROOT / "app/src/main/java/ai/sonario/app/data/Settings.kt"
replace_once(
    settings,
    '''class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sonario_settings", Context.MODE_PRIVATE)

    init {
        migrateLegacyKey(context)
    }''',
    '''class Settings(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext
        .getSharedPreferences("sonario_settings", Context.MODE_PRIVATE)

    init {
        migrateLegacyKey(appContext)
    }''',
)
replace_all(settings, "SecureStorage.hasKey(context, provider.id)", "SecureStorage.hasKey(appContext, provider.id)")
replace_all(settings, "SecureStorage.getKey(context, provider.id)", "SecureStorage.getKey(appContext, provider.id)")
replace_all(settings, "SecureStorage.storeKey(context, provider.id, key)", "SecureStorage.storeKey(appContext, provider.id, key)")

source_fetcher = ROOT / "app/src/main/java/ai/sonario/app/source/SourceFetcher.kt"
replace_once(
    source_fetcher,
    '''    private fun diagnosticMessage(e: Exception): String = e.message
        ?.replace(';', ',')
        .replace('\\\\n', ' ')
        ?.take(180) ?: e.javaClass.simpleName''',
    '''    private fun diagnosticMessage(e: Exception): String = e.message
        ?.replace(';', ',')
        ?.replace('\\n', ' ')
        ?.take(180) ?: e.javaClass.simpleName''',
)

summary_screen = ROOT / "app/src/main/java/ai/sonario/app/ui/SummaryScreen.kt"
replace_once(summary_screen, "onClick = { vm.cancel() },", "onClick = { vm.cancelSummary() },")
replace_once(
    summary_screen,
    '''                    typography = markdownTypography(
                        default = MaterialTheme.typography.bodyMedium.copy(
                            color = SonarioColors.Ink),
                    ),''',
    '''                    typography = markdownTypography(
                        text = MaterialTheme.typography.bodyMedium.copy(
                            color = SonarioColors.Ink),
                        paragraph = MaterialTheme.typography.bodyMedium.copy(
                            color = SonarioColors.Ink),
                    ),''',
)
replace_once(
    summary_screen,
    '''                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(stripMd(copyText))) {
                }) {''',
    '''                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(stripMd(copyText)))
                }) {''',
)
replace_once(
    summary_screen,
    "private fun approxMinutes(minutes: Int?) {",
    "@Composable\nprivate fun approxMinutes(minutes: Int?) {",
)

view_model = ROOT / "app/src/main/java/ai/sonario/app/ui/SummaryViewModel.kt"
replace_once(
    view_model,
    "import ai.sonario.app.llm.LlmEngine\n",
    "import ai.sonario.app.llm.LlmEngine\nimport ai.sonario.app.llm.LlmProvider\n",
)
replace_once(
    view_model,
    "modelProvider = { settings.modelFor(settings.cloudProvider) },",
    "configProvider = { settings.configFor(settings.cloudProvider) },",
)
replace_once(
    view_model,
    '''            modelFileName = model.fileName,
            groqModel = state.modelFor(settings.cloudProvider),
            phase = "reading file",''',
    '''            modelFileName = model.fileName,
            cloudProviderId = settings.cloudProvider.id,
            cloudModel = settings.modelFor(settings.cloudProvider),
            phase = "reading file",''',
)
replace_once(
    view_model,
    '''    private fun applySession(session: SummarySession, restoredAtLaunch: Boolean = false) {
        val models = llm.availableModels()''',
    '''    private fun applySession(session: SummarySession, restoredAtLaunch: Boolean = false) {
        val sessionProvider = LlmProvider.fromId(session.cloudProviderId)
        val models = llm.availableModels()''',
)
replace_once(
    view_model,
    '''        settings.engine = session.engineChoice
        if (session.engineChoice == EngineChoice.CLOUD && session.modelFor(settings.cloudProvider).isNotBlank()) {
            settings.setModelFor(settings.cloudProvider, session.groqModel)
        }''',
    '''        settings.engine = session.engineChoice
        if (session.engineChoice == EngineChoice.CLOUD) {
            settings.cloudProvider = sessionProvider
            if (session.cloudModel.isNotBlank()) {
                settings.setModelFor(sessionProvider, session.cloudModel)
            }
        }''',
)
replace_once(
    view_model,
    "groqModel = session.modelFor(settings.cloudProvider),",
    "groqModel = session.cloudModel.ifBlank { settings.modelFor(sessionProvider) },",
)
replace_once(
    view_model,
    '''            if (!validateEngine(loaded.engineChoice, modelFor(loaded))) return@launch

            val session = sessionStore.save(loaded.copy(''',
    '''            val sessionProvider = LlmProvider.fromId(loaded.cloudProviderId)
            if (loaded.engineChoice == EngineChoice.CLOUD) {
                settings.cloudProvider = sessionProvider
                if (loaded.cloudModel.isNotBlank()) {
                    settings.setModelFor(sessionProvider, loaded.cloudModel)
                }
            }
            if (!validateEngine(loaded.engineChoice, modelFor(loaded))) return@launch

            val session = sessionStore.save(loaded.copy(''',
)
replace_once(
    view_model,
    '''            settings.engine = session.engineChoice
            if (session.engineChoice == EngineChoice.CLOUD) {
                settings.setModelFor(settings.cloudProvider, session.groqModel)
            }''',
    '''            settings.engine = session.engineChoice''',
)
replace_once(
    view_model,
    '''    private fun validateEngine(choice: EngineChoice, model: ModelInfo): Boolean {
        if (choice == EngineChoice.CLOUD && !settings.hasKeyFor(settings.cloudProvider)) {
            _ui.value = _ui.value.copy(
                error = "This session used Groq, but no Groq API key is currently set.")
            return false
        }''',
    '''    private fun validateEngine(choice: EngineChoice, model: ModelInfo): Boolean {
        val provider = settings.cloudProvider
        if (choice == EngineChoice.CLOUD && provider.needsKey && !settings.hasKeyFor(provider)) {
            _ui.value = _ui.value.copy(
                error = "This session uses ${provider.displayName}, but no API key is currently set.")
            return false
        }''',
)
replace_once(
    view_model,
    "groqModel = session.modelFor(settings.cloudProvider),",
    "groqModel = session.cloudModel.ifBlank { settings.modelFor(settings.cloudProvider) },",
)
replace_once(
    view_model,
    '''            refreshSessionPreviewsNow()
            SummaryService.failed(appCtx, "Summary stopped: ${saved.title}")
        } finally {''',
    '''            refreshSessionPreviewsNow()
            SummaryService.failed(appCtx, "Summary stopped: ${session.title}")
        } finally {''',
)
replace_once(
    view_model,
    '''            modelFileName = model.fileName,
            groqModel = state.modelFor(settings.cloudProvider),
            phase = "fetching",''',
    '''            modelFileName = model.fileName,
            cloudProviderId = settings.cloudProvider.id,
            cloudModel = settings.modelFor(settings.cloudProvider),
            phase = "fetching",''',
)

print("Applied Focal debug-build source repairs.")
