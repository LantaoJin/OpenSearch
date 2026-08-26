/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/**
 * Arrow field implementations for OpenSearch metadata fields.
 *
 * <p>These fields handle internal OpenSearch document metadata that is stored alongside
 * user data in the data file.
 *
 * <ul>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.metadata.IdArrowField} — Document {@code _id},
 *       stored as binary ({@code VarBinaryVector}) from {@code byte[]}.</li>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.metadata.RoutingArrowField} — Document {@code _routing},
 *       stored as UTF-8 text ({@code VarCharVector}).</li>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.metadata.IgnoredArrowField} — The {@code _ignored}
 *       field, stored as UTF-8 text ({@code VarCharVector}).</li>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.metadata.SizeArrowField} — Document {@code _size},
 *       stored as 32-bit integer ({@code IntVector}).</li>
 * </ul>
 */
package org.opensearch.dataformat.arrow.fields.core.metadata;
