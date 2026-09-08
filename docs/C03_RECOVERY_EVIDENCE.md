# Recovery evidence gate

A recovery action is no longer accepted solely because its executor returned success. Each `RecoveryPlan` must provide one or more read-only verification probes. The coordinator collects bounded composite evidence after a provisional action success and marks the target healthy only when every probe reports `HEALTHY`.

`DEGRADED`, `UNHEALTHY`, `UNAVAILABLE`, probe exceptions, or missing verification evidence cannot release quarantine. Cancellation always propagates.
