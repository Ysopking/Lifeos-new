package app.lifeos.next.ui.accessibility

object LifeOsSemantics {
    fun navigationLabel(label: String): String = label.trim()

    fun stateText(label: String, state: String): String = "${label.trim()}: ${state.trim()}"
}
