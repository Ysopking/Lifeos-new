package app.lifeos.core.runtime.informationasset

import app.lifeos.core.runtime.informationasset.code.CodeAuditInformationAssetValidationProfile
import app.lifeos.core.runtime.informationasset.code.CodeChangeProposalInformationAssetValidationProfile
import app.lifeos.core.runtime.informationasset.financial.FinancialInformationAssetValidationProfile
import app.lifeos.core.runtime.informationasset.legal.LegalInformationAssetValidationProfile
import app.lifeos.core.runtime.informationasset.organization.OrganizationInformationAssetValidationProfile
import app.lifeos.core.runtime.informationasset.project.ProjectInformationAssetValidationProfile
import app.lifeos.core.runtime.informationasset.scientific.ScientificInformationAssetValidationProfile

/**
 * Canonical profile registry for standard LIFEOS InformationAsset kinds.
 *
 * This object only composes the existing common validator with domain profiles. It does not create
 * another validation authority and deliberately leaves generic KNOWLEDGE/OTHER assets on the common
 * B4 integrity/semantic contract until a specific profile exists for them.
 */
object StandardInformationAssetValidation {
    val profiles: Map<InformationAssetKind, List<InformationAssetValidationProfile>> = linkedMapOf(
        InformationAssetKind.LEGAL to listOf(LegalInformationAssetValidationProfile()),
        InformationAssetKind.ORGANIZATION to listOf(OrganizationInformationAssetValidationProfile()),
        InformationAssetKind.FINANCIAL to listOf(FinancialInformationAssetValidationProfile()),
        InformationAssetKind.SCIENTIFIC to listOf(ScientificInformationAssetValidationProfile()),
        InformationAssetKind.CODE_AUDIT to listOf(CodeAuditInformationAssetValidationProfile()),
        InformationAssetKind.CODE_CHANGE_PROPOSAL to listOf(CodeChangeProposalInformationAssetValidationProfile()),
        InformationAssetKind.PROJECT to listOf(ProjectInformationAssetValidationProfile()),
    )

    val profiledKinds: Set<InformationAssetKind> = profiles.keys

    fun validator(): InformationAssetValidator = InformationAssetValidator(profiles)
}
