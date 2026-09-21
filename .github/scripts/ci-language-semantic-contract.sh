#!/usr/bin/env bash
set -Eeuo pipefail

kernel="app/src/main/java/app/lifeos/next/kernel/LifeOsKernel.kt"
retriever="core/language/src/main/kotlin/app/lifeos/core/language/LanguageContextRetriever.kt"
lexical="core/language/src/main/kotlin/app/lifeos/core/language/LinguisticFieldIndexV2.kt"
dispatcher="app/src/main/java/app/lifeos/next/kernel/GoalActionDispatcher.kt"
external="core/runtime/src/main/kotlin/app/lifeos/core/runtime/agency/ExternalEffectExecutor.kt"
paraphrase="core/language/src/main/kotlin/app/lifeos/core/language/PredicateParaphraseResolver.kt"
discourse="core/language/src/main/kotlin/app/lifeos/core/language/DiscourseIntentResolver.kt"
understanding="core/language/src/main/kotlin/app/lifeos/core/language/LanguageUnderstandingEngine.kt"

grep -Fq 'maxCandidates: Int = 160' "$retriever" || {
  echo "language-context-candidate-bound-missing" >&2
  exit 1
}
grep -Fq 'maxSelected: Int = 96' "$retriever" || {
  echo "language-context-selection-bound-missing" >&2
  exit 1
}
grep -Fq 'maxCandidates: Int = 12' "$lexical" || {
  echo "linguistic-field-candidate-bound-missing" >&2
  exit 1
}
if grep -Fq 'photons = mutableBootstrapState.value.photons' "$kernel"; then
  echo "language-full-bootstrap-photon-fallback-present" >&2
  exit 1
fi
grep -Fq 'LanguageContextRetriever(' "$kernel" || {
  echo "bounded-language-context-retriever-not-wired" >&2
  exit 1
}
grep -Fq 'NO_EXTERNAL_SIDE_EFFECT_WITHOUT_EXECUTABLE_SEMANTIC_ACTION' "$dispatcher" || {
  echo "semantic-external-effect-invariant-missing" >&2
  exit 1
}
grep -Fq 'allowedTransition(previous.state, state)' "$external" || {
  echo "external-effect-transition-gate-missing" >&2
  exit 1
}

grep -Fq 'SIDE_EFFECT_UNDERSTANDING_CAP = 0.74' "$paraphrase" || {
  echo "language-paraphrase-side-effect-cap-missing" >&2
  exit 1
}
grep -Fq 'No predicate frame, role, reference or execution' "$discourse" || {
  echo "language-discourse-non-authority-contract-missing" >&2
  exit 1
}
grep -Fq 'linguisticField = linguisticField' "$understanding" || {
  echo "language-field-predicate-bridge-not-wired" >&2
  exit 1
}
grep -Fq 'discourseIntentResolver.evidence' "$understanding" || {
  echo "language-discourse-evidence-not-wired" >&2
  exit 1
}

test -f core/language/src/main/kotlin/app/lifeos/core/language/DiscourseStateGraph.kt || {
  echo "language-discourse-state-graph-missing" >&2
  exit 1
}
test -f core/language/src/main/kotlin/app/lifeos/core/language/DependencySyntaxGraph.kt || {
  echo "language-dependency-syntax-missing" >&2
  exit 1
}
test -f core/language/src/main/kotlin/app/lifeos/core/language/CoreferenceResolverV4.kt || {
  echo "language-coreference-v4-missing" >&2
  exit 1
}
test -f core/language/src/main/kotlin/app/lifeos/core/language/SemanticInterpretationLattice.kt || {
  echo "language-interpretation-lattice-missing" >&2
  exit 1
}
test -f core/language/src/main/kotlin/app/lifeos/core/language/ClarificationEngine.kt || {
  echo "language-clarification-engine-missing" >&2
  exit 1
}
test -f core/language/src/main/kotlin/app/lifeos/core/language/SemanticCorrectionEngine.kt || {
  echo "language-semantic-correction-engine-missing" >&2
  exit 1
}
test -f core/language/src/main/kotlin/app/lifeos/core/language/PragmaticActResolver.kt || {
  echo "language-pragmatic-act-resolver-missing" >&2
  exit 1
}
test -f core/language/src/test/kotlin/app/lifeos/core/language/LanguageUnderstandingV2AdversarialGoldTest.kt || {
  echo "language-v2-adversarial-gold-missing" >&2
  exit 1
}
grep -Fq 'semantic-clarification-required:' core/language/src/main/kotlin/app/lifeos/core/language/SemanticExecutionGate.kt || {
  echo "language-clarification-execution-gate-missing" >&2
  exit 1
}
grep -Fq 'source = "personal-grammar/v1"' core/language/src/main/kotlin/app/lifeos/core/language/PredicateParaphraseResolver.kt || {
  echo "personal-grammar-source-contract-missing" >&2
  exit 1
}
grep -Fq 'descriptiveOnly' core/language/src/main/kotlin/app/lifeos/core/language/PragmaticActResolver.kt || {
  echo "pragmatic-descriptive-only-contract-missing" >&2
  exit 1
}

echo "LANGUAGE_UNDERSTANDING_V2_CONTRACT_OK"
echo "LANGUAGE_SEMANTIC_ARCHITECTURE_CONTRACT_OK"
