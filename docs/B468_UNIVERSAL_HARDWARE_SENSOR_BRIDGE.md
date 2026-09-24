# B468 — Universal Hardware Sensor Bridge

B468 adds a transport-neutral adapter boundary for physical sensor measurements.

The path is:

HardwareSensorSource -> HardwareSensorBridgeAdapter -> InformationObservation(SENSOR) ->
AppObservationIngress -> OwnerAuthorizedAppObservationIngress -> product commit callback.

Invariants:

- sensor sources cannot mint Owner Observation Policy grants
- observation authority remains separate from external-effect authority
- source and batch cursors never move backwards
- checkpoints advance only after authorized observations have committed
- commit failure preserves the previous checkpoint and marks the sensor degraded
- unavailable, quarantined, or disabled sources are not polled
- the bridge itself does not activate hardware or grant permissions
