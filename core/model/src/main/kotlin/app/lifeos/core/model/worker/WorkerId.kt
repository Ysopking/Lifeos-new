package app.lifeos.core.model.worker

@JvmInline
value class WorkerId(val value: String) {
    init {
        require(value.isNotBlank()) { "Worker ID must not be blank" }
    }
}
