/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.painless;

import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.Query;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.IndexService;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.index.query.ScriptQueryBuilder;
import org.opensearch.plugins.Plugin;
import org.opensearch.script.Script;
import org.opensearch.script.ScriptType;
import org.opensearch.test.OpenSearchSingleNodeTestCase;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * End-to-end check that {@link ScriptQueryBuilder#toQuery(QueryShardContext)} actually swaps a
 * Painless {@code FilterScript} for a native range query when the script matches the guarded
 * grammar, and stays on the script path otherwise. These are the only tests on the branch that
 * exercise the full compile → extract → rewrite chain against a real mapper + script service.
 */
public class ScriptQueryRewriteTests extends OpenSearchSingleNodeTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(PainlessModulePlugin.class);
    }

    /**
     * Predicate extraction is off by default because of a known multi-value soundness hole
     * (see Known limitations in DESIGN-script-query-acceleration.md and apache/lucene#15794).
     * Every rewrite test here opts in via this index-level setting.
     */
    private static final Settings REWRITE_ENABLED = Settings.builder()
        .put(org.opensearch.index.IndexSettings.ALLOW_PREDICATE_EXTRACTION.getKey(), true)
        .build();

    /** {@code ScriptQueryBuilder.ScriptQuery} is package-private so we can't import it from here. */
    private static boolean isScriptQuery(Query query) {
        return query != null && "ScriptQuery".equals(query.getClass().getSimpleName());
    }

    public void testGuardedScriptRewritesToNativeRange() throws IOException {
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "price", "type=long");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script("doc['price'].size() == 1 && doc['price'].value > 10")
        );

        Query query = builder.toQuery(context);
        assertTrue(
            "guarded script must rewrite to a ConstantScoreQuery wrapping a native range, got " + query,
            query instanceof ConstantScoreQuery
        );
        Query inner = ((ConstantScoreQuery) query).getQuery();
        assertFalse("rewrite must not fall back to a ScriptQuery, got " + inner, isScriptQuery(inner));
    }

    public void testBoundedRangeRewritesToNativeRange() throws IOException {
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "price", "type=long");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script(
                "doc['price'].size() == 1 && doc['price'].value > 10 && doc['price'].value < 100"
            )
        );

        Query query = builder.toQuery(context);
        assertTrue("bounded range should rewrite, got " + query, query instanceof ConstantScoreQuery);
        assertFalse("bounded range must not land on ScriptQuery", isScriptQuery(((ConstantScoreQuery) query).getQuery()));
    }

    public void testUnguardedScriptStaysOnScriptQuery() throws IOException {
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "price", "type=long");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        // Bare .value read — unsafe on missing docs, must not rewrite.
        ScriptQueryBuilder builder = new ScriptQueryBuilder(new Script("doc['price'].value > 10"));

        Query query = builder.toQuery(context);
        assertTrue("unguarded script must stay on the ScriptQuery path, got " + query, isScriptQuery(query));
    }

    public void testPresenceOnlyGuardStaysOnScriptQuery() throws IOException {
        // Regression guard for the multi-valued unsoundness: `size() != 0` rules out missing docs
        // but not multi-valued docs, where Painless' `get(0)` diverges from a native range query.
        // The phase must decline and the query must stay on ScriptQuery.
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "price", "type=long");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script("doc['price'].size() != 0 && doc['price'].value > 10")
        );

        Query query = builder.toQuery(context);
        assertTrue("presence-only guard must not rewrite (multi-value soundness), got " + query, isScriptQuery(query));
    }

    public void testGuardedStringEqualityRewritesToTerm() throws IOException {
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "status", "type=keyword");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script("doc['status'].size() == 1 && doc['status'].value == 'active'")
        );

        Query query = builder.toQuery(context);
        assertTrue("guarded string equality must rewrite, got " + query, query instanceof ConstantScoreQuery);
        Query inner = ((ConstantScoreQuery) query).getQuery();
        assertFalse("rewrite must not fall back to ScriptQuery, got " + inner, isScriptQuery(inner));
    }

    public void testPresenceOnlyGuardStringEqualityStaysOnScriptQuery() throws IOException {
        // Same multi-value soundness check as the numeric case: `size() != 0` isn't enough.
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "status", "type=keyword");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script("doc['status'].size() != 0 && doc['status'].value == 'active'")
        );

        Query query = builder.toQuery(context);
        assertTrue(
            "presence-only guard on string == must not rewrite (multi-value soundness), got " + query,
            isScriptQuery(query)
        );
    }

    public void testAltSpellingsRewriteToNativeRange() throws IOException {
        // `doc.get('f')` / `.getValue()` accept the same rewrite as `doc['f']` / `.value`. End-to-
        // end check that the phase + carrier still produce a ConstantScoreQuery for a mix of alt
        // spellings in the guard and the read.
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "price", "type=long");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script("doc.get('price').size() == 1 && doc.get('price').getValue() > 10")
        );

        Query query = builder.toQuery(context);
        assertTrue("alt-spelling script must rewrite, got " + query, query instanceof ConstantScoreQuery);
        Query inner = ((ConstantScoreQuery) query).getQuery();
        assertFalse("rewrite must not fall back to ScriptQuery, got " + inner, isScriptQuery(inner));
    }

    public void testGuardedListContainsRewritesToTerms() throws IOException {
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "status", "type=keyword");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script("doc['status'].size() == 1 && ['active', 'pending'].contains(doc['status'].value)")
        );

        Query query = builder.toQuery(context);
        assertTrue("guarded list.contains must rewrite, got " + query, query instanceof ConstantScoreQuery);
        Query inner = ((ConstantScoreQuery) query).getQuery();
        assertFalse("rewrite must not fall back to ScriptQuery, got " + inner, isScriptQuery(inner));
    }

    public void testListContainsOnNormalizedKeywordStaysOnScriptQuery() throws IOException {
        // Same normalizer divergence that blocks the Term rewrite applies to Terms: a normalizer
        // would lowercase each list element, so `['Active']` would match docs storing `active`
        // even though Painless' `.equals()` against the raw value returns false.
        Settings settings = Settings.builder()
            .putList("index.analysis.normalizer.my_lower.filter", "lowercase")
            .put(org.opensearch.index.IndexSettings.ALLOW_PREDICATE_EXTRACTION.getKey(), true)
            .build();
        IndexService index = createIndexWithSimpleMappings("idx", settings, "status", "type=keyword,normalizer=my_lower");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script("doc['status'].size() == 1 && ['Active'].contains(doc['status'].value)")
        );

        Query query = builder.toQuery(context);
        assertTrue(
            "list.contains on normalized keyword must stay on ScriptQuery, got " + query,
            isScriptQuery(query)
        );
    }

    public void testGuardedOrRewritesToBooleanShould() throws IOException {
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "price", "type=long");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script(
                "doc['price'].size() == 1 && (doc['price'].value < 10 || doc['price'].value > 100)"
            )
        );

        Query query = builder.toQuery(context);
        assertTrue("guarded Or must rewrite, got " + query, query instanceof ConstantScoreQuery);
        Query inner = ((ConstantScoreQuery) query).getQuery();
        assertFalse("rewrite must not fall back to ScriptQuery, got " + inner, isScriptQuery(inner));
        assertTrue("expected a BooleanQuery inside, got " + inner, inner instanceof org.apache.lucene.search.BooleanQuery);
        org.apache.lucene.search.BooleanQuery bq = (org.apache.lucene.search.BooleanQuery) inner;
        assertEquals(1, bq.getMinimumNumberShouldMatch());
        assertEquals(2, bq.clauses().size());
    }

    public void testGuardedMultiFieldAndRewritesToBooleanMust() throws IOException {
        IndexService index = createIndexWithSimpleMappings(
            "idx",
            REWRITE_ENABLED,
            "price",
            "type=long",
            "status",
            "type=keyword"
        );
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script(
                "doc['price'].size() == 1 && doc['price'].value > 10 && "
                    + "doc['status'].size() == 1 && doc['status'].value == 'active'"
            )
        );

        Query query = builder.toQuery(context);
        assertTrue("guarded multi-field And must rewrite, got " + query, query instanceof ConstantScoreQuery);
        Query inner = ((ConstantScoreQuery) query).getQuery();
        assertFalse("rewrite must not fall back to ScriptQuery, got " + inner, isScriptQuery(inner));
        assertTrue("expected a BooleanQuery inside, got " + inner, inner instanceof org.apache.lucene.search.BooleanQuery);
        org.apache.lucene.search.BooleanQuery bq = (org.apache.lucene.search.BooleanQuery) inner;
        assertEquals(2, bq.clauses().size());
        for (org.apache.lucene.search.BooleanClause clause : bq.clauses()) {
            assertEquals("each clause must be MUST", org.apache.lucene.search.BooleanClause.Occur.MUST, clause.occur());
        }
    }

    public void testUnguardedOrStaysOnScriptQuery() throws IOException {
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "price", "type=long");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script("doc['price'].value < 10 || doc['price'].value > 100")
        );

        Query query = builder.toQuery(context);
        assertTrue("unguarded Or must stay on ScriptQuery, got " + query, isScriptQuery(query));
    }

    public void testGuardedNumericRangeOnKeywordFieldStaysOnScriptQuery() throws IOException {
        // Pre-existing hole closed: KeywordFieldType.rangeQuery stringifies numeric bounds into
        // a lexicographic range, which doesn't match Painless' DefMath.gt/lt throw-on-type-
        // mismatch semantics. The Range.toQuery gate must decline the rewrite.
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "status", "type=keyword");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script("doc['status'].size() == 1 && doc['status'].value > 10")
        );

        Query query = builder.toQuery(context);
        assertTrue("numeric range on keyword field must stay on ScriptQuery, got " + query, isScriptQuery(query));
    }

    public void testGuardedMixedTermRangeOrOnKeywordStaysOnScriptQuery() throws IOException {
        // Mixed Term + Range arm on a keyword field: the Range arm's gate declines (keyword
        // isn't a NumberFieldType), so the whole Or declines. Before the gate, the range arm
        // would have stringified `10` lexicographically and diverged from Painless.
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "status", "type=keyword");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script(
                "doc['status'].size() == 1 && (doc['status'].value == 'active' || doc['status'].value < 10)"
            )
        );

        Query query = builder.toQuery(context);
        assertTrue("mixed Term/Range Or on keyword field must stay on ScriptQuery, got " + query, isScriptQuery(query));
    }

    public void testKeywordWithNormalizerStaysOnScriptQuery() throws IOException {
        // Regression guard for semantic divergence on normalized keyword mappings. A `lowercase`
        // normalizer lowercases the search literal before lookup, so `termQuery('Active')`
        // matches docs storing `active` — but Painless' `doc['status'].value == 'Active'` reads
        // the raw `active` and returns false. The rewrite must decline and let the script run.
        Settings settings = Settings.builder()
            .putList("index.analysis.normalizer.my_lower.filter", "lowercase")
            .put(org.opensearch.index.IndexSettings.ALLOW_PREDICATE_EXTRACTION.getKey(), true)
            .build();
        IndexService index = createIndexWithSimpleMappings("idx", settings, "status", "type=keyword,normalizer=my_lower");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script("doc['status'].size() == 1 && doc['status'].value == 'Active'")
        );

        Query query = builder.toQuery(context);
        assertTrue(
            "keyword-with-normalizer must not rewrite (normalizer divergence), got " + query,
            isScriptQuery(query)
        );
    }

    public void testTextFieldStaysOnScriptQuery() throws IOException {
        // Regression guard for text-field divergence. `termQuery` on a text field searches the
        // analyzed inverted index; the script reads raw fielddata (or throws if fielddata is
        // disabled). Different result sets in either direction — the rewrite must decline.
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "title", "type=text");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script("doc['title'].size() == 1 && doc['title'].value == 'hello'")
        );

        Query query = builder.toQuery(context);
        assertTrue("text field must not rewrite (analyzer divergence), got " + query, isScriptQuery(query));
    }

    public void testGuardedScriptWithParamRewritesToNativeRange() throws IOException {
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "price", "type=long");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        Map<String, Object> params = new HashMap<>();
        params.put("threshold", 10L);
        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script(ScriptType.INLINE, "painless", "doc['price'].size() == 1 && doc['price'].value > params.threshold", params)
        );

        Query query = builder.toQuery(context);
        assertTrue("guarded param script must rewrite, got " + query, query instanceof ConstantScoreQuery);
        Query inner = ((ConstantScoreQuery) query).getQuery();
        assertFalse("rewrite must not fall back to ScriptQuery, got " + inner, isScriptQuery(inner));
    }

    public void testGuardedStringEqualityAgainstParamRewritesToTerm() throws IOException {
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "status", "type=keyword");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        Map<String, Object> params = new HashMap<>();
        params.put("expected", "active");
        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script(ScriptType.INLINE, "painless", "doc['status'].size() == 1 && doc['status'].value == params.expected", params)
        );

        Query query = builder.toQuery(context);
        assertTrue("guarded ==-param term must rewrite, got " + query, query instanceof ConstantScoreQuery);
        Query inner = ((ConstantScoreQuery) query).getQuery();
        assertFalse("rewrite must not fall back to ScriptQuery, got " + inner, isScriptQuery(inner));
    }

    public void testNumericValuedTermParamFallsBackToScript() throws IOException {
        // Painless' `String.equals(Integer)` returns false, never throws. The rewrite would
        // stringify the number and match a doc whose keyword literally equals that string —
        // strictly more permissive. Must decline.
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "status", "type=keyword");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        Map<String, Object> params = new HashMap<>();
        params.put("expected", 123);
        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script(ScriptType.INLINE, "painless", "doc['status'].size() == 1 && doc['status'].value == params.expected", params)
        );

        Query query = builder.toQuery(context);
        assertTrue("Number-valued term param must stay on ScriptQuery, got " + query, isScriptQuery(query));
    }

    public void testStringValuedNumericParamFallsBackToScript() throws IOException {
        // Painless compiles `long > Object` via DefMath.gt, which throws on Long-vs-String. The
        // rewrite must match that semantics and decline a String-valued numeric param, not
        // silently parse it via MappedFieldType.rangeQuery.
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "price", "type=long");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        Map<String, Object> params = new HashMap<>();
        params.put("threshold", "10");
        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script(ScriptType.INLINE, "painless", "doc['price'].size() == 1 && doc['price'].value > params.threshold", params)
        );

        Query query = builder.toQuery(context);
        assertTrue("String-valued numeric param must stay on ScriptQuery, got " + query, isScriptQuery(query));
    }

    public void testMissingParamFallsBackToScript() throws IOException {
        // Missing `params.threshold` at query-build time. A' resolution rule: decline so the
        // script runs and the user sees the exception, rather than a silent match-all range.
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "price", "type=long");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script(
                ScriptType.INLINE,
                "painless",
                "doc['price'].size() == 1 && doc['price'].value > params.threshold",
                Collections.emptyMap()
            )
        );

        Query query = builder.toQuery(context);
        assertTrue("missing param must stay on ScriptQuery, got " + query, isScriptQuery(query));
    }

    public void testUnmappedFieldFallsBackToScript() throws IOException {
        // Range.toQuery returns null when the field is unmapped; doToQuery must fall back to the
        // script path rather than emitting a broken rewrite.
        IndexService index = createIndexWithSimpleMappings("idx", REWRITE_ENABLED, "other", "type=long");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script("doc['missing'].size() == 1 && doc['missing'].value > 10")
        );

        Query query = builder.toQuery(context);
        assertTrue("unmapped field must fall back to ScriptQuery, got " + query, isScriptQuery(query));
    }
}
