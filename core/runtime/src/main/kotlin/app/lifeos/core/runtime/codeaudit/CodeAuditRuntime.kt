package app.lifeos.core.runtime.codeaudit

import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.CanonicalPhotonState
import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.informationasset.InformationAssetAssembler
import app.lifeos.core.runtime.informationasset.InformationAssetAssemblyRequest
import app.lifeos.core.runtime.informationasset.InformationAssetRevision
import app.lifeos.core.runtime.informationasset.InformationAssetValidationReport
import app.lifeos.core.runtime.informationasset.InformationAssetValidator
import app.lifeos.core.runtime.informationasset.InformationClaim
import app.lifeos.core.runtime.informationasset.InformationClaimState
import app.lifeos.core.runtime.informationasset.InformationEvidenceBinding
import app.lifeos.core.runtime.informationasset.PhotonRevisionReference
import app.lifeos.core.runtime.informationasset.StandardInformationDomains
import app.lifeos.core.runtime.informationasset.code.CodeAuditFinding
import app.lifeos.core.runtime.informationasset.code.CodeAuditInformationAssetFactory
import app.lifeos.core.runtime.informationasset.code.CodeAuditInformationAssetValidationProfile
import app.lifeos.core.runtime.informationasset.code.CodeAuditSemanticKeys
import app.lifeos.core.runtime.informationasset.code.CodeAuditSeverity
import app.lifeos.core.runtime.informationasset.code.CodeLocation
import java.time.Instant

/** Exact immutable source input for one repository audit. */
data class CodeAuditSourceFile(
    val path: String,
    val photon: Photon,
    val authority: SourceAuthority = SourceAuthority.USER_PROVIDED,
) {
    init {
        require(path.isNotBlank()) { "Code audit source path must not be blank" }
        require(!path.startsWith('/')) { "Code audit source path must be repository relative" }
        require(".." !in path.split('/')) { "Code audit source path must not traverse outside the repository" }
    }
}

data class RepositoryCodeSnapshot(
    val sourceCommit: String,
    val sourceFiles: List<CodeAuditSourceFile>,
    val fingerprint: String,
) {
    init {
        require(sourceCommit.isNotBlank()) { "Code audit source commit must not be blank" }
        require(sourceFiles.isNotEmpty()) { "Code audit snapshot requires source files" }
        require(sourceFiles.map { it.path }.distinct().size == sourceFiles.size) {
            "Code audit snapshot paths must be unique"
        }
        require(fingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Code audit snapshot fingerprint must be lowercase SHA-256"
        }
    }

    companion object {
        fun create(sourceCommit: String, sourceFiles: List<CodeAuditSourceFile>): RepositoryCodeSnapshot {
            val canonicalFiles = sourceFiles.sortedBy { it.path }
            val fingerprint = StableFieldIds.fingerprint(
                "repository-code-snapshot/v1",
                sourceCommit,
                *canonicalFiles.flatMap { file ->
                    listOf(
                        file.path,
                        file.photon.id.value,
                        file.photon.revision.toString(),
                        CanonicalPhotonState.inputHash(file.photon).value,
                        CanonicalPhotonState.semanticHash(file.photon).value,
                        file.authority.name,
                    )
                }.toTypedArray(),
            )
            return RepositoryCodeSnapshot(sourceCommit, canonicalFiles, fingerprint)
        }
    }
}

fun interface CodeAuditRule {
    fun inspect(snapshot: RepositoryCodeSnapshot): List<CodeAuditFinding>
}

/**
 * Conservative first rule: direct PhotonRepository writes are suspicious when source code does not
 * also reference the canonical ingress boundary. It reports evidence only; it never edits source.
 */
object DirectPhotonStoreWriteRule : CodeAuditRule {
    override fun inspect(snapshot: RepositoryCodeSnapshot): List<CodeAuditFinding> =
        snapshot.sourceFiles.mapNotNull { source ->
            val lines = source.photon.content.lines()
            val writeLine = lines.indexOfFirst { line ->
                (".save(" in line || " save(" in line) &&
                    ("photon" in line.lowercase() || "PhotonRepository" in source.photon.content)
            }
            val canonicalIngressReferenced =
                "CanonicalPhotonIngress" in source.photon.content ||
                    "ArtifactPhotonIngress" in source.photon.content
            if (writeLine < 0 || canonicalIngressReferenced) {
                null
            } else {
                CodeAuditFinding(
                    key = "photon-ingress-bypass",
                    location = CodeLocation(source.path, writeLine + 1, writeLine + 1),
                    severity = CodeAuditSeverity.HIGH,
                    summary = "Direct Photon persistence may bypass canonical durable cognition ingress",
                    recommendation = "Route productive Photon writes through the canonical ingress boundary or document why the write is non-productive/staged.",
                )
            }
        }
}

data class CodeAuditRun(
    val snapshot: RepositoryCodeSnapshot,
    val findings: List<CodeAuditFinding>,
    val revision: InformationAssetRevision,
    val validation: InformationAssetValidationReport,
) {
    init {
        require(validation.revisionId == revision.manifest.id) {
            "Code audit validation must describe the produced InformationAsset revision"
        }
    }
}

/**
 * Read-only deterministic code auditor. It turns exact repository source Photons into a CODE_AUDIT
 * InformationAsset. Mutation, patch creation, BuildStudio and activation are intentionally outside
 * this block.
 */
