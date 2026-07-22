package ai.focal.app.ui

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Shown on the launch AFTER a crash. Displays the recorded stack trace so the
 * problem is visible instead of the app silently closing, with a button to copy
 * it. The report also lives on disk at
 * Android/data/ai.focal.app/files/last_crash.txt.
 */
@Composable
fun CrashScreen(text: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(color = FocalColors.Deep, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Focal hit an error",
                color = FocalColors.Ink,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.headlineMedium)
            Text(
                "The last run crashed. Details below (also saved to " +
                "Android/data/ai.focal.app/files/last_crash.txt). Copy this and " +
                "send it so it can be fixed.",
                color = FocalColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))

            Row {
                Button(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(
                                ClipData.newPlainText("Focal crash report", text),
                            ))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FocalColors.Green,
                        contentColor = FocalColors.Abyss),
                ) { Text("Copy report") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = FocalColors.InkSoft),
                ) { Text("Continue") }
            }

            Spacer(Modifier.height(14.dp))
            Surface(
                color = FocalColors.Panel,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Text(
                    text = text,
                    color = FocalColors.InkSoft,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}
