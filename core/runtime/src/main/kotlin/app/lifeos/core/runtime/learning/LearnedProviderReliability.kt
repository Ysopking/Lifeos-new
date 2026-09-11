package app.lifeos.core.runtime.learning

import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityReliabilityResolver

class LearnedProviderReliabilityResolver(
    private val ledger: DurableLearningAdaptationLedger,
) : CapabilityReliabilityResolver {
    override fun resolve(provider: CapabilityDescriptor): Double = ledger.effectiveValue(
        target = LearningAdaptationTarget(
            kind = LearningAdaptationTargetKind.PROVIDER_RELIABILITY,
            key = provider.providerId,
        ),
        baselineValue = provider.reliability,
    )
}