class CodeAuditCoordinator(
    private val rules: List<CodeAuditRule> = listOf(DirectPhotonStoreWriteRule),
    private val assembler: InformationAssetAssembler = InformationAssetAssembler(),
    private val validator: InformationAssetValidator = InformationAssetValidator(
        profiles = mapOf(
            app.lifeos.core.runtime.informationasset.InformationAssetKind.CODE_AUDIT to
                listOf(CodeAuditInformationAssetValidationProfile()),
        ),
    ),
) {
    fun audit(
        namespace: String,
        stableKey: String,
        title: String,
        sourceCommit: String,
        sourceFiles: List<CodeAuditSourceFile>,
        evaluatedAt: Instant,
    ): CodeAuditRun {
        val snapshot = RepositoryCodeSnapshot.create(sourceCommit, sourceFiles)
        val findings = rules
            .sortedBy { it::class.qualifiedName.orEmpty() }
            .flatMap { it.inspect(snapshot) }
            .distinctBy { findingFingerprint(it) }
            .sortedWith(
                compareByDescending<CodeAuditFinding> { it.severity.ordinal }
                    .thenBy { it.location?.path.orEmpty() }
                    .thenBy { it.location?.startLine ?: Int.MAX_VALUE }
                    .thenBy { it.key }
            )

        val evidenceByPath = snapshot.sourceFiles.associate { source ->
            source.path to InformationEvidenceBinding.create(
                source = PhotonRevisionReference.from(source.photon),
                fieldEvidenceId = null,
                domainId = StandardInformationDomains.CODE,
                authority = source.authority,
                confidence = 1.0,
                reliability = EvidenceReliability(1.0, "Exact repository source Photon"),
                validity = TemporalValidity.UNBOUNDED,
                observedAt = source.photon.provenance.createdAt,
                payloadFingerprint = StableFieldIds.fingerprint(
                    "code-audit-source/v1",
                    source.path,
                    source.photon.id.value,
                    source.photon.revision.toString(),
                    CanonicalPhotonState.inputHash(source.photon).value,
                ),
            )
        }
        val allEvidenceIds = evidenceByPath.values.mapTo(linkedSetOf()) { it.id }

        fun claim(
            key: String,
            statement: String,
            evidenceIds: Set<app.lifeos.core.runtime.informationasset.InformationEvidenceBindingId> = allEvidenceIds,
            confidence: Double = 1.0,
        ): InformationClaim = InformationClaim.create(
            domainId = StandardInformationDomains.CODE,
            semanticKey = key,
            statement = statement,
            state = InformationClaimState.SUPPORTED,
            confidence = confidence,
            evidenceBindingIds = evidenceIds,
            explanation = "Deterministic code audit over exact repository source revisions",
        )

        val claims = mutableListOf<InformationClaim>()
        claims += claim(CodeAuditSemanticKeys.SCOPE, "Repository snapshot ${snapshot.fingerprint}")
        claims += claim(CodeAuditSemanticKeys.TARGET, "Source commit ${snapshot.sourceCommit}")

        if (findings.isEmpty()) {
            claims += claim(
                CodeAuditSemanticKeys.FINDING,
                "No finding produced by the configured deterministic audit rules",
            )
            claims += claim(CodeAuditSemanticKeys.SEVERITY, CodeAuditSeverity.INFO.name)
            claims += claim(CodeAuditSemanticKeys.EVIDENCE, "${snapshot.sourceFiles.size} exact source file revisions inspected")
            claims += claim(CodeAuditSemanticKeys.RECOMMENDATION, "No change proposed by this audit run")
        } else {
            findings.forEach { finding ->
                val path = finding.location?.path
                val evidenceIds = path?.let { evidenceByPath[it]?.id?.let(::setOf) } ?: allEvidenceIds
                claims += claim(CodeAuditSemanticKeys.FINDING, finding.summary, evidenceIds)
                claims += claim(CodeAuditSemanticKeys.SEVERITY, finding.severity.name, evidenceIds)
                claims += claim(
                    CodeAuditSemanticKeys.EVIDENCE,
                    finding.location?.let { "${it.path}:${it.startLine ?: 1}" } ?: snapshot.fingerprint,
                    evidenceIds,
                )
                claims += claim(CodeAuditSemanticKeys.RECOMMENDATION, finding.recommendation, evidenceIds)
            }
        }

        val request = CodeAuditInformationAssetFactory.request(namespace, stableKey, title)
        val revision = assembler.assemble(
            InformationAssetAssemblyRequest(
                request = request,
                sourcePhotons = snapshot.sourceFiles.map { it.photon },
                evidenceBindings = evidenceByPath.values.toList(),
                claims = claims,
                participatingModules = setOf("code-auditor-v1"),
            )
        ).revision
        val validation = validator.validate(revision, evaluatedAt)
        require(validation.isValid) {
            "Code audit produced an invalid InformationAsset: " +
                validation.violations.joinToString { "${it.code}:${it.path}" }
        }
        return CodeAuditRun(snapshot, findings, revision, validation)
    }

    private fun findingFingerprint(finding: CodeAuditFinding): String = StableFieldIds.fingerprint(
        "code-audit-finding/v1",
        finding.key,
        finding.location?.path.orEmpty(),
        finding.location?.startLine?.toString().orEmpty(),
        finding.location?.endLine?.toString().orEmpty(),
        finding.severity.name,
        finding.summary,
        finding.recommendation,
    )
}
