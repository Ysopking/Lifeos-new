package app.lifeos.next.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MemoryMigrationScreen(modifier: Modifier = Modifier) {
    MigrationScreen(
        title = "Gedächtnis",
        detail = "Photon-Suche, Verlauf und Artefakte werden im nächsten Frontend-Block aus der bestehenden Gedankenansicht hierher migriert.",
        modifier = modifier,
    )
}

@Composable
fun GoalsMigrationScreen(modifier: Modifier = Modifier) {
    MigrationScreen(
        title = "Ziele",
        detail = "Durable Goals, Plan-Schritte und Next Actions werden direkt aus der produktiven Goal-Runtime projiziert.",
        modifier = modifier,
    )
}

@Composable
fun SystemMigrationScreen(modifier: Modifier = Modifier) {
    MigrationScreen(
        title = "System",
        detail = "Runtime-Health, Readiness und ToolWorkshop werden schrittweise aus den bestehenden Diagnoseflächen hierher verschoben.",
        modifier = modifier,
    )
}

@Composable
private fun MigrationScreen(
    title: String,
    detail: String,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
}
