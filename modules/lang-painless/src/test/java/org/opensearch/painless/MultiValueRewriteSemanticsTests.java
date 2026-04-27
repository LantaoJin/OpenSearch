/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.painless;

import org.apache.lucene.tests.util.LuceneTestCase.AwaitsFix;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.index.query.ScriptQueryBuilder;
import org.opensearch.plugins.Plugin;
import org.opensearch.script.Script;
import org.opensearch.test.OpenSearchSingleNodeTestCase;

import java.util.Collection;
import java.util.Collections;

/**
 * Empirical regression test for the "{@code size() == 1} guard-vs-rewrite" divergence flagged in
 * Codex review. The branch's rewrite carriers all assume that under a {@code size() == 1}
 * guard, the native {@code rangeQuery} / {@code termQuery} / etc. matches the same doc set as
 * the script. That's not actually true on multi-valued fields: Lucene's native field queries
 * match a doc if <em>any</em> indexed value satisfies the predicate, while the script
 * short-circuits on the guard and never evaluates the comparison on multi-valued docs.
 *
 * <p>Every case in this file indexes a multi-valued doc that the script semantics should
 * reject, issues a guarded script query that should rewrite via {@link ScriptQueryBuilder},
 * and asserts the hit count matches what the script would produce. These tests are expected
 * to <strong>fail</strong> until the rewrite is made multi-value-safe — they document the
 * gap rather than pass it.
 */
public class MultiValueRewriteSemanticsTests extends OpenSearchSingleNodeTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(PainlessModulePlugin.class);
    }

    /**
     * Single-field Range. Doc {@code {price: [5, 20]}} has {@code size() == 2}, so the script
     * {@code size() == 1 && value > 10} is false. But the rewritten {@code rangeQuery(price
     * > 10)} matches the doc because {@code 20 > 10}.
     */
    @AwaitsFix(bugUrl = "https://github.com/apache/lucene/issues/15794")
    public void testRangeSingleFieldMultiValuedDocIsNotMatchedByScript() throws Exception {
        createIndex("idx", Settings.EMPTY, "_doc", XContentFactory.jsonBuilder()
            .startObject()
                .startObject("properties")
                    .startObject("price").field("type", "long").endObject()
                .endObject()
            .endObject());

        client().prepareIndex("idx").setId("single_match").setSource("price", 15).get();
        client().prepareIndex("idx").setId("multi_valued").setSource("price", new int[] {5, 20}).get();
        client().admin().indices().prepareRefresh("idx").get();

        SearchResponse response = client().prepareSearch("idx")
            .setQuery(new ScriptQueryBuilder(new Script("doc['price'].size() == 1 && doc['price'].value > 10")))
            .get();

        // Script semantics: only `single_match` (price=15, size==1) matches. The multi-valued
        // doc should be excluded by the size() == 1 guard.
        assertEquals(
            "Rewrite must not match the multi-valued doc — script would have rejected it via the guard",
            1L,
            response.getHits().getTotalHits().value()
        );
        assertEquals("single_match", response.getHits().getAt(0).getId());
    }

    /**
     * Single-field Term on keyword. Doc {@code {status: ['active', 'stale']}} has
     * {@code size() == 2}, so the script {@code size() == 1 && value == 'active'} is false.
     * The rewritten {@code termQuery(status, 'active')} would match because one of the indexed
     * values is {@code active}.
     */
    @AwaitsFix(bugUrl = "https://github.com/apache/lucene/issues/15794")
    public void testTermSingleFieldMultiValuedDocIsNotMatchedByScript() throws Exception {
        createIndex("idx", Settings.EMPTY, "_doc", XContentFactory.jsonBuilder()
            .startObject()
                .startObject("properties")
                    .startObject("status").field("type", "keyword").endObject()
                .endObject()
            .endObject());

        client().prepareIndex("idx").setId("single_match").setSource("status", "active").get();
        client().prepareIndex("idx").setId("multi_valued").setSource("status", new String[] {"active", "stale"}).get();
        client().admin().indices().prepareRefresh("idx").get();

        SearchResponse response = client().prepareSearch("idx")
            .setQuery(new ScriptQueryBuilder(new Script("doc['status'].size() == 1 && doc['status'].value == 'active'")))
            .get();

        assertEquals(
            "Term rewrite must not match the multi-valued doc — script would have rejected it via the guard",
            1L,
            response.getHits().getTotalHits().value()
        );
        assertEquals("single_match", response.getHits().getAt(0).getId());
    }

    /**
     * Multi-field And. The case Codex flagged: doc with multi-valued {@code price} and
     * single-valued {@code status} satisfies neither the script's per-block guard for
     * {@code price}, yet the rewritten {@code price > 10 AND status == active} matches on any
     * value of {@code price}.
     */
    @AwaitsFix(bugUrl = "https://github.com/apache/lucene/issues/15794")
    public void testAndMultiFieldMultiValuedDocIsNotMatchedByScript() throws Exception {
        createIndex("idx", Settings.EMPTY, "_doc", XContentFactory.jsonBuilder()
            .startObject()
                .startObject("properties")
                    .startObject("price").field("type", "long").endObject()
                    .startObject("status").field("type", "keyword").endObject()
                .endObject()
            .endObject());

        client().prepareIndex("idx").setId("single_match").setSource("price", 15, "status", "active").get();
        client().prepareIndex("idx").setId("multi_price").setSource("price", new int[] {5, 20}, "status", "active").get();
        client().admin().indices().prepareRefresh("idx").get();

        SearchResponse response = client().prepareSearch("idx")
            .setQuery(new ScriptQueryBuilder(new Script(
                "doc['price'].size() == 1 && doc['price'].value > 10 && "
                    + "doc['status'].size() == 1 && doc['status'].value == 'active'"
            )))
            .get();

        assertEquals(
            "And rewrite must not match the multi-valued-price doc — script's per-block guard would have rejected it",
            1L,
            response.getHits().getTotalHits().value()
        );
        assertEquals("single_match", response.getHits().getAt(0).getId());
    }
}
