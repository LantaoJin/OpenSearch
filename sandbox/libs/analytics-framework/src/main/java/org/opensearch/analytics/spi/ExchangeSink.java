/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.spi;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.opensearch.analytics.backend.EngineResultStream;

import java.util.List;

/**
 * Write-only interface for feeding Arrow batches into a stage exchange.
 * Producers (shard scan stages, local compute stages) call {@link #feed}
 * to push data; they never read from the sink.
 *
 * <p>Implementations are backend-specific and created via {@link ExchangeSinkProvider}.
 * A coordinator-side sink runs the root stage computation (final aggregate, sort, etc.)
 * over the batches it receives.
 *
 * <p>Implementations must be thread-safe — multiple shard response handlers
 * may call {@link #feed} concurrently.
 *
 * @opensearch.internal
 */
public interface ExchangeSink {

    /**
     * Ingest an Arrow batch into this sink. The sink takes ownership of the
     * batch and is responsible for releasing it when no longer needed.
     */
    void feed(VectorSchemaRoot batch);

    /**
     * Ingest an Arrow batch with a per-source ordinal — the index of the
     * producer task within its stage's resolved target list (e.g.
     * {@link org.opensearch.analytics.spi.ExchangeSink} consumed via
     * {@code ShardExecutionTarget.ordinal()}).
     *
     * <p>Default implementation drops the ordinal and falls through to
     * {@link #feed(VectorSchemaRoot)}. Sinks that need to discriminate
     * batches by producer (e.g. Late Materialization, where the ordinal is
     * stamped onto each batch as a column) override this method.
     *
     * <p>Producers that have a meaningful per-task ordinal call this overload;
     * producers without one continue to call {@link #feed(VectorSchemaRoot)}.
     */
    default void feed(VectorSchemaRoot batch, int sourceOrdinal) {
        feed(batch);
    }

    /**
     * Whether the downstream consumer has finished and will read no more batches (e.g. a reduce
     * whose LimitExec satisfied its fetch). Producers may poll this after a {@link #feed} to stop
     * early. Default {@code false}; best-effort — a {@code true} is monotonic, a {@code false} may
     * race a concurrent completion.
     */
    default boolean isConsumerDone() {
        return false;
    }

    /**
     * Drain {@code stream} straight into the Rust-native Arrow-Flight shuffle transport instead of
     * the per-batch {@link #feed} loop: hash-partition each batch by {@code hashKeyChannels} and push
     * partition {@code p} to {@code targetUris.get(p)}. The {@code (queryId, stageId, side)} triple
     * plus the partition index key each route on the consumer side. Blocks until the stream is fully
     * drained and every target's transfer completes.
     *
     * <p>Returns {@code true} if this sink handled the drain via Flight (the caller then skips the
     * {@link #feed}/{@link #close} loop but still emits any header frame the coordinator stream
     * needs), or {@code false} to fall back to the normal {@link #feed} loop. The default returns
     * {@code false} so sinks without a native Flight path are unaffected.
     */
    default boolean drainViaFlight(
        EngineResultStream stream,
        List<String> targetUris,
        List<Integer> hashKeyChannels,
        String queryId,
        int stageId,
        String side
    ) {
        return false;
    }

    /**
     * Signal that no more batches will be fed. Releases resources.
     */
    void close();
}
