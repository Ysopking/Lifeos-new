#!/usr/bin/env bash
set -Eeuo pipefail

kernel="app/src/main/java/app/lifeos/next/kernel/LifeOsKernel.kt"
retriever="core/language/src/main/kotlin/app/lifeos/core/language/LanguageContextRetriever.kt"
lexical="core/language/src/main/kotlin/app/lifeos/core/language/LinguisticFieldIndexV2.kt"
dispatcher="app/src/main/java/app/lifeos/next/kernel/GoalActionDispatcher.kt"
external="core/runtime/src/main/kotlin/app/lifeos/core/runtime/agency/ExternalEffectExecutor.kt"

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

echo "LANGUAGE_SEMANTIC_ARCHITECTURE_CONTRACT_OK"
