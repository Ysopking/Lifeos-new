package app.lifeos.core.model.checkpoint

import java.util.UUID

@JvmInline
value class CheckpointId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid checkpoint ID" }
    }

    companion object {
        fun new(): CheckpointId = CheckpointId(UUID.randomUUID().toString())
    }
}
