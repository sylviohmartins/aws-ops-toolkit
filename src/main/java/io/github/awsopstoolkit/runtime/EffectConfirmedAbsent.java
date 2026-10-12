package io.github.awsopstoolkit.runtime;

/**
 * Reconciliation may raise this only after an authoritative read proves that the prior remote
 * effect was not applied. The engine can then safely reset the effect to NOT_SENT and dispatch it
 * again under the original idempotency contract.
 */
public final class EffectConfirmedAbsent extends RuntimeException {
    public EffectConfirmedAbsent() {
        super("Reconciliation confirmed that the prior effect was not applied");
    }
}
