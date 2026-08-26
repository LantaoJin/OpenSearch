/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/**
 * Arrow field implementations for boolean and binary OpenSearch data types.
 *
 * <ul>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.data.BooleanArrowField} — Maps to Arrow
 *       {@code BitVector}; stores boolean values as single-bit flags.</li>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.data.BinaryArrowField} — Maps to Arrow
 *       {@code VarBinaryVector}; stores raw byte arrays.</li>
 * </ul>
 */
package org.opensearch.dataformat.arrow.fields.core.data;
