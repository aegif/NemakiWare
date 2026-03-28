package jp.aegif.nemaki.rest.purview.journal;

/**
 * No-op emitter used when {@code lineage.mode=disabled}.
 * Silently discards all events at zero cost.
 */
public class NoopLineageEmitter implements LineageEmitter {

    @Override
    public void emit(LineageEvent event) {
        // no-op: lineage is disabled
    }

    @Override
    public boolean isActive() {
        return false;
    }
}
