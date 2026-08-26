/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/**
 * Arrow field implementations for text-based OpenSearch types.
 *
 * <p>All implementations use Arrow {@code VarCharVector} with UTF-8 encoding.
 *
 * <ul>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.data.text.TextArrowField} — Full-text fields.</li>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.data.text.KeywordArrowField} — Keyword (exact match) fields.</li>
 *   <li>{@link org.opensearch.dataformat.arrow.fields.core.data.text.IpArrowField} — IP address fields stored as binary-encoded addresses.</li>
 * </ul>
 */
package org.opensearch.dataformat.arrow.fields.core.data.text;
