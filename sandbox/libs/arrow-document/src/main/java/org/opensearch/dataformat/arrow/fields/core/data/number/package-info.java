/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/**
 * Arrow field implementations for all OpenSearch numeric types.
 *
 * <p>Each class maps an OpenSearch numeric type to its corresponding Arrow integer or
 * floating-point vector. All implementations use {@code setSafe()} for bounds-checked writes.
 *
 * <ul>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.data.number.ByteArrowField} — 8-bit signed ({@code TinyIntVector})</li>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.data.number.ShortArrowField} — 16-bit signed ({@code SmallIntVector})</li>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.data.number.IntegerArrowField} — 32-bit signed ({@code IntVector})</li>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.data.number.LongArrowField} — 64-bit signed ({@code BigIntVector})</li>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.data.number.UnsignedLongArrowField} — 64-bit unsigned ({@code UInt8Vector})</li>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.data.number.HalfFloatArrowField} — 16-bit float ({@code Float2Vector})</li>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.data.number.FloatArrowField} — 32-bit float ({@code Float4Vector})</li>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.data.number.DoubleArrowField} — 64-bit float ({@code Float8Vector})</li>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.data.number.TokenCountArrowField} — Token count stored as 32-bit integer</li>
 * </ul>
 */
package org.opensearch.dataformat.arrow.fields.core.data.number;
