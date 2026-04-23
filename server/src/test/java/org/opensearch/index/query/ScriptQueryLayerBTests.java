/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.query;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.opensearch.index.fielddata.ScriptDocValues;
import org.opensearch.script.FilterScript;
import org.opensearch.script.Script;
import org.opensearch.search.lookup.LeafSearchLookup;
import org.opensearch.search.lookup.SearchLookup;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the Layer-B skipper-based approximation in {@link ScriptQueryBuilder.ScriptQuery}. The
 * test builds a real in-memory index with a numeric field that carries a doc-values skip index,
 * constructs a {@link FilterScript.LeafFactory} that reads that field, and compares the hits
 * against a deterministic {@link LongPredicate} ground truth.
 */
public class ScriptQueryLayerBTests extends OpenSearchTestCase {

    private static final String FIELD = "price";
    private static final String KEYWORD_FIELD = "status";

    public void testSparseFieldFallsBackToFullScanAndKeepsSameHits() throws IOException {
        try (Directory dir = newDirectory()) {
            int numDocs = 5_000;
            long[] values = new long[numDocs];
            boolean[] present = new boolean[numDocs];
            try (IndexWriter iw = new IndexWriter(dir, new IndexWriterConfig(null))) {
                for (int i = 0; i < numDocs; i++) {
                    Document doc = new Document();
                    if (i % 3 != 0) { // ~2/3 of docs have a value, 1/3 are sparse
                        long v = i * 7L - 10_000L;
                        values[i] = v;
                        present[i] = true;
                        doc.add(SortedNumericDocValuesField.indexedField(FIELD, v));
                    }
                    iw.addDocument(doc);
                }
                iw.forceMerge(1);
            }

            try (IndexReader reader = DirectoryReader.open(dir)) {
                IndexSearcher searcher = newSearcher(reader);
                LongPredicate predicate = v -> v > 0 && v < 8_000;

                // Compute expected hits directly.
                int expected = 0;
                for (int i = 0; i < numDocs; i++) {
                    if (present[i] && predicate.test(values[i])) expected++;
                }

                Set<String> accessedFields = new HashSet<>(Collections.singleton(FIELD));
                AtomicInteger scriptInvocations = new AtomicInteger();
                Set<Integer> visitedDocs = ConcurrentHashMap.newKeySet();
                FilterScript.LeafFactory leafFactory = ctx -> new NumericPredicateFilterScript(
                    ctx,
                    FIELD,
                    predicate,
                    scriptInvocations,
                    visitedDocs
                );
                ScriptQueryBuilder.ScriptQuery query = new ScriptQueryBuilder.ScriptQuery(
                    mockScript(),
                    leafFactory,
                    accessedFields,
                    false,
                    null
                );

                TopDocs topDocs = searcher.search(query, numDocs);
                assertEquals(expected, topDocs.totalHits.value());

                // Sparse fields must now stay on the full-scan path because a single-field hint is
                // not enough to prove that every missing doc is a non-match.
                int firstMissing = -1;
                for (int i = 0; i < numDocs; i++) {
                    if (!present[i]) {
                        firstMissing = i;
                        break;
                    }
                }
                assertTrue("full-scan path should visit a missing-field doc", firstMissing >= 0 && visitedDocs.contains(firstMissing));
            }
        }
    }

