/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.backend;

import org.apache.arrow.vector.types.pojo.Schema;

import java.util.Iterator;

/**
 * A closeable stream of record batches returned by engine execution.
 * Callers iterate batches via the returned iterator and MUST close the stream
 * when done to release native resources.
 *
 * @opensearch.internal
 */
public interface EngineResultStream extends AutoCloseable {

    /**
     * Returns an iterator over the record batches in this stream.
     * Each call returns the same iterator instance — the stream is single-pass.
     */
    Iterator<EngineResultBatch> iterator();

    /**
     * Returns this stream's output Arrow schema WITHOUT consuming a batch, or {@code null} if the
     * backend cannot report it up front. Used by the Flight-shuffle producer drain: when the native
     * transport drains the stream directly (bypassing the {@code iterator()} feed loop), the engine
     * still emits a zero-row, schema-bearing header frame on the coordinator-bound stream, and it needs
     * the schema before the data is drained. The default returns {@code null} (schema unknown until the
     * first batch), which keeps the non-Flight path unchanged.
     */
    default Schema schema() {
        return null;
    }

    @Override
    void close();
}
