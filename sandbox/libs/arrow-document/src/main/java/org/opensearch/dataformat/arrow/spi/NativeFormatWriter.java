/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataformat.arrow.spi;

import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.index.engine.dataformat.RowIdMapping;

import java.io.IOException;

/**
 * The seam between the format-agnostic Arrow document pipeline and a concrete storage format.
 *
 * <p>Everything above this interface — mapping-to-Arrow field writers, schema construction,
 * {@code VectorSchemaRoot} pooling and rotation, document input — is shared by every columnar
 * format. Everything below it is that format's native binding.
 *
 * <p>The contract is deliberately the Arrow C Data Interface, expressed as raw addresses, because
 * that is already the boundary the existing Parquet path uses and it is the same boundary Milvus
 * Loon exposes ({@code loon_writer_write(handle, ArrowArray*)}). A format implementation is
 * therefore a thin marshalling layer over a native writer, not a re-implementation of the pipeline.
 *
 * <p>Implementations are <b>not</b> required to be thread-safe; {@code VSRManager} serialises calls.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public interface NativeFormatWriter {

    /**
     * Prepares the underlying native writer. Called once, before the first {@link #write}.
     *
     * @param indexName      the index this writer belongs to, for native-side diagnostics
     * @param schemaAddress  address of an exported Arrow {@code ArrowSchema}
     * @param sortConfig     index sort to apply while writing, possibly {@link FormatSortConfig#empty()}
     * @param writerGeneration the Mustang writer generation this file belongs to
     */
    void initialize(String indexName, long schemaAddress, FormatSortConfig sortConfig, long writerGeneration)
        throws IOException;

    /** Whether {@link #initialize} has completed. */
    boolean isInitialized();

    /**
     * Consumes one Arrow batch handed over through the C Data Interface. Repeatable.
     *
     * @param arrayAddress  address of an exported Arrow {@code ArrowArray}
     * @param schemaAddress address of the matching exported {@code ArrowSchema}
     */
    void write(long arrayAddress, long schemaAddress) throws IOException;

    /**
     * Finalises the file and returns its metadata. After this call the writer is spent.
     */
    FormatFileMetadata flush() throws IOException;

    /** Metadata of the finalised file, or {@code null} before {@link #flush}. */
    FormatFileMetadata getMetadata();

    /**
     * The row-id remapping produced if the format reordered rows while writing (index sort).
     * Returns {@code null} when write order was preserved.
     */
    RowIdMapping getRowIdMapping();

    /** Releases native resources. Idempotent, and safe to call after a failed write. */
    void cleanup();

    /** The format name this writer produces, matching its {@code DataFormat.name()}. */
    String formatName();
}