    public void testFullScanWhenMissingDocsMatch() throws IOException {
        try (Directory dir = newDirectory()) {
            int numDocs = 200;
            long[] values = new long[numDocs];
            boolean[] present = new boolean[numDocs];
            try (IndexWriter iw = new IndexWriter(dir, new IndexWriterConfig(null))) {
                for (int i = 0; i < numDocs; i++) {
                    Document doc = new Document();
                    if (i % 4 != 0) {
                        long v = i - 50L;
                        values[i] = v;
                        present[i] = true;
                        doc.add(SortedNumericDocValuesField.indexedField(FIELD, v));
                    }
                    iw.addDocument(doc);
                }
                iw.forceMerge(1);
            }

            try (IndexReader reader = DirectoryReader.open(dir)) {
                IndexSearcher searcher = newSearcher(reader);
                // Predicate that matches missing-field docs (because ScriptDocValues.Longs treats
                // missing as 0L, and this predicate returns true for 0 too).
                LongPredicate predicate = v -> v >= 0;

                int expected = 0;
                for (int i = 0; i < numDocs; i++) {
                    long fieldValue = present[i] ? values[i] : 0L;
                    if (predicate.test(fieldValue)) expected++;
                }

                Set<String> accessedFields = new HashSet<>(Collections.singleton(FIELD));
                AtomicInteger scriptInvocations = new AtomicInteger();
                FilterScript.LeafFactory leafFactory = ctx -> new NumericPredicateFilterScript(ctx, FIELD, predicate, scriptInvocations);
                ScriptQueryBuilder.ScriptQuery query = new ScriptQueryBuilder.ScriptQuery(
                    mockScript(),
                    leafFactory,
                    accessedFields,
                    false,
                    null
                );

                TopDocs topDocs = searcher.search(query, numDocs);
                assertEquals(expected, topDocs.totalHits.value());
            }
        }
    }

    public void testFallsBackWhenNoSkipper() throws IOException {
        try (Directory dir = newDirectory()) {
            int numDocs = 100;
            try (IndexWriter iw = new IndexWriter(dir, new IndexWriterConfig(null))) {
                for (int i = 0; i < numDocs; i++) {
                    Document doc = new Document();
                    // Plain (non-indexedField) doc values have no skipper.
                    doc.add(new SortedNumericDocValuesField(FIELD, i));
                    iw.addDocument(doc);
                }
                iw.forceMerge(1);
            }

            try (IndexReader reader = DirectoryReader.open(dir)) {
                IndexSearcher searcher = newSearcher(reader);
                LongPredicate predicate = v -> v >= 50;
                Set<String> accessedFields = new HashSet<>(Collections.singleton(FIELD));
                FilterScript.LeafFactory leafFactory = ctx -> new NumericPredicateFilterScript(ctx, FIELD, predicate, new AtomicInteger());
                ScriptQueryBuilder.ScriptQuery query = new ScriptQueryBuilder.ScriptQuery(
                    mockScript(),
                    leafFactory,
                    accessedFields,
                    false,
                    null
                );
                TopDocs topDocs = searcher.search(query, numDocs);
                assertEquals(50, topDocs.totalHits.value());
            }
        }
    }

    public void testFullScanPathWhenAccessedFieldsEmpty() throws IOException {
        // When accessedDocFields is empty (script engine did not introspect), the skipper path must
        // not be taken, regardless of whether a skipper happens to exist.
        try (Directory dir = newDirectory()) {
            int numDocs = 50;
            try (IndexWriter iw = new IndexWriter(dir, new IndexWriterConfig(null))) {
                for (int i = 0; i < numDocs; i++) {
                    Document doc = new Document();
                    doc.add(SortedNumericDocValuesField.indexedField(FIELD, i));
                    iw.addDocument(doc);
                }
                iw.forceMerge(1);
            }
            try (IndexReader reader = DirectoryReader.open(dir)) {
                IndexSearcher searcher = newSearcher(reader);
                ScriptQueryBuilder.ScriptQuery query = new ScriptQueryBuilder.ScriptQuery(
                    mockScript(),
                    ctx -> new NumericPredicateFilterScript(ctx, FIELD, v -> v >= 25, new AtomicInteger()),
                    Collections.emptySet(),
                    false,
                    null
                );
                TopDocs topDocs = searcher.search(query, numDocs);
                assertEquals(25, topDocs.totalHits.value());
            }
        }
    }

    public void testKeywordDocValuesFieldFallsBackInsteadOfAssumingSortedNumeric() throws IOException {
        try (Directory dir = newDirectory()) {
            int numDocs = 3;
            try (IndexWriter iw = new IndexWriter(dir, new IndexWriterConfig(null))) {
                for (int i = 0; i < numDocs; i++) {
                    Document doc = new Document();
                    doc.add(new SortedDocValuesField(KEYWORD_FIELD, new BytesRef("value-" + i)));
                    iw.addDocument(doc);
                }
                iw.forceMerge(1);
            }
            try (IndexReader reader = DirectoryReader.open(dir)) {
                IndexSearcher searcher = newSearcher(reader);
                ScriptQueryBuilder.ScriptQuery query = new ScriptQueryBuilder.ScriptQuery(
                    mockScript(),
                    MatchAllFilterScript::new,
                    Collections.singleton(KEYWORD_FIELD),
                    false,
                    null
                );

                TopDocs topDocs = searcher.search(query, numDocs);
                assertEquals(numDocs, topDocs.totalHits.value());
            }
        }
    }

