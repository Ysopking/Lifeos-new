# B376 — ProceduralSkillInduction — exact implementation plan

Base head: `c04b80879d4feff3993bd914025752987f65885e`
Branch: `b376-procedural-skill-induction-v1`
Module: `:core:runtime-reasoning`

## Reuse / non-duplication contract

B376 does not create a second plan or strategy-learning system:
- `GoalPlanDefinition` remains the immutable execution-plan definition.
- `GoalPlanTransition` remains the execution-progress/evidence trace.
- `GoalStepState.COMPLETED` remains the terminal successful step marker.
- `StrategyLearningCandidate` remains the independently verified strategy-learning evidence carrier.
- B374 verified learning episodes remain the experiential authority.

B376 only induces inactive procedure candidates from repeated successful plan shapes:
`executionAuthority == false`, `activationAllowed == false`, `promotionAllowed == false`.

## Prepared production code

NEW `core/runtime-reasoning/src/main/kotlin/app/lifeos/core/runtime/reasoning/ProceduralSkillInductionEngine.kt`

- L1–8: package/imports; reuse GoalPlan and StrategyLearning contracts.
- L10–31: `ProceduralSkillStep`; canonical key/objective/dependency-key/priority representation.
- L33–133: immutable `ProceduralSkillTrace`; binds exact B374 episode, GoalPlan id/fingerprint, canonical step shape, exact COMPLETED transition fingerprints and optional existing strategy-learning fingerprint.
- L63–132: `ProceduralSkillTrace.from`; requires verified episode, one exact COMPLETED transition per plan step, no foreign steps/plans, and derives plan-shape fingerprint without using execution-instance action ids as the skill identity.
- L135–149: `ProceduralSkillInductionPolicy`; minimum independent cycles and bounded trace support.
- L151–203: immutable `ProceduralSkillCandidate`; support episodes/cycles/traces and optional strategy-learning evidence; explicitly non-executable/non-active/non-promoted.
- L205–214: invariant comment: GoalPlan remains execution authority and StrategyLearning remains strategy evidence.
- L215–286: `ProceduralSkillInductionEngine.induce`; groups by exact procedural shape, collapses multiple revisions from one source cycle, requires independent cycles, derives bounded support confidence and canonical candidate identity.
- L288–293: deterministic procedural-shape fingerprint.
- L295–316: deterministic trace fingerprint.
- L318–339: deterministic candidate fingerprint.

## Prepared tests

NEW `core/runtime-reasoning/src/test/kotlin/app/lifeos/core/runtime/reasoning/ProceduralSkillInductionEngineTest.kt`

- L19–38: two verified independent cycles with same completed plan shape induce one inactive skill.
- L41–55: two revisions of one source cycle cannot fake independent procedural support.
- L58–73: unverified learning episode cannot become a procedural trace.
- L76–87: every GoalPlan step must have exactly one COMPLETED transition.
- L90–101: transitions from another plan fail closed.
- L104–113: trace ordering cannot change candidate identity.
- L116–137: existing `StrategyLearningCandidate` fingerprint is carried without granting promotion.
- L140–144: different plan shapes do not collapse into one skill.
- L147–160: canonical trace helper.
- L162–182: exact GoalPlan fixture.
- L184–205: exact terminal transition fixture.
- L207–277: exact B374 episode fixture.

## Pre-implementation verification

Before writing code:
1. confirm no existing `ProceduralSkillInductionEngine`, `ProceduralSkillTrace`, or `ProceduralSkillCandidate` collision;
2. confirm GoalPlan ids/fingerprints are content-derived and plan steps expose stable key/objective/dependencies;
3. confirm terminal COMPLETED transitions require action id + outcome Photon;
4. confirm `StrategyLearningCandidate` already requires independently verified world transitions and itself cannot promote;
5. confirm B376 remains in `:core:runtime-reasoning`, outside the 505-file `core/runtime` monolith.

## Gate

After implementation:
1. `./gradlew :core:runtime-reasoning:test --tests 'app.lifeos.core.runtime.reasoning.ProceduralSkillInductionEngineTest'`
2. `./gradlew :core:runtime-reasoning:test`
3. Core Fast after B375/B374 promotion order.

## Promotion run

B376 is cleanly restacked on merged B375/main. This exact head is the promotion candidate for main-targeted CI.
