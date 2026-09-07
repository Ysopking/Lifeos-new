package app.lifeos.core.runtime.health

import java.io.*

object HealthControlCodec {
    const val MAX_BYTES = 256 * 1024
    fun encode(value: HealthControlSnapshot): ByteArray {
        validate(value)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeLong(value.revision)
            fun strings(values: Set<String>) { out.writeInt(values.size); values.sorted().forEach(out::writeUTF) }
            strings(value.quarantined)
            strings(value.safeReasons)
            out.writeInt(value.decisions.size)
            value.decisions.forEach { d ->
                out.writeLong(d.revision); out.writeUTF(d.action); out.writeUTF(d.target); out.writeUTF(d.actor)
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): HealthControlSnapshot {
        require(bytes.size <= MAX_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 1)
        val revision = input.readLong()
        fun strings(): Set<String> {
            val count = input.readInt().also { require(it in 0..1000) }
            return List(count) { input.readUTF() }.toSet().also { require(it.size == count) }
        }
        val quarantine = strings()
        val reasons = strings()
        val count = input.readInt().also { require(it in 0..100) }
        val decisions = List(count) { HealthDecision(input.readLong(), input.readUTF(), input.readUTF(), input.readUTF()) }
        require(input.available() == 0)
        return HealthControlSnapshot(revision, quarantine, reasons, decisions).also(::validate)
    }
    private fun validate(value: HealthControlSnapshot) {
        require(value.revision >= 0 && value.revision < Long.MAX_VALUE)
        require(value.quarantined.size <= 1000 && value.safeReasons.size <= 1000 && value.decisions.size <= 100)
        fun valid(text: String) = text.isNotBlank() && text.length <= 256
        require((value.quarantined + value.safeReasons).all(::valid))
        require(value.decisions.all { it.revision in 1..value.revision && valid(it.target) &&
            it.action in setOf("QUARANTINE", "SAFE_MODE", "VERIFIED_RELEASE") &&
            it.actor in setOf("runtime", "user-requested-probe") })
        require(value.decisions.zipWithNext().all { (a, b) -> a.revision <= b.revision })
    }
}
