/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.script;

import org.apache.lucene.search.Query;
import org.opensearch.common.lucene.Lucene;
import org.opensearch.index.mapper.KeywordFieldMapper;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.index.query.QueryShardContext;

import java.util.Objects;

/**
 * A structured predicate that a script engine has extracted from a compiled script. The script
 * query may substitute the script with a native Lucene query produced by {@link #toQuery},
 * bypassing per-doc script evaluation entirely.
 *
 * <p>This is an opt-in optimization surface. Script engines that cannot introspect their scripts
 * continue to return {@code null} from the factory hook and nothing changes. A non-null
 * {@code ExtractedPredicate} is a best-effort rewrite hint; returning {@code null} from
 * {@link #toQuery} is always safe and causes the caller to fall back to normal script execution.
 */
public abstract class ExtractedPredicate {

    ExtractedPredicate() {}

    /**
     * Convert the predicate to a native Lucene {@link Query} by consulting the shard context. If
     * the referenced field is not mapped, is of an incompatible type, or rewriting is otherwise
     * unsafe, implementations must return {@code null} so the caller falls back to script
     * evaluation.
     */
    public abstract Query toQuery(QueryShardContext context);

    /**
     * A half- or fully-bounded range predicate on a single field. Bounds use {@link Long} to cover
     * numeric comparisons emitted by the Painless predicate-extraction phase. Either bound may be
     * {@code null} to mean unbounded in that direction.
     */
    public static final class Range extends ExtractedPredicate {
        private final String field;
        private final Long lower;
        private final Long upper;
        private final boolean includeLower;
        private final boolean includeUpper;

        public Range(String field, Long lower, Long upper, boolean includeLower, boolean includeUpper) {
            this.field = Objects.requireNonNull(field, "field");
            this.lower = lower;
            this.upper = upper;
            this.includeLower = includeLower;
            this.includeUpper = includeUpper;
        }

        public String field() {
            return field;
        }

        public Long lower() {
            return lower;
        }

        public Long upper() {
            return upper;
        }

        public boolean includeLower() {
            return includeLower;
        }

        public boolean includeUpper() {
            return includeUpper;
        }

        @Override
        public Query toQuery(QueryShardContext context) {
            MappedFieldType fieldType = context.fieldMapper(field);
            if (fieldType == null) {
                return null;
            }
            try {
                return fieldType.rangeQuery(lower, upper, includeLower, includeUpper, null, null, null, context);
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                // Field type doesn't support range queries, or the bounds don't parse against this
                // field type. In either case fall back to the script.
                return null;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Range r = (Range) o;
            return includeLower == r.includeLower
                && includeUpper == r.includeUpper
                && field.equals(r.field)
                && Objects.equals(lower, r.lower)
                && Objects.equals(upper, r.upper);
        }

        @Override
        public int hashCode() {
            return Objects.hash(field, lower, upper, includeLower, includeUpper);
        }

        @Override
        public String toString() {
            return "Range{"
                + "field='"
                + field
                + '\''
                + ", lower="
                + lower
                + ", upper="
                + upper
                + ", includeLower="
                + includeLower
                + ", includeUpper="
                + includeUpper
                + '}';
        }
    }

    /**
     * A single-value equality predicate on one field, produced from shapes like
     * {@code doc['status'].size() == 1 && doc['status'].value == 'active'}. The value is typed as
     * {@code Object} to pass through whatever the script engine captured (typically {@code String}
     * for keyword fields); {@link MappedFieldType#termQuery} handles the type coercion.
     */
    public static final class Term extends ExtractedPredicate {
        private final String field;
        private final Object value;

        public Term(String field, Object value) {
            this.field = Objects.requireNonNull(field, "field");
            this.value = Objects.requireNonNull(value, "value");
        }

        public String field() {
            return field;
        }

        public Object value() {
            return value;
        }

        @Override
        public Query toQuery(QueryShardContext context) {
            MappedFieldType fieldType = context.fieldMapper(field);
            if (fieldType == null) {
                return null;
            }
            // Painless' `doc['f'].value` reads the raw stored bytes from doc values, so the only
            // field types where `termQuery(literal)` is script-equivalent are those that look up
            // the literal without analysis or coercion:
            //   - text: `termQuery` searches analyzed tokens, which differ from the raw value the
            //     script reads; fielddata may also be disabled, in which case the script throws
            //     while the rewrite silently matches/misses.
            //   - keyword with a normalizer: the literal is lowercased / asciifolded before
            //     lookup, so `'Active'` matches a stored `active` even though the script reading
            //     the raw `active` and comparing to `'Active'` returns false.
            // Restrict to KeywordFieldType whose search analyzer is the keyword analyzer
            // (i.e. no normalizer). Anything else falls back to the script.
            if ((fieldType instanceof KeywordFieldMapper.KeywordFieldType) == false) {
                return null;
            }
            if (fieldType.getTextSearchInfo().getSearchAnalyzer() != Lucene.KEYWORD_ANALYZER) {
                return null;
            }
            try {
                return fieldType.termQuery(value, context);
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                // Field type doesn't support term queries, or the value doesn't parse against
                // this field type. Fall back to the script in either case.
                return null;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Term t = (Term) o;
            return field.equals(t.field) && value.equals(t.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(field, value);
        }

        @Override
        public String toString() {
            return "Term{field='" + field + "', value=" + value + '}';
        }
    }
}
