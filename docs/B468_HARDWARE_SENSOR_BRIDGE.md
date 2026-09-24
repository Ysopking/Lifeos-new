# B468 — Hardware Sensor Bridge

B468 binds ordinary Android device/environment sensors into the existing B451–B467 perception
grammar and productive canonical Photon path.

```text
Android SensorManager
  -> bounded/rate-limited physical sample
  -> HardwareSensorSample
  -> HardwareSensorObservationFactory
  -> InformationObservation(PROJECTED / OBSERVED / PASSIVE)
  -> AppObservationBatch
  -> CanonicalPhotonIngress.ingestSensorBatch
  -> Owner Observation Policy
  -> canonical ORIGIN Photon
  -> Continuous Cognition
```

The productive bridge currently subscribes only to ordinary local device/environment sensors:
accelerometer, gyroscope, magnetic field, light, pressure, proximity, gravity, linear acceleration,
rotation vector, relative humidity and ambient temperature.

It intentionally does not request or activate health/body-sensor permissions.

Hard invariants:

- raw sensor sample != semantic fact
- raw sensor sample != owner intent
- SensorManager availability != observation permission
- observation permission != effect authority
- no platform permission is self-granted
- Owner Observation Policy is checked for every emitted batch
- owner revocation remains effective because the grant is evaluated at ingest time
- samples are rate-limited before cognition to avoid a raw high-frequency stream flooding the field
- the bridge owns no second durable truth store
