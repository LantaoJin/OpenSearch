/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.script;

import org.apache.lucene.search.Query;
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
}
