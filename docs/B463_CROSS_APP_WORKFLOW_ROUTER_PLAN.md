# B463 — Cross-App Workflow Router

Parent exact head: `c6ebb9467058b22cf4d41038392fafdf2005a396`

B463 composes semantic capabilities across app providers using the existing AndroidCapabilityBus.

A workflow carries explicit initial inputs. Each resolved provider may add only its declared output
contracts to the input set for later steps. Missing capabilities, contract mismatches or platform
permissions stop resolution at the first failing step.

The router plans only:

- executionAuthority = false
- ownerPolicyAuthority = false
- no Android API call
- no external side effect

Actual execution remains in the existing action runtimes with just-in-time Owner Policy and platform
permission checks.
