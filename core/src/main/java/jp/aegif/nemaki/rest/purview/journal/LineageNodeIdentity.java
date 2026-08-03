/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest.purview.journal;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Who this node is, for §6-a's ACK (A-2 Slice 4a).
 *
 * <p>{@code bootId} is a fresh UUID per process — that is the whole point: an ACK proves "this
 * node is running THIS binary NOW", and a restart must invalidate it.
 *
 * <p>{@code nodeId} must survive restarts, so it is either pinned by {@code lineage.node.id} or
 * allocated once and persisted. <b>It is allocated lazily, on the first prepare/ack, never at
 * startup</b>: a deployment that never enables the barrier must write nothing at all, and an
 * identity document created eagerly would be the first thing to break that promise.
 */
@Component
public class LineageNodeIdentity {

    private final String bootId = UUID.randomUUID().toString();

    @Autowired(required = false)
    private LineageBarrierStore store;

    @Autowired(required = false)
    private LineageConfig lineageConfig;

    private volatile String resolvedNodeId;
    private java.util.function.LongSupplier clockMs = System::currentTimeMillis;
    private java.util.function.Supplier<String> idAllocator = () -> UUID.randomUUID().toString();

    public LineageNodeIdentity() {
    }

    LineageNodeIdentity(LineageBarrierStore store, LineageConfig lineageConfig,
                        java.util.function.Supplier<String> idAllocator,
                        java.util.function.LongSupplier clockMs) {
        this.store = store;
        this.lineageConfig = lineageConfig;
        this.idAllocator = idAllocator;
        this.clockMs = clockMs;
    }

    /** This process's boot id. Changes on every restart, by construction. */
    public String bootId() {
        return bootId;
    }

    /**
     * This node's durable id, allocating and persisting one on first use.
     *
     * @throws IllegalStateException when no id is configured and none can be persisted — an
     *         in-memory id would make every restart look like a different node
     */
    public String nodeId() {
        String cached = resolvedNodeId;
        if (cached != null) {
            return cached;
        }
        String configured = lineageConfig == null ? "" : lineageConfig.getNodeId();
        if (configured != null && !configured.isBlank()) {
            resolvedNodeId = configured;
            return configured;
        }
        if (store == null) {
            throw new IllegalStateException("no lineage.node.id is configured and no barrier"
                    + " store is wired — this node cannot establish a durable identity");
        }
        synchronized (this) {
            if (resolvedNodeId != null) {
                return resolvedNodeId;
            }
            String durable = store.readNodeId();
            if (durable == null) {
                durable = store.allocateNodeIdIfAbsent(idAllocator.get(), clockMs.getAsLong());
            }
            resolvedNodeId = durable;
            return durable;
        }
    }

    /** The pair as §6-a's membership entry. */
    public LineageWriteVersionBarrier.NodeRef selfRef() {
        return new LineageWriteVersionBarrier.NodeRef(nodeId(), bootId);
    }
}