    public void testSparseFieldStillFallsBackWhenCostOverreportsCoverage() throws IOException {
        try (Directory dir = newDirectory()) {
            int numDocs = 40;
            boolean[] present = new boolean[numDocs];
            try (IndexWriter iw = new IndexWriter(dir, new IndexWriterConfig(null))) {
                for (int i = 0; i < numDocs; i++) {
                    Document doc = new Document();
                    if (i % 5 != 0) {
                        present[i] = true;
                        doc.add(SortedNumericDocValuesField.indexedField(FIELD, i));
                    }
                    iw.addDocument(doc);
                }
                iw.forceMerge(1);
            }

            try (DirectoryReader baseReader = DirectoryReader.open(dir);
                DirectoryReader wrappedReader = wrapSparseNumericFieldWithOverreportedCost(baseReader, FIELD)) {
                IndexSearcher searcher = newSearcher(wrappedReader);
                Set<Integer> visitedDocs = ConcurrentHashMap.newKeySet();
                ScriptQueryBuilder.ScriptQuery query = new ScriptQueryBuilder.ScriptQuery(
                    mockScript(),
                    ctx -> new NumericPredicateFilterScript(ctx, FIELD, v -> v > 0, new AtomicInteger(), visitedDocs),
                    Collections.singleton(FIELD),
                    false,
                    null
                );

                TopDocs topDocs = searcher.search(query, numDocs);
                assertEquals(32, topDocs.totalHits.value());

                int firstMissing = -1;
                for (int i = 0; i < numDocs; i++) {
                    if (present[i] == false) {
                        firstMissing = i;
                        break;
                    }
                }
                assertTrue("full-scan path should still visit a missing-field doc even when cost() lies", visitedDocs.contains(firstMissing));
            }
        }
    }

    public void testIsCacheableFollowsDeterminism() throws IOException {
        try (Directory dir = newDirectory()) {
            try (IndexWriter iw = new IndexWriter(dir, new IndexWriterConfig(null))) {
                Document doc = new Document();
                doc.add(new SortedNumericDocValuesField(FIELD, 1));
                iw.addDocument(doc);
                iw.forceMerge(1);
            }
            try (IndexReader reader = DirectoryReader.open(dir)) {
                IndexSearcher searcher = newSearcher(reader);
                LeafReaderContext leaf = reader.leaves().get(0);

                ScriptQueryBuilder.ScriptQuery deterministic = new ScriptQueryBuilder.ScriptQuery(
                    mockScript(),
                    ctx -> new NumericPredicateFilterScript(ctx, FIELD, v -> true, new AtomicInteger()),
                    Collections.emptySet(),
                    true,
                    null
                );
                assertTrue(
                    "deterministic script query should be cacheable",
                    deterministic.createWeight(searcher, org.apache.lucene.search.ScoreMode.COMPLETE_NO_SCORES, 1f).isCacheable(leaf)
                );

                ScriptQueryBuilder.ScriptQuery nonDeterministic = new ScriptQueryBuilder.ScriptQuery(
                    mockScript(),
                    ctx -> new NumericPredicateFilterScript(ctx, FIELD, v -> true, new AtomicInteger()),
                    Collections.emptySet(),
                    false,
                    null
                );
                assertFalse(
                    "non-deterministic script query must not be cacheable",
                    nonDeterministic.createWeight(searcher, org.apache.lucene.search.ScoreMode.COMPLETE_NO_SCORES, 1f).isCacheable(leaf)
                );
            }
        }
    }

    private static Script mockScript() {
        return new Script("mock");
    }

