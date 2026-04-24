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

import java.util.Collections;
import java.util.Map;
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
     * Convert the predicate to a native Lucene {@link Query}, resolving any {@link ParamRef}
     * bounds against the supplied runtime {@code params} map. Implementations that embed
     * {@link ParamRef}s must override this and follow the hybrid resolution rule:
     * <ul>
     *   <li>If a referenced param is missing ({@code params.get(name) == null}), return
     *       {@code null} and let the caller fall back to the script. This preserves the
     *       throw-on-missing semantics Painless would have produced, so user bugs surface
     *       loudly instead of silently matching the wrong document set.</li>
     *   <li>If the param value is a {@link Number}, pass it through to the field type's query
     *       factory.</li>
     *   <li>Anything else (String, Boolean, List, …) declines rewriting. Painless compiles the
     *       comparison to a Def-dispatched {@code DefMath.gt(Object, Object)} (and friends),
     *       which throws {@link ClassCastException} on non-numeric/non-Character operands — so
     *       a rewrite that accepted those types would be silently more permissive than the
     *       script it replaced.</li>
     * </ul>
     * The default implementation ignores {@code params} and delegates to {@link
     * #toQuery(QueryShardContext)} — correct for predicates that don't hold any {@link ParamRef}.
     */
    public Query toQuery(QueryShardContext context, Map<String, Object> params) {
        return toQuery(context);
    }

    /**
     * A half- or fully-bounded range predicate on a single field. Bounds are {@link Object} so
     * they can hold either a compile-time {@link Long} literal or a {@link ParamRef} placeholder
     * that resolves against the runtime {@code params} map at {@link #toQuery(QueryShardContext,
     * Map)} time. Either bound may be {@code null} to mean unbounded in that direction.
     */
    public static final class Range extends ExtractedPredicate {
        private final String field;
        private final Object lower;
        private final Object upper;
        private final boolean includeLower;
        private final boolean includeUpper;

        public Range(String field, Object lower, Object upper, boolean includeLower, boolean includeUpper) {
            this.field = Objects.requireNonNull(field, "field");
            this.lower = lower;
            this.upper = upper;
            this.includeLower = includeLower;
            this.includeUpper = includeUpper;
        }

        public String field() {
            return field;
        }

        public Object lower() {
            return lower;
        }

        public Object upper() {
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
            return toQuery(context, Collections.emptyMap());
        }

        @Override
        public Query toQuery(QueryShardContext context, Map<String, Object> params) {
            MappedFieldType fieldType = context.fieldMapper(field);
            if (fieldType == null) {
                return null;
            }
            Object resolvedLower = resolveBound(lower, params);
            if (resolvedLower == UNRESOLVABLE) {
                return null;
            }
            Object resolvedUpper = resolveBound(upper, params);
            if (resolvedUpper == UNRESOLVABLE) {
                return null;
            }
            try {
                return fieldType.rangeQuery(
                    resolvedLower,
                    resolvedUpper,
                    includeLower,
                    includeUpper,
                    null,
                    null,
                    null,
                    context
                );
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
     * Sentinel returned by {@link #resolveBound} when a bound can't be used. Distinct from
     * {@code null}, which means "no bound in this direction" and is always valid.
     */
    private static final Object UNRESOLVABLE = new Object();

    /**
     * Resolve a Term's value to an object suitable for {@link MappedFieldType#termQuery} on a
     * keyword field. Literal strings pass through; {@link ParamRef}s look up their value in
     * {@code params} and must resolve to a {@link String} to preserve Painless'
     * {@code DefMath.eq} semantics.
     *
     * <p>The rule differs from {@link #resolveBound}: numeric range comparisons only accept
     * {@link Number} (Painless throws on non-Number operands), while string equality only
     * accepts {@link String} (Painless returns {@code false} on non-String operands, never
     * throws, but the rewrite's {@code BytesRefs.toBytesRef} would invoke {@code toString()}
     * on any {@code Object} and silently match documents whose stored keyword equals that
     * stringification — which is strictly more permissive than the script).
     */
    private static Object resolveTermValue(Object value, Map<String, Object> params) {
        if (value instanceof ParamRef == false) {
            return value;
        }
        Object resolved = params.get(((ParamRef) value).name());
        if (resolved instanceof String) {
            return resolved;
        }
        // null, Number, Boolean, List, nested maps — all decline. Painless' `.equals()` would
        // return false for these (never match), and we preserve that by not matching anything
        // either via the script path.
        return UNRESOLVABLE;
    }

    /**
     * Resolve a Range bound to an object suitable for {@link MappedFieldType#rangeQuery}. Literal
     * bounds pass through; {@link ParamRef}s look up their value in {@code params}. Returns the
     * {@link #UNRESOLVABLE} sentinel when a ParamRef can't be resolved safely — the caller must
     * then return {@code null} so the script runs and surfaces the user's bug. See the
     * hybrid-resolution documentation on {@link #toQuery(QueryShardContext, Map)}.
     */
    private static Object resolveBound(Object bound, Map<String, Object> params) {
        if (bound instanceof ParamRef == false) {
            return bound;
        }
        Object value = params.get(((ParamRef) bound).name());
        if (value == null) {
            // Missing param: Painless would have thrown on the first doc. Decline so the script
            // runs and the user sees the exception instead of a silently wrong match-all range.
            return UNRESOLVABLE;
        }
        if (value instanceof Number) {
            return value;
        }
        // Anything else is refused even though some types (e.g. numeric String) would parse fine
        // inside MappedFieldType.rangeQuery. The script path itself doesn't accept them — Painless
        // compiles `long > Object` through DefMath.gt(Object, Object), which throws
        // ClassCastException on e.g. Long-vs-String / Long-vs-Boolean / Long-vs-List — so a
        // rewrite that did accept them would be silently more permissive than the script. Decline
        // and let the script run so the user sees the same error it would have thrown.
        return UNRESOLVABLE;
    }

    /**
     * A placeholder for a script-level {@code params.<name>} reference that the Painless
     * predicate-extraction phase captured at compile time. {@link Range} holds one of these in
     * place of a literal bound; {@link Range#toQuery(QueryShardContext, Map)} resolves it at
     * query-build time against the runtime params map.
     */
    public static final class ParamRef {
        private final String name;

        public ParamRef(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        public String name() {
            return name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if ((o instanceof ParamRef) == false) return false;
            return name.equals(((ParamRef) o).name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public String toString() {
            return "params." + name;
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
            return toQuery(context, Collections.emptyMap());
        }

        @Override
        public Query toQuery(QueryShardContext context, Map<String, Object> params) {
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
            Object resolved = resolveTermValue(value, params);
            if (resolved == UNRESOLVABLE) {
                return null;
            }
            try {
                return fieldType.termQuery(resolved, context);
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
