package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.runtime.health.BootAttemptStore

internal class AndroidBootAttemptStore(context: Context) : BootAttemptStore {
    private val preferences = context.getSharedPreferences("boot-health", Context.MODE_PRIVATE)
    override fun read(): Int = preferences.getInt("incomplete-starts", 0)
    override fun write(attempts: Int) {
        check(preferences.edit().putInt("incomplete-starts", attempts).commit()) {
            "Boot health could not be persisted"
        }
    }
}
