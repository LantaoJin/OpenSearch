/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.index.query;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.opensearch.OpenSearchException;
import org.opensearch.common.Nullable;
import org.opensearch.common.lucene.search.function.Functions;
import org.opensearch.core.common.ParsingException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.script.ExtractedPredicate;
import org.opensearch.script.FilterScript;
import org.opensearch.script.Script;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

import static org.opensearch.search.SearchService.ALLOW_EXPENSIVE_QUERIES;

/**
 * Query builder for script queries
 *
 * @opensearch.internal
 */
public class ScriptQueryBuilder extends AbstractQueryBuilder<ScriptQueryBuilder> {
    public static final String NAME = "script";

    private final Script script;

    public ScriptQueryBuilder(Script script) {
        if (script == null) {
            throw new IllegalArgumentException("script cannot be null");
        }
        this.script = script;
    }

    /**
     * Read from a stream.
     */
    public ScriptQueryBuilder(StreamInput in) throws IOException {
        super(in);
        script = new Script(in);
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        script.writeTo(out);
    }

    public Script script() {
        return this.script;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params builderParams) throws IOException {
        builder.startObject(NAME);
        builder.field(Script.SCRIPT_PARSE_FIELD.getPreferredName(), script);
        printBoostAndQueryName(builder);
        builder.endObject();
    }

