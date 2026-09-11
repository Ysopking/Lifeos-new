package app.lifeos.core.runtime.capability

/**
 * Resolves the effective reliability used for selection without mutating the immutable provider
 * descriptor or any trust/promotion evidence.
 */
fun interface CapabilityReliabilityResolver {
    fun resolve(provider: CapabilityDescriptor): Double
}

object DescriptorCapabilityReliabilityResolver : CapabilityReliabilityResolver {
    override fun resolve(provider: CapabilityDescriptor): Double = provider.reliability
}
