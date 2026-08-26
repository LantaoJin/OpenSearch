/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/**
 * Arrow field implementations for date and timestamp OpenSearch types.
 *
 * <ul>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.data.date.DateArrowField} — Millisecond-precision
 *       timestamps using Arrow {@code TimeStampMilliVector}.</li>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.data.date.DateNanosArrowField} — Nanosecond-precision
 *       timestamps using Arrow {@code TimeStampNanoVector}.</li>
 * </ul>
 */
package org.opensearch.dataformat.arrow.fields.core.data.date;
