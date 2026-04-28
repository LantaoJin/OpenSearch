/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.script;

import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.opensearch.common.lucene.Lucene;
import org.opensearch.index.mapper.KeywordFieldMapper;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.index.mapper.NumberFieldMapper;
import org.opensearch.index.query.QueryShardContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
            // Range is only emitted for numeric comparisons, and the underlying Painless
            // semantics rely on `DefMath` throwing `ClassCastException` on type mismatches.
            // Some non-numeric field types (notably `KeywordFieldType`) implement
            // `rangeQuery` by stringifying the bounds via `BytesRefs.toBytesRef` and building a
            // lexicographic `TermRangeQuery` — so `value > 10` on a keyword field would silently
            // match `"11"`, `"2"`, `"active"`, etc., whereas Painless would have thrown. Gate
            // the rewrite to numeric field types so the rewrite and the script have the same
            // outcome on every doc.
            if ((fieldType instanceof NumberFieldMapper.NumberFieldType) == false) {
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
        if (value instanceof ParamRef) {
            value = params.get(((ParamRef) value).name());
        }
        // Defense-in-depth: the carrier's constructor is public with a raw `Object` value, so a
        // third-party factory or a future phase change could hand us a non-String here. `BytesRefs
        // .toBytesRef` would stringify any Object (Integer → "1", Boolean → "true", even another
        // ParamRef via our custom toString → "params.x"), which `termQuery` would then look up
        // bit-exactly — strictly more permissive than Painless' `.equals()` on the raw object.
        // Require String to keep the rewrite equivalent to the script.
        if (value instanceof String) {
            return value;
        }
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

    /**
     * A set-membership predicate on one field, produced from shapes like
     * {@code doc['status'].size() == 1 && ['active', params.extra].contains(doc['status'].value)}
     * or {@code doc['status'].size() == 1 && params.statusList.contains(doc['status'].value)}.
     * Two element sources are supported:
     * <ul>
     *   <li><b>Inline element list</b>: each element is either a {@link String} literal or a
     *       {@link ParamRef} placeholder for {@code params.<name>}. Emitted when the list literal
     *       appears inline in the script.</li>
     *   <li><b>Whole-list ParamRef</b>: a single {@link ParamRef} that resolves at query-build
     *       time to a {@code List<?>} whose every element must be a {@link String}. Emitted when
     *       the list itself is passed as {@code params.listName}.</li>
     * </ul>
     * In both cases {@link MappedFieldType#termsQuery} converts the resolved values to
     * {@code BytesRef}s via the same {@code BytesRefs.toBytesRef} path {@link Term#toQuery}
     * relies on.
     *
     * <p>Applies the same two-part field-type gate as {@link Term}: only plain
     * {@link KeywordFieldMapper.KeywordFieldType} without a configured normalizer is accepted,
     * because normalized/analyzed fields would apply analysis to each list element and match a
     * different document set than Painless' raw-bytes {@code .equals()}.
     *
     * <p>{@link ParamRef} elements (or a whole-list ParamRef whose resolved list contains one)
     * are resolved at query-build time through the same String-only rule as {@link Term}: any
     * element that resolves to a {@code Number}, {@code Boolean}, {@code null}, or any other
     * non-{@code String} declines the whole rewrite so the script runs and mirrors Painless'
     * {@code .equals()} semantics on the raw element.
     */
    public static final class Terms extends ExtractedPredicate {
        private final String field;
        private final List<Object> values;
        private final ParamRef listParam;

        public Terms(String field, List<Object> values) {
            this.field = Objects.requireNonNull(field, "field");
            this.values = Collections.unmodifiableList(Objects.requireNonNull(values, "values"));
            this.listParam = null;
        }

        /**
         * Whole-list-ParamRef constructor: the list itself is a {@code params.<name>} reference.
         * At query-build time the ParamRef resolves to a {@code List<?>} and each element is
         * validated through the same String-only rule as {@link Term}.
         */
        public Terms(String field, ParamRef listParam) {
            this.field = Objects.requireNonNull(field, "field");
            this.listParam = Objects.requireNonNull(listParam, "listParam");
            this.values = null;
        }

        public String field() {
            return field;
        }

        /**
         * Element list for the inline-list shape. Returns {@code null} when the carrier was built
         * with the whole-list-ParamRef constructor.
         */
        public List<Object> values() {
            return values;
        }

        /**
         * ParamRef for the whole-list shape. Returns {@code null} when the carrier was built with
         * an inline element list.
         */
        public ParamRef listParam() {
            return listParam;
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
            if ((fieldType instanceof KeywordFieldMapper.KeywordFieldType) == false) {
                return null;
            }
            if (fieldType.getTextSearchInfo().getSearchAnalyzer() != Lucene.KEYWORD_ANALYZER) {
                return null;
            }
            List<Object> source;
            if (listParam != null) {
                // Whole-list ParamRef: resolve it to a concrete List now. Missing (null) declines
                // to mirror Painless' NPE on `null.contains(...)`; a non-List declines because
                // Painless would throw ClassCastException on `.contains` dispatch against a
                // non-Collection. Declining lets the script run so the user sees the same error.
                Object resolved = params.get(listParam.name());
                if ((resolved instanceof List) == false) {
                    return null;
                }
                source = new ArrayList<>((List<?>) resolved);
            } else {
                source = values;
            }
            // Per-element validation applies the same String-only rule as Term. The public
            // constructor takes raw {@code Object} elements, so a future phase change or a
            // third-party factory could hand us a list with a numeric, Boolean, or ParamRef
            // element whose `toString()` would silently match a document whose stored keyword
            // equals that stringification. Resolve each element through the Term path and
            // decline the whole rewrite if any element fails — partial lookup would be wrong
            // because `List.contains` requires exact element matches, not some-of-matches.
            List<Object> resolved = new ArrayList<>(source.size());
            for (Object element : source) {
                Object r = resolveTermValue(element, params);
                if (r == UNRESOLVABLE) {
                    return null;
                }
                resolved.add(r);
            }
            try {
                return fieldType.termsQuery(resolved, context);
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                return null;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Terms t = (Terms) o;
            return field.equals(t.field) && Objects.equals(values, t.values) && Objects.equals(listParam, t.listParam);
        }

        @Override
        public int hashCode() {
            return Objects.hash(field, values, listParam);
        }

        @Override
        public String toString() {
            return "Terms{field='" + field + "', values=" + (listParam != null ? listParam : values) + '}';
        }
    }

    /**
     * A disjunction of other extracted predicates, produced from shapes like
     * {@code doc['f'].size() == 1 && (doc['f'].value < 10 || doc['f'].value > 100)}. Rewrites to
     * a {@link BooleanQuery} of SHOULD clauses with {@code minimumShouldMatch=1} — Lucene's
     * native union. Each clause validates itself at {@link #toQuery} time; the {@code Or}
     * carrier contributes no additional field-type gate.
     *
     * <p>Soundness derives from two properties the Painless phase enforces at extraction time:
     * <ul>
     *   <li>All clauses reference the same guarded field, so the shared {@code size() == 1}
     *       guard dominates every clause at runtime.</li>
     *   <li>Each clause is an independently rewrite-safe shape ({@link Range} or {@link Term}
     *       on that field).</li>
     * </ul>
     * Under these properties Painless' short-circuit {@code ||} and Lucene's unconditional
     * SHOULD produce the same doc-matching set — only the per-doc evaluation order differs,
     * and we're skipping the script entirely.
     */
    public static final class Or extends ExtractedPredicate {
        private final List<ExtractedPredicate> clauses;

        public Or(List<ExtractedPredicate> clauses) {
            Objects.requireNonNull(clauses, "clauses");
            if (clauses.size() < 2) {
                // The phase only constructs `Or` when it flattens an `||` AST node, which has
                // exactly two operands. Guarding here protects against future callers.
                throw new IllegalArgumentException("Or requires at least two clauses, got " + clauses.size());
            }
            List<ExtractedPredicate> copy = new ArrayList<>(clauses.size());
            for (ExtractedPredicate clause : clauses) {
                copy.add(Objects.requireNonNull(clause, "clause"));
            }
            this.clauses = Collections.unmodifiableList(copy);
        }

        public List<ExtractedPredicate> clauses() {
            return clauses;
        }

        @Override
        public Query toQuery(QueryShardContext context) {
            return toQuery(context, Collections.emptyMap());
        }

        @Override
        public Query toQuery(QueryShardContext context, Map<String, Object> params) {
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            try {
                for (ExtractedPredicate clause : clauses) {
                    Query clauseQuery = clause.toQuery(context, params);
                    if (clauseQuery == null) {
                        // Any clause that can't rewrite invalidates the whole union — a partial Or
                        // would match a strict subset of what the script matches, so we decline
                        // and let the script run.
                        return null;
                    }
                    builder.add(clauseQuery, BooleanClause.Occur.SHOULD);
                }
                builder.setMinimumNumberShouldMatch(1);
                return builder.build();
            } catch (IndexSearcher.TooManyClauses e) {
                // Disjunctions longer than BooleanQuery.getMaxClauseCount() (1024 by default)
                // can't round-trip through the builder. Rather than failing the whole query
                // request, decline and let the script run on the per-doc fallback path.
                return null;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            return clauses.equals(((Or) o).clauses);
        }

        @Override
        public int hashCode() {
            return clauses.hashCode();
        }

        @Override
        public String toString() {
            return "Or" + clauses;
        }
    }

    /**
     * A conjunction of other extracted predicates, produced from multi-field guarded shapes like
     * {@code doc['a'].size() == 1 && doc['a'].value > 10 && doc['b'].size() == 1 && doc['b'].value < 20}.
     * Rewrites to a {@link BooleanQuery} of MUST clauses. Each clause validates itself at
     * {@link #toQuery} time; the {@code And} carrier contributes no additional field-type gate.
     *
     * <p>Soundness derives from two properties the Painless phase enforces at extraction time:
     * <ul>
     *   <li>Each clause covers a different field, and each clause's {@code size() == 1} guard
     *       dominates its own {@code .value} reads at runtime — per-field blocks are independent
     *       from each other.</li>
     *   <li>Each clause is an independently rewrite-safe shape ({@link Range}, {@link Term},
     *       {@link Terms}, or {@link Or} on a single field).</li>
     * </ul>
     * Under these properties Painless' short-circuit {@code &&} and Lucene's unconditional MUST
     * conjunction produce the same doc-matching set. In the script, block <i>k</i> only runs
     * when every earlier block passed; in the rewrite, every MUST clause evaluates, but a doc
     * is in the result set iff every clause matches — same outcome.
     */
    public static final class And extends ExtractedPredicate {
        private final List<ExtractedPredicate> clauses;

        public And(List<ExtractedPredicate> clauses) {
            Objects.requireNonNull(clauses, "clauses");
            if (clauses.size() < 2) {
                // Single-block extraction is emitted as the block's predicate directly; an And of
                // one clause would be a degenerate wrapper.
                throw new IllegalArgumentException("And requires at least two clauses, got " + clauses.size());
            }
            List<ExtractedPredicate> copy = new ArrayList<>(clauses.size());
            for (ExtractedPredicate clause : clauses) {
                copy.add(Objects.requireNonNull(clause, "clause"));
            }
            this.clauses = Collections.unmodifiableList(copy);
        }

        public List<ExtractedPredicate> clauses() {
            return clauses;
        }

        @Override
        public Query toQuery(QueryShardContext context) {
            return toQuery(context, Collections.emptyMap());
        }

        @Override
        public Query toQuery(QueryShardContext context, Map<String, Object> params) {
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            try {
                for (ExtractedPredicate clause : clauses) {
                    Query clauseQuery = clause.toQuery(context, params);
                    if (clauseQuery == null) {
                        // Any clause that can't rewrite invalidates the whole conjunction —
                        // a partial And would match a strict superset of what the script
                        // matches, so we decline and let the script run.
                        return null;
                    }
                    builder.add(clauseQuery, BooleanClause.Occur.MUST);
                }
                return builder.build();
            } catch (IndexSearcher.TooManyClauses e) {
                // Mirror Or: very long conjunctions (>= getMaxClauseCount()) fall back to the
                // script path rather than failing the request.
                return null;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            return clauses.equals(((And) o).clauses);
        }

        @Override
        public int hashCode() {
            return clauses.hashCode();
        }

        @Override
        public String toString() {
            return "And" + clauses;
        }
    }

    /**
     * Negation of another extracted predicate, produced from shapes like
     * {@code doc['f'].size() == 1 && doc['f'].value != 'active'} or
     * {@code doc['f'].size() == 1 && !['a','b'].contains(doc['f'].value)}. Rewrites to a
     * {@link BooleanQuery} of {@code MUST existsQuery(field)} + {@code MUST_NOT inner.toQuery},
     * i.e. Lucene's "every doc that has a value for {@code field} except those matching inner".
     *
     * <p>Soundness under the shared {@code size() == 1} guard requires two things:
     * <ol>
     *   <li><b>Missing docs</b> are excluded by the script's guard. The native {@code MUST_NOT
     *       Term('active')} alone would <i>include</i> docs with no value for {@code field}
     *       (they don't match the term, so MUST_NOT doesn't exclude them), whereas Painless'
     *       guard bails on them. The {@code MUST existsQuery(field)} clause closes this by
     *       requiring every result to have a value for {@code field}, mirroring the guard's
     *       missing-doc exclusion.</li>
     *   <li><b>Multi-valued docs</b> are excluded by the script's {@code size() == 1} guard
     *       but are still matched by native {@code MUST existsQuery} + {@code MUST_NOT}. This
     *       is exactly the multi-valued soundness hole that makes the whole feature default-
     *       off (see {@code ALLOW_PREDICATE_EXTRACTION} setting + {@code
     *       MultiValueRewriteSemanticsTests}). For multi-valued docs, native MUST_NOT excludes
     *       docs where <i>any</i> value matches the negated shape, whereas Painless excludes
     *       docs where the <i>first</i> value matches. Under the branch's default-off guard,
     *       the benchmark's single-valued corpora avoid this case.</li>
     * </ol>
     *
     * <p>A {@code Not} whose {@code inner} clause declines its own rewrite (returns
     * {@code null}) also declines — a partial negation (matching everything with an exists
     * clause) would be strictly more permissive than the script. Likewise, the field used for
     * the exists clause must have a working {@code existsQuery}; any exception declines the
     * whole Not.
     */
    public static final class Not extends ExtractedPredicate {
        private final String field;
        private final ExtractedPredicate inner;

        /**
         * @param field the name of the field whose guard the {@code Not} depends on — used to
         *              build the {@code existsQuery(field)} exclusion of missing-valued docs
         *              (see class javadoc). Must be the same field the guard protects and the
         *              inner predicate reads.
         * @param inner the positive predicate to negate (e.g. {@code Term}, {@code Terms},
         *              {@code Range})
         */
        public Not(String field, ExtractedPredicate inner) {
            this.field = Objects.requireNonNull(field, "field");
            this.inner = Objects.requireNonNull(inner, "inner");
        }

        public String field() {
            return field;
        }

        public ExtractedPredicate inner() {
            return inner;
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
            Query innerQuery = inner.toQuery(context, params);
            if (innerQuery == null) {
                // Inner declined (field-type gate, param resolution, etc.). Without the inner,
                // the Not would collapse to "exists(field)", which is strictly more permissive
                // than the script. Decline and let the script run.
                return null;
            }
            Query existsQuery;
            try {
                existsQuery = fieldType.existsQuery(context);
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                // Field types without a working existsQuery can't build the missing-doc
                // exclusion; without it the MUST_NOT would silently include missing docs.
                return null;
            }
            return new BooleanQuery.Builder()
                .add(existsQuery, BooleanClause.Occur.MUST)
                .add(innerQuery, BooleanClause.Occur.MUST_NOT)
                .build();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Not n = (Not) o;
            return field.equals(n.field) && inner.equals(n.inner);
        }

        @Override
        public int hashCode() {
            return Objects.hash(field, inner);
        }

        @Override
        public String toString() {
            return "Not{field='" + field + "', inner=" + inner + '}';
        }
    }
}
