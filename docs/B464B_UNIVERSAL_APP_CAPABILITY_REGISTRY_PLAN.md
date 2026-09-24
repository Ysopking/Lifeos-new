# B464B — Universal App Capability Registry

B464B preserves a single capability authority. Validated app candidates are promoted into the
existing CapabilityRegistry through a thin adapter; no second app registry is introduced.

Promotion evidence is bound to the exact candidate fingerprint and provider version. The resulting
descriptor can be discovered/routed, but productive use still requires the existing Android
permission and Owner Policy effect gates. Promotion evidence itself carries no effect authority.
