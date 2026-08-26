/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataformat.arrow.spi;

import org.opensearch.common.annotation.ExperimentalApi;

/**
 * Counters the shared Arrow ingest pipeline emits. A concrete format's stats tracker implements
 * this so the pipeline can record without knowing which format it feeds.
 *
 * <p>These are exactly the events {@code VSRManager} produces; a format that does not surface a
 * particular counter can no-op it, and {@link #NOOP} is available for tests and for formats that
 * expose no stats at all.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public interface ArrowIngestStats {

    /** A {@code VectorSchemaRoot} was rotated out for writing. */
    void incVsrRotations();

    /** The native writer rejected a batch, typically on an off-heap budget breach. */
    void incNativeWriteRejections();

    /** A background (asynchronous) rotation write was submitted. */
    void incBackgroundWriteTotal();

    /** A background rotation write exceeded its wait budget. */
    void incBackgroundWriteTimeouts();

    /** A background rotation write failed. */
    void incBackgroundWriteFailures();

    /** Records how long a caller waited on a background rotation write. */
    void addBackgroundWriteWaitMillis(long millis);

    /** Discards every counter. Useful in tests and for formats without stats. */
    ArrowIngestStats NOOP = new ArrowIngestStats() {
        @Override
        public void incVsrRotations() {}

        @Override
        public void incNativeWriteRejections() {}

        @Override
        public void incBackgroundWriteTotal() {}

        @Override
        public void incBackgroundWriteTimeouts() {}

        @Override
        public void incBackgroundWriteFailures() {}

        @Override
        public void addBackgroundWriteWaitMillis(long millis) {}
    };
}
