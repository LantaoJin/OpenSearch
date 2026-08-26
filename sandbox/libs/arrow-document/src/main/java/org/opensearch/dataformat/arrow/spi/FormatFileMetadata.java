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
 * Metadata of one finalised data file, in the format-independent subset Mustang needs in order to
 * build a {@link org.opensearch.index.engine.exec.WriterFileSet}.
 *
 * <p>{@code numRows} feeds {@code WriterFileSet.numRows}; {@code formatVersion} feeds
 * {@code WriterFileSet.formatVersion}; {@code crc32} feeds the pre-computed-checksum path that
 * lets the upload layer avoid re-reading the file.
 *
 * <p>{@code fileSizeBytes} and {@code footerSizeBytes} are carried because they let a reader skip
 * an object-store HEAD request and fetch a footer in a single GET — the optimisation Loon records
 * as {@code file_size} / {@code footer_size} properties on each column-group file. A format that
 * does not know them reports {@link #UNKNOWN_SIZE}.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public record FormatFileMetadata(
    int formatVersion,
    long numRows,
    String createdBy,
    long crc32,
    int numBlocks,
    long fileSizeBytes,
    long footerSizeBytes
) {
    /** Sentinel for a size a format cannot report. */
    public static final long UNKNOWN_SIZE = -1L;

    /** Convenience constructor for formats that report neither file nor footer size. */
    public FormatFileMetadata(int formatVersion, long numRows, String createdBy, long crc32, int numBlocks) {
        this(formatVersion, numRows, createdBy, crc32, numBlocks, UNKNOWN_SIZE, UNKNOWN_SIZE);
    }

    /** Whether a reader can use {@link #fileSizeBytes} to skip a size probe. */
    public boolean hasFileSize() {
        return fileSizeBytes != UNKNOWN_SIZE;
    }

    /** Whether a reader can use {@link #footerSizeBytes} for a single-IO footer read. */
    public boolean hasFooterSize() {
        return footerSizeBytes != UNKNOWN_SIZE;
    }
}
