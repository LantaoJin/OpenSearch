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
import org.opensearch.test.OpenSearchSingleNodeTestCase;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

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

    /** {@code ScriptQueryBuilder.ScriptQuery} is package-private so we can't import it from here. */
    private static boolean isScriptQuery(Query query) {
        return query != null && "ScriptQuery".equals(query.getClass().getSimpleName());
    }

    public void testGuardedScriptRewritesToNativeRange() throws IOException {
        IndexService index = createIndexWithSimpleMappings("idx", Settings.EMPTY, "price", "type=long");
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
        IndexService index = createIndexWithSimpleMappings("idx", Settings.EMPTY, "price", "type=long");
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
        IndexService index = createIndexWithSimpleMappings("idx", Settings.EMPTY, "price", "type=long");
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
        IndexService index = createIndexWithSimpleMappings("idx", Settings.EMPTY, "price", "type=long");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script("doc['price'].size() != 0 && doc['price'].value > 10")
        );

        Query query = builder.toQuery(context);
        assertTrue("presence-only guard must not rewrite (multi-value soundness), got " + query, isScriptQuery(query));
    }

    public void testUnmappedFieldFallsBackToScript() throws IOException {
        // Range.toQuery returns null when the field is unmapped; doToQuery must fall back to the
        // script path rather than emitting a broken rewrite.
        IndexService index = createIndexWithSimpleMappings("idx", Settings.EMPTY, "other", "type=long");
        QueryShardContext context = index.newQueryShardContext(0, null, () -> 0, null);

        ScriptQueryBuilder builder = new ScriptQueryBuilder(
            new Script("doc['missing'].size() == 1 && doc['missing'].value > 10")
        );

        Query query = builder.toQuery(context);
        assertTrue("unmapped field must fall back to ScriptQuery, got " + query, isScriptQuery(query));
    }
}
