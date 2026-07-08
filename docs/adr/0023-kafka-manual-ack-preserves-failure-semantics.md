# Kafka consumers move to manual ack, but still ack on failure

Graceful shutdown (`server.shutdown=graceful`, 30s shutdown-phase timeout) needs Kafka offsets to commit only after a message finishes processing, not on a timer — otherwise SIGTERM can land between an auto-commit tick and the listener actually finishing, losing the message. All three consumers (`SyncCommandConsumer`, `OwnershipResolvedConsumer`, `RegistryServiceDiscoveryConsumer`) move to `AckMode.MANUAL_IMMEDIATE`.

This does **not** add retry or a dead-letter topic. Each listener already catches its own exceptions and just logs them; under manual ack they still call `acknowledge()` from inside the `catch` block, so a permanently-failing message is dropped after one attempt, same as today under auto-commit. The alternative — skip the ack on failure and let Kafka redeliver — was rejected here because it turns "drop and log" into "retry forever" with no attempt cap, which is a reliability improvement that needs its own design (backoff policy, DLT topic, max attempts), not a rider on a shutdown-timing ticket.

## Consequences

A future reader who sees `AckMode.MANUAL_IMMEDIATE` may reasonably assume failed messages get redelivered — they don't. Failure handling is unchanged from today; only the *timing* of a successful commit changed (after processing, not on a timer). A DLT/retry policy is a deliberate follow-up, not an oversight.
