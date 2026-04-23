/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.script;

import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.test.OpenSearchTestCase;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ExtractedPredicateTests extends OpenSearchTestCase {

    public void testRangeRoutesToFieldType() {
        QueryShardContext context = mock(QueryShardContext.class);
        MappedFieldType fieldType = mock(MappedFieldType.class);
        Query expected = new MatchAllDocsQuery();

        when(context.fieldMapper("price")).thenReturn(fieldType);
        when(fieldType.rangeQuery(any(), any(), eq(true), eq(false), any(), any(), any(), eq(context))).thenReturn(expected);

        ExtractedPredicate predicate = new ExtractedPredicate.Range("price", 10L, 100L, true, false);
        Query actual = predicate.toQuery(context);

        assertSame(expected, actual);
        verify(fieldType).rangeQuery(10L, 100L, true, false, null, null, null, context);
    }

    public void testRangeReturnsNullWhenFieldMissing() {
        QueryShardContext context = mock(QueryShardContext.class);
        when(context.fieldMapper("price")).thenReturn(null);

        ExtractedPredicate predicate = new ExtractedPredicate.Range("price", 0L, 10L, true, true);
        assertNull(predicate.toQuery(context));
    }

    public void testRangeSwallowsUnsupportedField() {
        QueryShardContext context = mock(QueryShardContext.class);
        MappedFieldType fieldType = mock(MappedFieldType.class);
        when(context.fieldMapper("price")).thenReturn(fieldType);
        when(fieldType.rangeQuery(any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), eq(context))).thenThrow(
            new IllegalArgumentException("field [price] does not support range queries")
        );

        ExtractedPredicate predicate = new ExtractedPredicate.Range("price", 0L, 10L, true, true);
        assertNull(predicate.toQuery(context));
    }

    public void testEqualsAndHashCode() {
        ExtractedPredicate.Range a = new ExtractedPredicate.Range("price", 1L, 10L, true, false);
        ExtractedPredicate.Range b = new ExtractedPredicate.Range("price", 1L, 10L, true, false);
        ExtractedPredicate.Range c = new ExtractedPredicate.Range("price", 1L, 11L, true, false);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
