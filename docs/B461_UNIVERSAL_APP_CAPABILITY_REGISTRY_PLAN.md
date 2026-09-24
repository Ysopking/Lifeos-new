# B461 — Universal App Capability Registry

Parent exact head: `4d0842bf22d017766fba7366861936dccd7d05f6`

B461 deliberately does **not** add another provider registry. The adapter promotes validated app
capability candidates into the existing canonical CapabilityRegistry.

Interface stability order is represented explicitly:

official API > content provider > intent > deep link > share target > notification action >
semantic accessibility > visual interaction > coordinate automation.

Candidate discovery carries no activation, execution or Owner Policy authority. Promotion requires
shadow/contract evidence bound to the exact candidate fingerprint and provider version. Productive
actions remain behind existing Android permission and OwnerPolicy effect gates.
