package app.lifeos.core.runtime

interface FieldRegistry {
    fun activeFields(): List<ForceField>
}

class StaticFieldRegistry(fields: List<ForceField>) : FieldRegistry {
    private val snapshot = fields.toList()

    override fun activeFields(): List<ForceField> = snapshot
}
