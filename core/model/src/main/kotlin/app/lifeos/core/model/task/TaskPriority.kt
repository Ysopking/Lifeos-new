package app.lifeos.core.model.task

enum class TaskPriority(val weight: Int) {
    BACKGROUND(10),
    NORMAL(50),
    HIGH(75),
    INTERACTIVE(90),
    CRITICAL(100),
}
