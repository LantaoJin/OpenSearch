/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/**
 * The {@code DocumentInput} implementation that feeds the shared Arrow pipeline.
 *
 * <p>{@link org.opensearch.dataformat.arrow.document.ArrowDocumentInput} collects
 * {@code (MappedFieldType, value)} pairs for the fields a given {@code DataFormat} was assigned,
 * enforcing single-value semantics. A concrete format's {@code Writer} then hands the collected
 * pairs to {@link org.opensearch.dataformat.arrow.vsr.VSRManager}, which turns them into Arrow
 * batches and pushes them through the format's
 * {@link org.opensearch.dataformat.arrow.spi.NativeFormatWriter}.
 *
 * @see org.opensearch.dataformat.arrow.spi.NativeFormatWriter
 */
package org.opensearch.dataformat.arrow.document;
