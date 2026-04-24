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

    public void testRangeResolvesParamRefAndPassesToFieldType() {
        QueryShardContext context = mock(QueryShardContext.class);
        MappedFieldType fieldType = mock(MappedFieldType.class);
        Query expected = new MatchAllDocsQuery();
        when(context.fieldMapper("price")).thenReturn(fieldType);
        when(fieldType.rangeQuery(any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), eq(context))).thenReturn(expected);

        ExtractedPredicate predicate = new ExtractedPredicate.Range("price", new ExtractedPredicate.ParamRef("threshold"), null, false, true);
        Query actual = predicate.toQuery(context, Collections.singletonMap("threshold", 10L));

        assertSame(expected, actual);
        // The resolved Long flows straight through to rangeQuery; no coercion happens in the carrier.
        verify(fieldType).rangeQuery(10L, null, false, true, null, null, null, context);
    }

    public void testRangeDeclinesStringParam() {
        // Painless compiles `long > Object` via DefMath.gt(Object, Object), which throws
        // ClassCastException when the RHS is a String. The rewrite must not be more permissive
        // than the script: decline so the script runs and the user sees the same exception.
        QueryShardContext context = mock(QueryShardContext.class);
        MappedFieldType fieldType = mock(MappedFieldType.class);
        when(context.fieldMapper("price")).thenReturn(fieldType);

        ExtractedPredicate predicate = new ExtractedPredicate.Range("price", new ExtractedPredicate.ParamRef("threshold"), null, false, true);
        assertNull(predicate.toQuery(context, Collections.singletonMap("threshold", "10")));
        verify(fieldType, org.mockito.Mockito.never()).rangeQuery(any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any());
    }

    public void testRangeDeclinesWhenParamIsMissing() {
        // Missing param: Painless would have thrown. Decline so the script runs and the user
        // sees the exception rather than a silently wrong match-all range.
        QueryShardContext context = mock(QueryShardContext.class);
        MappedFieldType fieldType = mock(MappedFieldType.class);
        when(context.fieldMapper("price")).thenReturn(fieldType);

        ExtractedPredicate predicate = new ExtractedPredicate.Range("price", new ExtractedPredicate.ParamRef("threshold"), null, false, true);
        assertNull(predicate.toQuery(context, Collections.emptyMap()));
        verify(fieldType, org.mockito.Mockito.never()).rangeQuery(any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any());
    }

    public void testRangeDeclinesWhenParamIsNotNumberOrString() {
        QueryShardContext context = mock(QueryShardContext.class);
        MappedFieldType fieldType = mock(MappedFieldType.class);
        when(context.fieldMapper("price")).thenReturn(fieldType);

        ExtractedPredicate predicate = new ExtractedPredicate.Range("price", new ExtractedPredicate.ParamRef("threshold"), null, false, true);
        assertNull(predicate.toQuery(context, Collections.singletonMap("threshold", java.util.Arrays.asList(1, 2))));
        assertNull(predicate.toQuery(context, Collections.singletonMap("threshold", Boolean.TRUE)));
        verify(fieldType, org.mockito.Mockito.never()).rangeQuery(any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any());
    }

    public void testRangeResolvesBothBoundsFromParams() {
        QueryShardContext context = mock(QueryShardContext.class);
        MappedFieldType fieldType = mock(MappedFieldType.class);
        when(context.fieldMapper("price")).thenReturn(fieldType);
        when(fieldType.rangeQuery(any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), eq(context))).thenReturn(
            new MatchAllDocsQuery()
        );

        ExtractedPredicate predicate = new ExtractedPredicate.Range(
            "price",
            new ExtractedPredicate.ParamRef("low"),
            new ExtractedPredicate.ParamRef("high"),
            false,
            false
        );
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("low", 5L);
        params.put("high", 50L);
        assertNotNull(predicate.toQuery(context, params));
        verify(fieldType).rangeQuery(5L, 50L, false, false, null, null, null, context);
    }

    public void testRangeMixedLiteralAndParamBounds() {
        // One side literal, one side param — the literal passes through unresolved and the param
        // resolves against the map.
        QueryShardContext context = mock(QueryShardContext.class);
        MappedFieldType fieldType = mock(MappedFieldType.class);
        when(context.fieldMapper("price")).thenReturn(fieldType);
        when(fieldType.rangeQuery(any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), eq(context))).thenReturn(
            new MatchAllDocsQuery()
        );

        ExtractedPredicate predicate = new ExtractedPredicate.Range("price", 10L, new ExtractedPredicate.ParamRef("high"), false, false);
        assertNotNull(predicate.toQuery(context, Collections.singletonMap("high", 100L)));
        verify(fieldType).rangeQuery(10L, 100L, false, false, null, null, null, context);
    }

    public void testParamRefEqualsAndHashCode() {
        ExtractedPredicate.ParamRef a = new ExtractedPredicate.ParamRef("x");
        ExtractedPredicate.ParamRef b = new ExtractedPredicate.ParamRef("x");
        ExtractedPredicate.ParamRef c = new ExtractedPredicate.ParamRef("y");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
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

    public void testTermResolvesStringParamAndPassesToFieldType() {
        QueryShardContext context = mock(QueryShardContext.class);
        KeywordFieldMapper.KeywordFieldType fieldType = spy(new KeywordFieldMapper.KeywordFieldType("status"));
        Query expected = new MatchAllDocsQuery();
        when(context.fieldMapper("status")).thenReturn(fieldType);
        doReturn(expected).when(fieldType).termQuery("active", context);

        ExtractedPredicate predicate = new ExtractedPredicate.Term("status", new ExtractedPredicate.ParamRef("expected"));
        Query actual = predicate.toQuery(context, Collections.singletonMap("expected", "active"));

        assertSame(expected, actual);
        verify(fieldType).termQuery("active", context);
    }

    public void testTermDeclinesWhenParamIsMissing() {
        // Painless' .equals on a null param returns false. We preserve that by declining; the
        // script runs and matches nothing. Accepting would mean passing null to termQuery which
        // most field types treat as NPE or match-nothing — silent behavior change.
        QueryShardContext context = mock(QueryShardContext.class);
        KeywordFieldMapper.KeywordFieldType fieldType = spy(new KeywordFieldMapper.KeywordFieldType("status"));
        when(context.fieldMapper("status")).thenReturn(fieldType);

        ExtractedPredicate predicate = new ExtractedPredicate.Term("status", new ExtractedPredicate.ParamRef("expected"));
        assertNull(predicate.toQuery(context, Collections.emptyMap()));
        verify(fieldType, org.mockito.Mockito.never()).termQuery(any(), any());
    }

    public void testTermDeclinesWhenParamIsNumber() {
        // `doc['status'].value == params.expected` with `expected = 123` Painless-evaluates to
        // `"active".equals(Integer(123))` = false. The rewrite would stringify via
        // `BytesRefs.toBytesRef(123)` = "123" and match docs whose keyword literally is "123" —
        // silently more permissive. Decline.
        QueryShardContext context = mock(QueryShardContext.class);
        KeywordFieldMapper.KeywordFieldType fieldType = spy(new KeywordFieldMapper.KeywordFieldType("status"));
        when(context.fieldMapper("status")).thenReturn(fieldType);

        ExtractedPredicate predicate = new ExtractedPredicate.Term("status", new ExtractedPredicate.ParamRef("expected"));
        assertNull(predicate.toQuery(context, Collections.singletonMap("expected", 123)));
        verify(fieldType, org.mockito.Mockito.never()).termQuery(any(), any());
    }

    public void testTermDeclinesWhenParamIsBooleanOrList() {
        QueryShardContext context = mock(QueryShardContext.class);
        KeywordFieldMapper.KeywordFieldType fieldType = spy(new KeywordFieldMapper.KeywordFieldType("status"));
        when(context.fieldMapper("status")).thenReturn(fieldType);

        ExtractedPredicate predicate = new ExtractedPredicate.Term("status", new ExtractedPredicate.ParamRef("expected"));
        assertNull(predicate.toQuery(context, Collections.singletonMap("expected", Boolean.TRUE)));
        assertNull(predicate.toQuery(context, Collections.singletonMap("expected", java.util.Arrays.asList("a", "b"))));
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

    public void testTermsRoutesToFieldType() {
        QueryShardContext context = mock(QueryShardContext.class);
        KeywordFieldMapper.KeywordFieldType fieldType = spy(new KeywordFieldMapper.KeywordFieldType("status"));
        Query expected = new MatchAllDocsQuery();
        when(context.fieldMapper("status")).thenReturn(fieldType);
        java.util.List<Object> values = java.util.Arrays.asList("active", "pending");
        doReturn(expected).when(fieldType).termsQuery(values, context);

        ExtractedPredicate predicate = new ExtractedPredicate.Terms("status", values);
        Query actual = predicate.toQuery(context);

        assertSame(expected, actual);
        verify(fieldType).termsQuery(values, context);
    }

    public void testTermsDeclinesForNonKeywordFieldType() {
        QueryShardContext context = mock(QueryShardContext.class);
        MappedFieldType notKeyword = mock(MappedFieldType.class);
        when(context.fieldMapper("title")).thenReturn(notKeyword);

        ExtractedPredicate predicate = new ExtractedPredicate.Terms("title", java.util.Arrays.asList("a", "b"));
        assertNull(predicate.toQuery(context));
        verify(notKeyword, org.mockito.Mockito.never()).termsQuery(any(), any());
    }

    public void testTermsDeclinesForKeywordWithNormalizer() {
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

        assertNull(new ExtractedPredicate.Terms("status", java.util.Arrays.asList("Active", "Pending")).toQuery(context));
        verify(fieldType, org.mockito.Mockito.never()).termsQuery(any(), any());
    }

    public void testTermDeclinesForNonStringLiteralValue() {
        // Defense-in-depth: the Term constructor takes a raw Object. If a third-party factory
        // (or a future phase change) hands us a Number or other non-String, the rewrite must
        // decline rather than stringify via BytesRefs.toBytesRef and silently match a keyword
        // whose indexed bytes equal that stringification.
        QueryShardContext context = mock(QueryShardContext.class);
        KeywordFieldMapper.KeywordFieldType fieldType = spy(new KeywordFieldMapper.KeywordFieldType("status"));
        when(context.fieldMapper("status")).thenReturn(fieldType);

        assertNull(new ExtractedPredicate.Term("status", 123).toQuery(context));
        assertNull(new ExtractedPredicate.Term("status", Boolean.TRUE).toQuery(context));
        verify(fieldType, org.mockito.Mockito.never()).termQuery(any(), any());
    }

    public void testTermsPerElementResolution() {
        // A homogeneous String list passes through unchanged.
        QueryShardContext context = mock(QueryShardContext.class);
        KeywordFieldMapper.KeywordFieldType fieldType = spy(new KeywordFieldMapper.KeywordFieldType("status"));
        Query expected = new MatchAllDocsQuery();
        when(context.fieldMapper("status")).thenReturn(fieldType);
        java.util.List<Object> values = java.util.Arrays.asList("active", "pending");
        doReturn(expected).when(fieldType).termsQuery(values, context);

        ExtractedPredicate predicate = new ExtractedPredicate.Terms("status", values);
        assertSame(expected, predicate.toQuery(context, Collections.emptyMap()));
    }

    public void testTermsResolvesParamRefElements() {
        // String literal mixed with a ParamRef that resolves to a String — both should flow
        // through to termsQuery as Strings.
        QueryShardContext context = mock(QueryShardContext.class);
        KeywordFieldMapper.KeywordFieldType fieldType = spy(new KeywordFieldMapper.KeywordFieldType("status"));
        Query expected = new MatchAllDocsQuery();
        when(context.fieldMapper("status")).thenReturn(fieldType);
        java.util.List<Object> resolvedExpected = java.util.Arrays.asList("active", "pending");
        doReturn(expected).when(fieldType).termsQuery(resolvedExpected, context);

        ExtractedPredicate predicate = new ExtractedPredicate.Terms(
            "status",
            java.util.Arrays.asList("active", new ExtractedPredicate.ParamRef("second"))
        );
        assertSame(expected, predicate.toQuery(context, Collections.singletonMap("second", "pending")));
    }

    public void testTermsDeclinesWhenAnyElementIsNonString() {
        QueryShardContext context = mock(QueryShardContext.class);
        KeywordFieldMapper.KeywordFieldType fieldType = spy(new KeywordFieldMapper.KeywordFieldType("status"));
        when(context.fieldMapper("status")).thenReturn(fieldType);

        assertNull(
            new ExtractedPredicate.Terms("status", java.util.Arrays.asList("active", 123)).toQuery(context)
        );
        assertNull(
            new ExtractedPredicate.Terms("status", java.util.Arrays.asList(Boolean.TRUE)).toQuery(context)
        );
        verify(fieldType, org.mockito.Mockito.never()).termsQuery(any(), any());
    }

    public void testTermsDeclinesWhenAnyParamRefElementMissingOrNonString() {
        QueryShardContext context = mock(QueryShardContext.class);
        KeywordFieldMapper.KeywordFieldType fieldType = spy(new KeywordFieldMapper.KeywordFieldType("status"));
        when(context.fieldMapper("status")).thenReturn(fieldType);

        ExtractedPredicate predicate = new ExtractedPredicate.Terms(
            "status",
            java.util.Arrays.asList("active", new ExtractedPredicate.ParamRef("second"))
        );
        // Missing param → decline.
        assertNull(predicate.toQuery(context, Collections.emptyMap()));
        // Non-String param → decline.
        assertNull(predicate.toQuery(context, Collections.singletonMap("second", 42)));
        verify(fieldType, org.mockito.Mockito.never()).termsQuery(any(), any());
    }

    public void testTermsEqualsAndHashCode() {
        ExtractedPredicate.Terms a = new ExtractedPredicate.Terms("status", java.util.Arrays.asList("active", "pending"));
        ExtractedPredicate.Terms b = new ExtractedPredicate.Terms("status", java.util.Arrays.asList("active", "pending"));
        ExtractedPredicate.Terms c = new ExtractedPredicate.Terms("status", java.util.Arrays.asList("active"));
        ExtractedPredicate.Terms d = new ExtractedPredicate.Terms("tier", java.util.Arrays.asList("active", "pending"));
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
