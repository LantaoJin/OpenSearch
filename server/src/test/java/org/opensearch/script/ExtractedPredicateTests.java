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
import org.opensearch.index.analysis.NamedAnalyzer;
import org.opensearch.index.mapper.KeywordFieldMapper;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.index.mapper.TextSearchInfo;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Collections;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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

    public void testTermRoutesToFieldType() {
        QueryShardContext context = mock(QueryShardContext.class);
        KeywordFieldMapper.KeywordFieldType fieldType = spy(new KeywordFieldMapper.KeywordFieldType("status"));
        Query expected = new MatchAllDocsQuery();

        when(context.fieldMapper("status")).thenReturn(fieldType);
        doReturn(expected).when(fieldType).termQuery("active", context);

        ExtractedPredicate predicate = new ExtractedPredicate.Term("status", "active");
        Query actual = predicate.toQuery(context);

        assertSame(expected, actual);
        verify(fieldType).termQuery("active", context);
    }

    public void testTermReturnsNullWhenFieldMissing() {
        QueryShardContext context = mock(QueryShardContext.class);
        when(context.fieldMapper("status")).thenReturn(null);

        ExtractedPredicate predicate = new ExtractedPredicate.Term("status", "active");
        assertNull(predicate.toQuery(context));
    }

    public void testTermSwallowsUnsupportedField() {
        QueryShardContext context = mock(QueryShardContext.class);
        KeywordFieldMapper.KeywordFieldType fieldType = spy(new KeywordFieldMapper.KeywordFieldType("status"));
        when(context.fieldMapper("status")).thenReturn(fieldType);
        doThrow(new UnsupportedOperationException("no term queries")).when(fieldType).termQuery(any(), eq(context));

        ExtractedPredicate predicate = new ExtractedPredicate.Term("status", "active");
        assertNull(predicate.toQuery(context));
    }

    public void testTermDeclinesForNonKeywordFieldType() {
        // Text fields, numeric fields, etc. all route through TermBasedFieldType.termQuery, which
        // builds a plain TermQuery against the inverted index — not equivalent to Painless'
        // `doc['f'].value == 'literal'`. Term.toQuery must decline and let the script run.
        QueryShardContext context = mock(QueryShardContext.class);
        MappedFieldType notKeyword = mock(MappedFieldType.class);
        when(context.fieldMapper("title")).thenReturn(notKeyword);

        ExtractedPredicate predicate = new ExtractedPredicate.Term("title", "Hello World");
        assertNull(predicate.toQuery(context));
        verify(notKeyword, org.mockito.Mockito.never()).termQuery(any(), any());
    }

    public void testTermDeclinesForKeywordWithNormalizer() {
        // A keyword with a normalizer lowercases / asciifolds the literal before lookup, so
        // `termQuery('Active')` matches a stored `active` even though Painless reading the raw
        // `active` and comparing against `'Active'` returns false. The gate keys off the search
        // analyzer not being the identity `Lucene.KEYWORD_ANALYZER`.
        QueryShardContext context = mock(QueryShardContext.class);
        KeywordFieldMapper.KeywordFieldType fieldType = spy(new KeywordFieldMapper.KeywordFieldType("status"));
        NamedAnalyzer normalizer = new NamedAnalyzer(
            "lowercase",
            org.opensearch.index.analysis.AnalyzerScope.INDEX,
            new org.apache.lucene.analysis.core.WhitespaceAnalyzer()
        );
        org.apache.lucene.document.FieldType luceneFieldType = new org.apache.lucene.document.FieldType();
        luceneFieldType.setTokenized(false);
        luceneFieldType.setOmitNorms(true);
        luceneFieldType.freeze();
        doReturn(new TextSearchInfo(luceneFieldType, null, normalizer, normalizer)).when(fieldType).getTextSearchInfo();
        when(context.fieldMapper("status")).thenReturn(fieldType);

        assertNull(
            "keyword-with-normalizer must decline the rewrite",
            new ExtractedPredicate.Term("status", "Active").toQuery(context)
        );
        verify(fieldType, org.mockito.Mockito.never()).termQuery(any(), any());
    }

    public void testTermEqualsAndHashCode() {
        ExtractedPredicate.Term a = new ExtractedPredicate.Term("status", "active");
        ExtractedPredicate.Term b = new ExtractedPredicate.Term("status", "active");
        ExtractedPredicate.Term c = new ExtractedPredicate.Term("status", "pending");
        ExtractedPredicate.Term d = new ExtractedPredicate.Term("tier", "active");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, d);
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
