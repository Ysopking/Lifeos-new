package app.lifeos.core.runtime.resource

data class SequencedExecutionSample(
    val sequence: Long,
    val sample: ExecutionSample,
) {
    init { require(sequence > 0L) }
}

interface ExecutionSampleRepository {
    suspend fun append(sample: ExecutionSample): SequencedExecutionSample
    suspend fun readAfter(sequenceExclusive: Long, limit: Int = 256): List<SequencedExecutionSample>
    suspend fun latestCostEstimate(
        operationKind: String,
        hardwareClass: HardwareExecutionClass,
    ): SoftCostEstimate?
}