    /**
     * A {@link FilterScript} wired directly to a {@link LongPredicate} over a single sorted numeric
     * doc-values field, simulating what Painless would emit for a script like
     * {@code doc['price'].value > 0 && doc['price'].value < 8000}. We can't reuse Painless here
     * because the server tests compile without the Painless module.
     */
    private static final class NumericPredicateFilterScript extends FilterScript {
        private final ScriptDocValues.Longs values;
        private final LongPredicate predicate;
        private final AtomicInteger invocations;
        private final Set<Integer> visitedDocs;
        private int currentDoc = -1;

        NumericPredicateFilterScript(
            LeafReaderContext ctx,
            String field,
            LongPredicate predicate,
            AtomicInteger invocations,
            Set<Integer> visitedDocs
        ) throws IOException {
            super(Collections.emptyMap(), mockLookup(), ctx);
            this.predicate = predicate;
            this.invocations = invocations;
            this.visitedDocs = visitedDocs;
            this.values = new ScriptDocValues.Longs(org.apache.lucene.index.DocValues.getSortedNumeric(ctx.reader(), field));
        }

        NumericPredicateFilterScript(LeafReaderContext ctx, String field, LongPredicate predicate, AtomicInteger invocations)
            throws IOException {
            this(ctx, field, predicate, invocations, ConcurrentHashMap.newKeySet());
        }

        @Override
        public void setDocument(int docid) {
            try {
                values.setNextDocId(docid);
                this.currentDoc = docid;
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public boolean execute() {
            invocations.incrementAndGet();
            visitedDocs.add(currentDoc);
            long v = values.size() == 0 ? 0L : values.getValue();
            return predicate.test(v);
        }

        private static SearchLookup mockLookup() {
            SearchLookup lookup = mock(SearchLookup.class);
            LeafSearchLookup leaf = mock(LeafSearchLookup.class);
            when(lookup.getLeafSearchLookup(any(LeafReaderContext.class))).thenReturn(leaf);
            return lookup;
        }
    }

    private static final class MatchAllFilterScript extends FilterScript {
        MatchAllFilterScript(LeafReaderContext ctx) {
            super(Collections.emptyMap(), NumericPredicateFilterScript.mockLookup(), ctx);
        }

        @Override
        public boolean execute() {
            return true;
        }
    }

    private static DirectoryReader wrapSparseNumericFieldWithOverreportedCost(DirectoryReader in, String field) throws IOException {
        return new FilterDirectoryReader(in, new FilterDirectoryReader.SubReaderWrapper() {
            @Override
            public LeafReader wrap(LeafReader reader) {
                return new FilterLeafReader(reader) {
                    @Override
                    public SortedNumericDocValues getSortedNumericDocValues(String fieldName) throws IOException {
                        SortedNumericDocValues values = super.getSortedNumericDocValues(fieldName);
                        if (field.equals(fieldName) == false || values == null) {
                            return values;
                        }
                        return new SortedNumericDocValues() {
                            @Override
                            public long nextValue() throws IOException {
                                return values.nextValue();
                            }

                            @Override
                            public int docValueCount() {
                                return values.docValueCount();
                            }

                            @Override
                            public boolean advanceExact(int target) throws IOException {
                                return values.advanceExact(target);
                            }

                            @Override
                            public int docID() {
                                return values.docID();
                            }

                            @Override
                            public int nextDoc() throws IOException {
                                return values.nextDoc();
                            }

                            @Override
                            public int advance(int target) throws IOException {
                                return values.advance(target);
                            }

                            @Override
                            public long cost() {
                                return maxDoc();
                            }
                        };
                    }

                    @Override
                    public IndexReader.CacheHelper getCoreCacheHelper() {
                        return reader.getCoreCacheHelper();
                    }

                    @Override
                    public IndexReader.CacheHelper getReaderCacheHelper() {
                        return reader.getReaderCacheHelper();
                    }
                };
            }
        }) {
            @Override
            protected DirectoryReader doWrapDirectoryReader(DirectoryReader in) throws IOException {
                return wrapSparseNumericFieldWithOverreportedCost(in, field);
            }

            @Override
            public IndexReader.CacheHelper getReaderCacheHelper() {
                return in.getReaderCacheHelper();
            }
        };
    }

    @Override
    protected boolean enableWarningsCheck() {
        return false;
    }
}
