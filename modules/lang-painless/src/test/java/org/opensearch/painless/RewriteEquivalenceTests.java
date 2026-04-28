/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.painless;

import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.query.ScriptQueryBuilder;
import org.opensearch.plugins.Plugin;
import org.opensearch.script.Script;
import org.opensearch.script.ScriptType;
import org.opensearch.search.SearchHit;
import org.opensearch.test.OpenSearchSingleNodeTestCase;
import org.junit.Before;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Result-set equivalence harness for the Layer A rewrite. Every assertion runs the same
 * Painless script twice — once with {@code index.query.script.allow_predicate_extraction}
 * off (pure script path) and once with it on (native rewrite) — and fails if the two hit sets
 * differ.
 *
 * <p>This catches bugs that class-identity-only tests (like {@link ScriptQueryRewriteTests})
 * cannot: wrong bound folding, wrong field-gating, param-resolution drift, mis-composed Or
 * arms, missing per-block guards in And, etc. It's the empirical complement to
 * {@link PredicateExtractionPhaseTests} (which asserts AST → carrier) and
 * {@link ScriptQueryRewriteTests} (which asserts carrier → query class).
 *
 * <p>The corpus is deliberately single-valued. Multi-valued regressions live in
 * {@link MultiValueRewriteSemanticsTests} as {@code @AwaitsFix} cases tracking the Lucene
 * fix (apache/lucene#15794). Once that lands and the per-segment dispatcher ships, this
 * harness should expand to cover multi-valued docs too.
 */
public class RewriteEquivalenceTests extends OpenSearchSingleNodeTestCase {

    private static final String INDEX = "rewrite_equiv";

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(PainlessModulePlugin.class);
    }

    @Before
    public void setUpCorpus() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder()
            .startObject()
                .startObject("properties")
                    .startObject("price").field("type", "long").endObject()
                    .startObject("age").field("type", "long").endObject()
                    .startObject("status").field("type", "keyword").endObject()
                    .startObject("tier").field("type", "keyword").endObject()
                    .startObject("region").field("type", "keyword").endObject()
                .endObject()
            .endObject();
        createIndex(INDEX, Settings.EMPTY, "_doc", mapping);

        // Small corpus, all single-valued. Each doc's ID is a stable label so failures name
        // the divergent doc. Includes edge cases (zero, negative, empty string) without
        // relying on implicit-ish behavior.
        index("d01", doc().put("price", -5L).put("status", "active").put("tier", "gold").put("age", 25L).put("region", "us-east").build());
        index("d02", doc().put("price", 0L).put("status", "active").put("tier", "silver").put("age", 30L).put("region", "us-west").build());
        index("d03", doc().put("price", 5L).put("status", "pending").put("tier", "gold").put("age", 40L).put("region", "us-east").build());
        index("d04", doc().put("price", 10L).put("status", "pending").put("tier", "bronze").put("age", 50L).put("region", "eu-west").build());
        index("d05", doc().put("price", 15L).put("status", "active").put("tier", "gold").put("age", 20L).put("region", "us-east").build());
        index("d06", doc().put("price", 20L).put("status", "archived").put("tier", "silver").put("age", 35L).put("region", "us-west").build());
        index("d07", doc().put("price", 50L).put("status", "active").put("tier", "gold").put("age", 60L).put("region", "eu-west").build());
        index("d08", doc().put("price", 99L).put("status", "pending").put("tier", "bronze").put("age", 18L).put("region", "ap-south").build());
        index("d09", doc().put("price", 100L).put("status", "active").put("tier", "gold").put("age", 45L).put("region", "ap-south").build());
        index("d10", doc().put("price", 101L).put("status", "archived").put("tier", "silver").put("age", 55L).put("region", "us-east").build());
        // Docs missing some fields — exercise the missing-doc path.
        index("d11", doc().put("price", 30L).build());
        index("d12", doc().put("status", "active").put("tier", "gold").build());
        client().admin().indices().prepareRefresh(INDEX).get();
    }

    // --- Range (literal bounds) --------------------------------------------

    public void testRangeGreaterThan() {
        assertEquivalent("doc['price'].size() == 1 && doc['price'].value > 10");
    }

    public void testRangeGreaterOrEqual() {
        assertEquivalent("doc['price'].size() == 1 && doc['price'].value >= 15");
    }

    public void testRangeLessThan() {
        assertEquivalent("doc['price'].size() == 1 && doc['price'].value < -1");
    }

    public void testRangeLessOrEqual() {
        assertEquivalent("doc['price'].size() == 1 && doc['price'].value <= 5");
    }

    public void testRangeEqualsLiteral() {
        assertEquivalent("doc['price'].size() == 1 && doc['price'].value == 50");
    }

    public void testRangeBoundedByAnd() {
        assertEquivalent("doc['price'].size() == 1 && doc['price'].value > 10 && doc['price'].value < 100");
    }

    public void testRangeBoundedCrossingZero() {
        assertEquivalent("doc['price'].size() == 1 && doc['price'].value > -5 && doc['price'].value < 5");
    }

    public void testRangeTightenedLowerBound() {
        // `>= 5 && > 10` should fold to `> 10`.
        assertEquivalent("doc['price'].size() == 1 && doc['price'].value >= 5 && doc['price'].value > 10");
    }

    // --- Range with params --------------------------------------------------

    public void testRangeWithParamLower() {
        Map<String, Object> params = new HashMap<>();
        params.put("threshold", 10L);
        assertEquivalent("doc['price'].size() == 1 && doc['price'].value > params.threshold", params);
    }

    public void testRangeWithBothParamBounds() {
        Map<String, Object> params = new HashMap<>();
        params.put("low", 5L);
        params.put("high", 100L);
        assertEquivalent(
            "doc['price'].size() == 1 && doc['price'].value > params.low && doc['price'].value < params.high",
            params
        );
    }

    public void testRangeMixedLiteralAndParam() {
        Map<String, Object> params = new HashMap<>();
        params.put("high", 50L);
        assertEquivalent(
            "doc['price'].size() == 1 && doc['price'].value > 10 && doc['price'].value < params.high",
            params
        );
    }

    // --- Term --------------------------------------------------------------

    public void testTermEqualityLiteral() {
        assertEquivalent("doc['status'].size() == 1 && doc['status'].value == 'active'");
    }

    public void testTermEqualityWithParam() {
        Map<String, Object> params = new HashMap<>();
        params.put("expected", "pending");
        assertEquivalent("doc['status'].size() == 1 && doc['status'].value == params.expected", params);
    }

    // --- Terms via list.contains -------------------------------------------

    public void testTermsTwoElementList() {
        assertEquivalent(
            "doc['status'].size() == 1 && ['active', 'pending'].contains(doc['status'].value)"
        );
    }

    public void testTermsSingleElementList() {
        assertEquivalent(
            "doc['tier'].size() == 1 && ['gold'].contains(doc['tier'].value)"
        );
    }

    public void testTermsEmptyList() {
        // `[].contains(x)` is always false — rewritten to match-nothing.
        assertEquivalent(
            "doc['status'].size() == 1 && [].contains(doc['status'].value)"
        );
    }

    public void testTermsMixedLiteralAndParamElement() {
        Map<String, Object> params = new HashMap<>();
        params.put("extra", "pending");
        assertEquivalent(
            "doc['status'].size() == 1 && ['active', params.extra].contains(doc['status'].value)",
            params
        );
    }

    public void testTermsAllParamElements() {
        Map<String, Object> params = new HashMap<>();
        params.put("a", "active");
        params.put("b", "archived");
        assertEquivalent(
            "doc['status'].size() == 1 && [params.a, params.b].contains(doc['status'].value)",
            params
        );
    }

    public void testTermsWholeListParam() {
        Map<String, Object> params = new HashMap<>();
        params.put("statusList", java.util.Arrays.asList("active", "pending"));
        assertEquivalent(
            "doc['status'].size() == 1 && params.statusList.contains(doc['status'].value)",
            params
        );
    }

    public void testTermsWholeListParamEmpty() {
        // Empty list: `[].contains(x)` is always false in Painless; rewritten to match-nothing.
        Map<String, Object> params = new HashMap<>();
        params.put("statusList", Collections.emptyList());
        assertEquivalent(
            "doc['status'].size() == 1 && params.statusList.contains(doc['status'].value)",
            params
        );
    }

    // --- Or ----------------------------------------------------------------

    public void testOrTwoNumericRanges() {
        assertEquivalent(
            "doc['price'].size() == 1 && (doc['price'].value < 0 || doc['price'].value > 50)"
        );
    }

    public void testOrThreeArms() {
        assertEquivalent(
            "doc['price'].size() == 1 && "
                + "(doc['price'].value < 0 || doc['price'].value == 50 || doc['price'].value > 99)"
        );
    }

    public void testOrMixedRangeAndTerm() {
        assertEquivalent(
            "doc['status'].size() == 1 && "
                + "(doc['status'].value == 'active' || doc['status'].value == 'archived')"
        );
    }

    // --- And (multi-field) -------------------------------------------------

    public void testAndTwoFields() {
        assertEquivalent(
            "doc['price'].size() == 1 && doc['price'].value > 10 && "
                + "doc['status'].size() == 1 && doc['status'].value == 'active'"
        );
    }

    public void testAndThreeFields() {
        assertEquivalent(
            "doc['price'].size() == 1 && doc['price'].value > 10 && "
                + "doc['status'].size() == 1 && doc['status'].value == 'active' && "
                + "doc['region'].size() == 1 && doc['region'].value == 'us-east'"
        );
    }

    public void testAndWithBoundedRangeBlock() {
        assertEquivalent(
            "doc['price'].size() == 1 && doc['price'].value > 10 && doc['price'].value < 100 && "
                + "doc['tier'].size() == 1 && doc['tier'].value == 'gold'"
        );
    }

    // --- Not --------------------------------------------------------------

    public void testNotEqualOnKeyword() {
        assertEquivalent("doc['status'].size() == 1 && doc['status'].value != 'active'");
    }

    public void testNotEqualOnKeywordWithParam() {
        Map<String, Object> params = new HashMap<>();
        params.put("expected", "pending");
        assertEquivalent(
            "doc['status'].size() == 1 && doc['status'].value != params.expected",
            params
        );
    }

    public void testNotEqualNumeric() {
        assertEquivalent("doc['price'].size() == 1 && doc['price'].value != 10");
    }

    public void testNotListContains() {
        assertEquivalent(
            "doc['status'].size() == 1 && !['active', 'archived'].contains(doc['status'].value)"
        );
    }

    public void testNotWholeListParamContains() {
        Map<String, Object> params = new HashMap<>();
        params.put("statusList", java.util.Arrays.asList("active", "archived"));
        assertEquivalent(
            "doc['status'].size() == 1 && !params.statusList.contains(doc['status'].value)",
            params
        );
    }

    // --- harness -----------------------------------------------------------

    private void assertEquivalent(String painless) {
        assertEquivalent(painless, Collections.emptyMap());
    }

    private void assertEquivalent(String painless, Map<String, Object> params) {
        Set<String> viaScript = runHits(painless, params, false);
        Set<String> viaRewrite = runHits(painless, params, true);
        assertEquals(
            "Script path and rewrite path must return identical hits for: " + painless,
            viaScript,
            viaRewrite
        );
    }

    /**
     * Execute the script with the rewrite flag set to {@code allowRewrite} and return the set
     * of doc IDs that match. Toggles the index setting dynamically so both executions hit the
     * same corpus.
     */
    private Set<String> runHits(String painless, Map<String, Object> params, boolean allowRewrite) {
        client().admin()
            .indices()
            .prepareUpdateSettings(INDEX)
            .setSettings(Settings.builder().put(IndexSettings.ALLOW_PREDICATE_EXTRACTION.getKey(), allowRewrite))
            .get();

        Script script = new Script(ScriptType.INLINE, "painless", painless, params);
        SearchResponse response = client().prepareSearch(INDEX)
            .setQuery(new ScriptQueryBuilder(script))
            .setSize(100)
            .get();

        // Sorted to make failure messages deterministic.
        Set<String> ids = new TreeSet<>();
        for (SearchHit hit : response.getHits().getHits()) {
            ids.add(hit.getId());
        }
        return ids;
    }

    private void index(String id, Map<String, Object> source) {
        client().prepareIndex(INDEX).setId(id).setSource(source).get();
    }

    private static DocBuilder doc() {
        return new DocBuilder();
    }

    /** Tiny typed map builder so each test-corpus doc reads top-to-bottom. */
    private static final class DocBuilder {
        private final Map<String, Object> source = new HashMap<>();

        DocBuilder put(String key, Object value) {
            source.put(key, value);
            return this;
        }

        Map<String, Object> build() {
            return source;
        }
    }

}
