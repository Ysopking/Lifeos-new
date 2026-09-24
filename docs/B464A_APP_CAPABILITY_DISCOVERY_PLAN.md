# B464A — Explicit App Capability Discovery

This completion block fills the original universal-app track without replacing the B460–B464
observation/re-observation work already present.

The classifier accepts only already-authorized AppSurfaceEvidence. It never enumerates apps, requests
permissions or activates a provider. A match is a non-authoritative candidate and preserves the
interface stability order:

official API > content provider > intent > deep link > share > notification action > semantic
accessibility > visual interaction > coordinate automation.

Surface existence is not Owner intent, permission or execution authority.
