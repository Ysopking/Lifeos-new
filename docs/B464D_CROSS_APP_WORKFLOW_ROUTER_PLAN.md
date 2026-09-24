# B464D — Cross-App Workflow Router

B464D routes semantic capabilities across providers through the existing AndroidCapabilityBus.
Declared provider outputs are the only contracts made available to later steps.

The router plans only. It has no execution or Owner Policy authority, performs no Android call and
stops on missing capability, contract mismatch or missing platform permission. Productive action
runtimes remain responsible for just-in-time Owner Policy and outcome verification.
