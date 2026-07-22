package ai.sonario.app

import android.app.Application
import ai.sonario.app.data.SessionStore
import ai.sonario.app.llm.LegacyModelCleanup
import java.io.File

class FocalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Catch uncaught exceptions and log them to a file so a crash isn't silent.
        CrashReporter.install(this)

        // Remove only superseded catalog files that no durable session references.
        val protectedModels = SessionStore(this).list()
            .mapTo(mutableSetOf()) { it.modelFileName }
        LegacyModelCleanup.clean(File(filesDir, "models"), protectedModels)
    }
}