    public static ScriptQueryBuilder fromXContent(XContentParser parser) throws IOException {
        // also, when caching, since its isCacheable is false, will result in loading all bit set...
        Script script = null;

        float boost = AbstractQueryBuilder.DEFAULT_BOOST;
        String queryName = null;

        XContentParser.Token token;
        String currentFieldName = null;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_OBJECT) {
                if (Script.SCRIPT_PARSE_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    script = Script.parse(parser);
                } else {
                    throw new ParsingException(parser.getTokenLocation(), "[script] query does not support [" + currentFieldName + "]");
                }
            } else if (token.isValue()) {
                if (AbstractQueryBuilder.NAME_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    queryName = parser.text();
                } else if (AbstractQueryBuilder.BOOST_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    boost = parser.floatValue();
                } else if (Script.SCRIPT_PARSE_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    script = Script.parse(parser);
                } else {
                    throw new ParsingException(parser.getTokenLocation(), "[script] query does not support [" + currentFieldName + "]");
                }
            } else {
                if (token != XContentParser.Token.START_ARRAY) {
                    throw new AssertionError("Impossible token received: " + token.name());
                }
                throw new ParsingException(
                    parser.getTokenLocation(),
                    "[script] query does not support an array of scripts. Use a bool query with a clause per script instead."
                );
            }
        }

        if (script == null) {
            throw new ParsingException(parser.getTokenLocation(), "script must be provided with a [script] filter");
        }

        return new ScriptQueryBuilder(script).boost(boost).queryName(queryName);
    }

    @Override
    protected Query doToQuery(QueryShardContext context) throws IOException {
        if (context.allowExpensiveQueries() == false) {
            throw new OpenSearchException(
                "[script] queries cannot be executed when '" + ALLOW_EXPENSIVE_QUERIES.getKey() + "' is set to false."
            );
        }
        FilterScript.Factory factory = context.compile(script, FilterScript.CONTEXT);
        ExtractedPredicate extracted = factory.extractedPredicate();
        if (extracted != null) {
            Query rewrite = extracted.toQuery(context);
            if (rewrite != null) {
                // AbstractQueryBuilder.toQuery takes care of boost wrapping and named-query
                // registration, so we only need to wrap in ConstantScoreQuery to match the
                // "script always scores 1.0" semantics of the fallback path.
                return new ConstantScoreQuery(rewrite);
            }
        }
        FilterScript.LeafFactory filterScript = factory.newFactory(script.getParams(), context.lookup());
        return new ScriptQuery(script, filterScript, factory.accessedDocFields(), factory.isResultDeterministic(), queryName);
    }

    /**
     * Internal script query
     *
     * @opensearch.internal
     */
    static class ScriptQuery extends Query {

        final Script script;
        final FilterScript.LeafFactory filterScript;
        final Set<String> accessedDocFields;
        final boolean deterministic;
        final String queryName;

        ScriptQuery(
            Script script,
            FilterScript.LeafFactory filterScript,
            Set<String> accessedDocFields,
            boolean deterministic,
            @Nullable String queryName
        ) {
            this.script = script;
            this.filterScript = filterScript;
            this.accessedDocFields = accessedDocFields;
            this.deterministic = deterministic;
            this.queryName = queryName;
        }

        @Override
        public String toString(String field) {
            StringBuilder buffer = new StringBuilder();
            buffer.append("ScriptQuery(");
            buffer.append(script);
            buffer.append(Functions.nameOrEmptyArg(queryName));
            buffer.append(")");
            return buffer.toString();
        }

        @Override
        public void visit(QueryVisitor visitor) {
            visitor.visitLeaf(this);
        }

        @Override
        public boolean equals(Object obj) {
            if (sameClassAs(obj) == false) return false;
            ScriptQuery other = (ScriptQuery) obj;
            return Objects.equals(script, other.script);
        }

        @Override
        public int hashCode() {
            int h = classHash();
            h = 31 * h + script.hashCode();
            return h;
        }

        @Override
        public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
            return new ConstantScoreWeight(this, boost) {

                @Override
                public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
                    final FilterScript leafScript = filterScript.newInstance(context);
                    final Approximation approximation = buildApproximation(context);
                    final float matchCost = approximation.estimatedMatchCost(context.reader().maxDoc());
                    final DocIdSetIterator iterator = approximation.iterator;
                    TwoPhaseIterator twoPhase = new TwoPhaseIterator(iterator) {

                        @Override
                        public boolean matches() throws IOException {
                            leafScript.setDocument(iterator.docID());
                            return leafScript.execute();
                        }

                        @Override
                        public float matchCost() {
                            return matchCost;
                        }
                    };
                    final Scorer scorer = new ConstantScoreScorer(score(), scoreMode, twoPhase);
                    return new DefaultScorerSupplier(scorer);
                }

                @Override
                public boolean isCacheable(LeafReaderContext ctx) {
                    return deterministic;
                }
            };
        }

        /**
         * Build a two-phase approximation. If the script accesses exactly one numeric doc-values
         * field and every doc in the leaf has a value for that field, we can iterate the field's
         * doc values directly. Otherwise we fall back to {@link DocIdSetIterator#all}.
         *
         * <p>For sparse fields we do not try to prove that missing docs are non-matches. The
         * current {@code accessedDocFields()} hint is too weak to guarantee that: scripts can
         * branch on missing-field state and inspect other per-doc inputs such as {@code _source}.
         * Any attempt to generalize from a single missing-doc probe risks changing query results.
         */
        private Approximation buildApproximation(LeafReaderContext context) throws IOException {
            int maxDoc = context.reader().maxDoc();
            if (accessedDocFields == null || accessedDocFields.size() != 1) {
                return Approximation.fullScan(maxDoc);
            }
            String field = accessedDocFields.iterator().next();
            FieldInfo fieldInfo = context.reader().getFieldInfos().fieldInfo(field);
            if (fieldInfo == null) {
                return Approximation.fullScan(maxDoc);
            }
            DocValuesType docValuesType = fieldInfo.getDocValuesType();
            if (docValuesType != DocValuesType.NUMERIC && docValuesType != DocValuesType.SORTED_NUMERIC) {
                return Approximation.fullScan(maxDoc);
            }
            SortedNumericDocValues values = DocValues.getSortedNumeric(context.reader(), field);
            if (findMissingFieldDoc(values, maxDoc) >= 0) {
                return Approximation.fullScan(maxDoc);
            }
            return new Approximation(DocValues.getSortedNumeric(context.reader(), field), maxDoc);
        }

        /**
         * Lightweight carrier so we can plumb a selectivity hint alongside the iterator without
         * relying on {@link DocIdSetIterator#cost()} being accurate in all cases.
         */
        private static final class Approximation {
            final DocIdSetIterator iterator;
            final long estimatedCandidates;

            Approximation(DocIdSetIterator iterator, long estimatedCandidates) {
                this.iterator = iterator;
                this.estimatedCandidates = estimatedCandidates;
            }

            static Approximation fullScan(int maxDoc) {
                return new Approximation(DocIdSetIterator.all(maxDoc), maxDoc);
            }

            float estimatedMatchCost(int maxDoc) {
                if (maxDoc <= 0 || estimatedCandidates <= 0 || estimatedCandidates >= maxDoc) {
                    return 1000f;
                }
                return Math.max(50f, 1000f * ((float) estimatedCandidates / (float) maxDoc));
            }
        }

        /**
         * Locate the first doc in this leaf that has no value for the target field. Returns -1 if
         * every doc has a value. Runs in O(docCount) rather than O(maxDoc) by walking the
         * iterator's {@code nextDoc()} gaps.
         */
        private static int findMissingFieldDoc(SortedNumericDocValues values, int maxDoc) throws IOException {
            int current = values.nextDoc();
            if (current == DocIdSetIterator.NO_MORE_DOCS) {
                return maxDoc == 0 ? -1 : 0;
            }
            if (current > 0) {
                return 0;
            }
            int previous = current;
            current = values.nextDoc();
            while (current != DocIdSetIterator.NO_MORE_DOCS) {
                if (current != previous + 1) {
                    return previous + 1;
                }
                previous = current;
                current = values.nextDoc();
            }
            if (previous + 1 < maxDoc) {
                return previous + 1;
            }
            return -1;
        }

    }

    @Override
    protected int doHashCode() {
        return Objects.hash(script);
    }

    @Override
    protected boolean doEquals(ScriptQueryBuilder other) {
        return Objects.equals(script, other.script);
    }

}
