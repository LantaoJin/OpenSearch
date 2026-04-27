/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.painless.phase;

import org.opensearch.painless.Operation;
import org.opensearch.painless.node.AExpression;
import org.opensearch.painless.node.AStatement;
import org.opensearch.painless.node.EBooleanComp;
import org.opensearch.painless.node.EBrace;
import org.opensearch.painless.node.ECall;
import org.opensearch.painless.node.EComp;
import org.opensearch.painless.node.EDot;
import org.opensearch.painless.node.EListInit;
import org.opensearch.painless.node.ENumeric;
import org.opensearch.painless.node.EString;
import org.opensearch.painless.node.ESymbol;
import org.opensearch.painless.node.EUnary;
import org.opensearch.painless.node.SBlock;
import org.opensearch.painless.node.SClass;
import org.opensearch.painless.node.SExpression;
import org.opensearch.painless.node.SFunction;
import org.opensearch.painless.node.SReturn;
import org.opensearch.painless.symbol.ScriptScope;
import org.opensearch.script.ExtractedPredicate;

import java.util.ArrayList;
import java.util.List;

/**
 * Extract a structured predicate from a compiled Painless script when the script body matches a
 * narrow grammar. Populates {@link ScriptScope#setExtractedPredicate(ExtractedPredicate)}; the
 * query layer uses that to rewrite the script query to a native Lucene query.
 *
 * <p>This phase is purely read-only: on any shape it doesn't recognise it simply leaves the
 * extracted predicate {@code null} and the existing per-doc script evaluation continues to apply.
 *
 * <h2>Supported grammar (MVP)</h2>
 * <pre>
 *   return comp-chain;      // or implicit-return single expression
 *   comp-chain  := comp | comp '&amp;&amp;' comp-chain
 *   comp        := field-ref CMP numeric-literal
 *                | numeric-literal CMP field-ref
 *   field-ref   := doc [ 'name' ] . value
 *   CMP         := &lt; | &lt;= | &gt; | &gt;= | == | !=
 * </pre>
 *
 * All comparisons must reference the same field. Anything else &mdash; string/boolean/decimal
 * constants, non-constant indices, method calls like {@code doc.get('f')}, arithmetic on either
 * side, negation, OR &mdash; aborts extraction.
 *
 * <p>Missing-value safety: a bare {@code doc['x'].value} read is not rewrite-safe on its own.
 * Painless throws when that value is missing, while a native range query can only match or skip
 * the doc. Until this phase understands an explicit existence guard (for example a preceding
 * {@code doc['x'].size() != 0}), it must decline extraction for these shapes rather than silently
 * changing behavior.
 */
public final class PredicateExtractionPhase extends UserTreeBaseVisitor<ScriptScope> {

    @Override
    public void visitClass(SClass userClassNode, ScriptScope scriptScope) {
        SFunction executeFn = findExecuteFunction(userClassNode);
        if (executeFn == null) {
            return;
        }
        AExpression body = extractSingleExpression(executeFn);
        if (body == null) {
            return;
        }
        ExtractedPredicate predicate = extractPredicate(body);
        if (predicate != null) {
            scriptScope.setExtractedPredicate(predicate);
        }
    }

    private static SFunction findExecuteFunction(SClass userClassNode) {
        for (SFunction fn : userClassNode.getFunctionNodes()) {
            if ("execute".equals(fn.getFunctionName())) {
                return fn;
            }
        }
        return null;
    }

    /**
     * Return the single expression that makes up the execute body, whether explicit ({@code return
     * expr;}) or implicit ({@code expr}). If the function has more than one statement, returns
     * null.
     */
    private static AExpression extractSingleExpression(SFunction executeFn) {
        SBlock block = executeFn.getBlockNode();
        List<AStatement> stmts = block.getStatementNodes();
        if (stmts.size() != 1) {
            return null;
        }
        AStatement only = stmts.get(0);
        if (only instanceof SReturn) {
            return ((SReturn) only).getValueNode();
        }
        if (only instanceof SExpression) {
            return ((SExpression) only).getStatementNode();
        }
        return null;
    }

    private ExtractedPredicate extractPredicate(AExpression expr) {
        // Bare `doc['f'].value` reads have two failure modes:
        //   (1) missing docs: `.value` throws, but a native range query silently skips them.
        //   (2) multi-valued docs: `.value` returns only the first value (ScriptDocValues.Longs
        //       #getValue → get(0)), while a native range query matches if ANY value is in range.
        //       A doc like `[5, 20]` against `.value > 10` is `false` in Painless but would be a
        //       hit for the rewritten range — a silent false positive.
        // The only guard that rules out both is `size() == 1`. `size() != 0` / `> 0` / `>= 1` only
        // prove presence and are not sufficient — they're treated as comparisons, not guards.
        // Painless short-circuits `&&` left-to-right (IR BooleanNode.AND emits IFEQ before the
        // right operand), so a guard at chain position k dominates every conjunct at position > k.
        List<AExpression> conjuncts = new ArrayList<>();
        if (collectAndChain(expr, conjuncts) == false) {
            return null;
        }
        // Partition the chain into per-field blocks. Each block starts with a size()==1 guard
        // and contains the following conjuncts until the next guard. Multi-field guarded
        // predicates compose as an And of per-block extractions; a single block emits its
        // predicate directly (avoiding a degenerate And-of-one wrapper).
        List<ExtractedPredicate> blockPredicates = new ArrayList<>();
        int i = 0;
        while (i < conjuncts.size()) {
            String guardedField = extractSizeGuardField(conjuncts.get(i));
            if (guardedField == null) {
                // A non-guard conjunct at block boundary means the chain doesn't start with a
                // guard. Decline — we require each block's `.value` reads to be dominated by
                // its own guard.
                return null;
            }
            int start = i + 1;
            int end = start;
            while (end < conjuncts.size() && extractSizeGuardField(conjuncts.get(end)) == null) {
                end++;
            }
            if (end == start) {
                // Guard without any trailing comparisons isn't a useful predicate on its own.
                return null;
            }
            ExtractedPredicate block = extractGuardedBlock(guardedField, conjuncts.subList(start, end));
            if (block == null) {
                return null;
            }
            blockPredicates.add(block);
            i = end;
        }
        if (blockPredicates.isEmpty()) {
            return null;
        }
        if (blockPredicates.size() == 1) {
            return blockPredicates.get(0);
        }
        return new ExtractedPredicate.And(blockPredicates);
    }

    /**
     * Extract a single per-field block: one guard followed by one or more comparisons on that
     * field. The block shape mirrors the original single-block grammar:
     * <ul>
     *   <li>Exactly one comparison → {@link #tryExtractSingleConjunct} dispatches into
     *       {@link ExtractedPredicate.Term}, {@link ExtractedPredicate.Terms},
     *       {@link ExtractedPredicate.Or}, or a single-bound
     *       {@link ExtractedPredicate.Range}.</li>
     *   <li>Multiple comparisons → numeric {@link ExtractedPredicate.Range} via the bound-
     *       folding loop. All comparisons must be on the guarded field.</li>
     * </ul>
     */
    private static ExtractedPredicate extractGuardedBlock(String guardedField, List<AExpression> comparisons) {
        if (comparisons.size() == 1) {
            ExtractedPredicate single = tryExtractSingleConjunct(comparisons.get(0), guardedField);
            if (single != null) {
                return single;
            }
            // Fall through: a single numeric comparison can also be handled by the range-folding
            // loop below. The short-circuit above already covers it via
            // tryExtractSingleComparisonRange, so this is only reached if the single conjunct
            // wasn't recognisable as any shape — and the loop will reject it identically.
        }
        // Accumulated bounds. Each side holds either a Long literal, a ParamRef, or null
        // (unbounded). Bound folding at compile time can only tighten when both the existing and
        // incoming bound are Long literals — any Long-vs-ParamRef or ParamRef-vs-ParamRef mix
        // can't be reduced without knowing param values, so the phase declines rather than
        // carrying a compound-bound representation that isn't needed yet.
        Object lower = null;
        Object upper = null;
        boolean includeLower = true;
        boolean includeUpper = true;
        String comparisonField = null;
        boolean sawComparison = false;
        for (AExpression conjunct : comparisons) {
            if ((conjunct instanceof EComp) == false) {
                return null;
            }
            ComparisonShape shape = ComparisonShape.of((EComp) conjunct);
            if (shape == null) {
                return null;
            }
            if (comparisonField == null) {
                comparisonField = shape.field;
            } else if (comparisonField.equals(shape.field) == false) {
                return null;
            }
            Bound bound = applyBound(shape);
            if (bound == null) {
                return null;
            }
            sawComparison = true;
            if (bound.lower != null) {
                if (lower == null) {
                    lower = bound.lower;
                    includeLower = bound.includeLower;
                } else if (bound.lower instanceof Long && lower instanceof Long) {
                    long existing = (Long) lower;
                    long incoming = (Long) bound.lower;
                    if (incoming > existing) {
                        lower = bound.lower;
                        includeLower = bound.includeLower;
                    } else if (incoming == existing) {
                        includeLower = includeLower && bound.includeLower;
                    }
                } else {
                    // At least one side is a ParamRef — bounds can't be folded at compile time.
                    return null;
                }
            }
            if (bound.upper != null) {
                if (upper == null) {
                    upper = bound.upper;
                    includeUpper = bound.includeUpper;
                } else if (bound.upper instanceof Long && upper instanceof Long) {
                    long existing = (Long) upper;
                    long incoming = (Long) bound.upper;
                    if (incoming < existing) {
                        upper = bound.upper;
                        includeUpper = bound.includeUpper;
                    } else if (incoming == existing) {
                        includeUpper = includeUpper && bound.includeUpper;
                    }
                } else {
                    return null;
                }
            }
            if (bound.equalTo != null) {
                if (lower != null) {
                    if (lower instanceof Long == false) return null;
                    long l = (Long) lower;
                    if (l > bound.equalTo || (l == bound.equalTo && includeLower == false)) return null;
                }
                if (upper != null) {
                    if (upper instanceof Long == false) return null;
                    long u = (Long) upper;
                    if (u < bound.equalTo || (u == bound.equalTo && includeUpper == false)) return null;
                }
                lower = bound.equalTo;
                upper = bound.equalTo;
                includeLower = true;
                includeUpper = true;
            }
        }
        if (sawComparison == false) {
            return null;
        }
        if (guardedField.equals(comparisonField) == false) {
            // The guard must protect exactly the field the comparisons read; guarding a different
            // field doesn't prove the .value reads are safe.
            return null;
        }
        if (lower == null && upper == null) {
            return null;
        }
        return new ExtractedPredicate.Range(comparisonField, lower, upper, includeLower, includeUpper);
    }

    /**
     * Try to extract a single conjunct — everything to the right of the guard in a two-conjunct
     * chain, and also each arm inside an {@code ||}. Returns a predicate on success, {@code null}
     * if the conjunct doesn't match any recognised shape. Recognised shapes are:
     * <ul>
     *   <li>{@link EBooleanComp} with {@code OR} → {@link ExtractedPredicate.Or} of sub-shapes.</li>
     *   <li>{@link EComp} with {@code ==} on a string literal or {@code params.x} →
     *       {@link ExtractedPredicate.Term}.</li>
     *   <li>{@link EComp} on numeric bounds → single-bound {@link ExtractedPredicate.Range}.</li>
     *   <li>{@link ECall} {@code list.contains(field)} →
     *       {@link ExtractedPredicate.Terms}.</li>
     * </ul>
     * Each sub-helper enforces the guarded-field and field-type requirements; this method is a
     * pure dispatcher.
     */
    private static ExtractedPredicate tryExtractSingleConjunct(AExpression expr, String guardedField) {
        if (expr instanceof EBooleanComp) {
            return tryExtractOr((EBooleanComp) expr, guardedField);
        }
        if (expr instanceof EComp) {
            EComp comp = (EComp) expr;
            ExtractedPredicate.Term term = tryExtractTerm(comp, guardedField);
            if (term != null) {
                return term;
            }
            return tryExtractSingleComparisonRange(comp, guardedField);
        }
        if (expr instanceof ECall) {
            return tryExtractTerms((ECall) expr, guardedField);
        }
        return null;
    }

    /**
     * Extract a single numeric comparison ({@code field OP literal-or-param}) as a one-bound
     * {@link ExtractedPredicate.Range}. Mirrors the first iteration of the multi-conjunct
     * bound-folding loop in {@link #extractPredicate}, but for the one-bound case that's
     * reachable from {@link #tryExtractSingleConjunct}.
     */
    private static ExtractedPredicate.Range tryExtractSingleComparisonRange(EComp comp, String guardedField) {
        ComparisonShape shape = ComparisonShape.of(comp);
        if (shape == null) {
            return null;
        }
        if (guardedField.equals(shape.field) == false) {
            return null;
        }
        Bound bound = applyBound(shape);
        if (bound == null) {
            return null;
        }
        Object lower = bound.lower;
        Object upper = bound.upper;
        boolean includeLower = bound.includeLower;
        boolean includeUpper = bound.includeUpper;
        if (bound.equalTo != null) {
            lower = bound.equalTo;
            upper = bound.equalTo;
            includeLower = true;
            includeUpper = true;
        }
        if (lower == null && upper == null) {
            return null;
        }
        return new ExtractedPredicate.Range(shape.field, lower, upper, includeLower, includeUpper);
    }

    /**
     * Flatten an {@code ||} expression (Painless left-associative) into a list of arms, extract
     * each arm via {@link #tryExtractSingleConjunct}, and wrap the results in an
     * {@link ExtractedPredicate.Or}. Declines if any arm can't be extracted or references a
     * different field from the shared guard — a partial rewrite would match a strict subset of
     * what the script matches.
     *
     * <p>Soundness: the guarded `size() == 1` check runs before any {@code ||} arm, so every
     * arm sees only single-valued docs on the guarded field. Painless' short-circuit `||` and
     * Lucene's unconditional SHOULD-union produce the same match set because each arm is an
     * independently rewrite-safe shape under the guard.
     */
    private static ExtractedPredicate.Or tryExtractOr(EBooleanComp boolExpr, String guardedField) {
        if (boolExpr.getOperation() != Operation.OR) {
            return null;
        }
        List<AExpression> arms = new ArrayList<>();
        if (flattenOrChain(boolExpr, arms) == false) {
            return null;
        }
        List<ExtractedPredicate> clauses = new ArrayList<>(arms.size());
        for (AExpression arm : arms) {
            ExtractedPredicate clause = tryExtractSingleConjunct(arm, guardedField);
            if (clause == null) {
                return null;
            }
            // Disallow nested Or inside Or — Painless parses `a || b || c` as
            // `EBooleanComp(OR, EBooleanComp(OR, a, b), c)`, which `flattenOrChain` has already
            // expanded to a flat list. A non-left-associative `a || (b || c)` would round-trip
            // through the recursion already; still, a nested Or as a direct clause would
            // indicate a grammar we don't yet handle.
            if (clause instanceof ExtractedPredicate.Or) {
                return null;
            }
            clauses.add(clause);
        }
        return new ExtractedPredicate.Or(clauses);
    }

    /**
     * Flatten a left-associative {@code ||} chain into its leaf arms. Returns false if the
     * expression contains any non-OR boolean node that isn't a leaf — for example,
     * {@code a || b && c} should decline here because the inner {@code &&} operand would carry
     * its own conjunct structure that arm extraction isn't set up to analyse.
     */
    private static boolean flattenOrChain(AExpression expr, List<AExpression> into) {
        if (expr instanceof EBooleanComp) {
            EBooleanComp bc = (EBooleanComp) expr;
            if (bc.getOperation() != Operation.OR) {
                // A nested `&&` under Or would need its own guard to be rewrite-safe. Decline.
                return false;
            }
            return flattenOrChain(bc.getLeftNode(), into) && flattenOrChain(bc.getRightNode(), into);
        }
        into.add(expr);
        return true;
    }

    /**
     * Match a guarded single string-equality. The non-field side may be either a string literal
     * ({@code doc['f'].value == 'active'}) or a {@code params.<name>} reference
     * ({@code doc['f'].value == params.status}); both emit an {@link ExtractedPredicate.Term}
     * whose value is either the literal String or a {@link ExtractedPredicate.ParamRef}. Only
     * {@code ==} is accepted here; {@code !=} would need an OR-of-everything-else that Lucene
     * doesn't express natively on a term.
     */
    private static ExtractedPredicate.Term tryExtractTerm(EComp comp, String guardedField) {
        if (comp.getOperation() != Operation.EQ) {
            return null;
        }
        String leftField = extractDocField(comp.getLeftNode());
        Object rightValue = extractStringOrParamRef(comp.getRightNode());
        String field;
        Object value;
        if (leftField != null && rightValue != null) {
            field = leftField;
            value = rightValue;
        } else {
            String rightField = extractDocField(comp.getRightNode());
            Object leftValue = extractStringOrParamRef(comp.getLeftNode());
            if (rightField == null || leftValue == null) {
                return null;
            }
            field = rightField;
            value = leftValue;
        }
        if (guardedField.equals(field) == false) {
            return null;
        }
        return new ExtractedPredicate.Term(field, value);
    }

    /**
     * Match a string literal or a {@code params.<name>} reference on the non-field side of a
     * term-equality comparison. Returns a {@link String} or an
     * {@link ExtractedPredicate.ParamRef}; returns {@code null} when neither shape matches.
     */
    private static Object extractStringOrParamRef(AExpression expr) {
        if (expr instanceof EString) {
            return ((EString) expr).getString();
        }
        return extractParamRef(expr);
    }

    /**
     * Match a guarded set-membership call: {@code ['literal', 'literal', ...].contains(
     * doc['f'].value)}. Returns an {@link ExtractedPredicate.Terms} when the list is a
     * homogeneous sequence of {@link EString} literals (possibly empty) and the argument is a
     * field reference on the guarded field. Declines anything else — variables inside the list,
     * mixed-type elements, non-contains methods, missing list prefix, or a different field in
     * the argument all fall through and let the script run.
     *
     * <p>Empty lists emit {@code Terms(field, [])}, which the carrier hands to
     * {@code MappedFieldType.termsQuery(emptyList)} — that produces a boolean query with zero
     * SHOULD clauses, i.e. match-nothing. Semantically identical to Painless'
     * {@code [].contains(x)} which is always {@code false}.
     */
    private static ExtractedPredicate.Terms tryExtractTerms(ECall call, String guardedField) {
        if ("contains".equals(call.getMethodName()) == false) return null;
        if (call.getArgumentNodes().size() != 1) return null;
        if ((call.getPrefixNode() instanceof EListInit) == false) return null;
        EListInit listInit = (EListInit) call.getPrefixNode();
        String argField = extractDocField(call.getArgumentNodes().get(0));
        if (argField == null || argField.equals(guardedField) == false) {
            return null;
        }
        List<Object> values = new ArrayList<>(listInit.getValueNodes().size());
        for (AExpression element : listInit.getValueNodes()) {
            if ((element instanceof EString) == false) {
                // Non-literal (ESymbol, EDot, ParamRef) or non-String (ENumeric, EBoolean) would
                // change semantics: Painless walks the list with `.equals()` against the raw
                // element object, whereas `termsQuery` does a bit-exact lookup of the indexed
                // keyword. A numeric element like `1` would be stringified ("1") before lookup
                // and match a doc whose keyword is literally "1" — strictly more permissive
                // than the script. Decline.
                return null;
            }
            values.add(((EString) element).getString());
        }
        return new ExtractedPredicate.Terms(argField, values);
    }

    /**
     * Flatten a tree of {@code &&} nodes. Each leaf is returned as-is so callers can distinguish
     * comparisons from other conjunct shapes such as size-guards.
     */
    private boolean collectAndChain(AExpression expr, List<AExpression> into) {
        if (expr instanceof EBooleanComp) {
            EBooleanComp bc = (EBooleanComp) expr;
            if (bc.getOperation() == Operation.AND) {
                return collectAndChain(bc.getLeftNode(), into) && collectAndChain(bc.getRightNode(), into);
            }
            // OR nodes are treated as leaf conjuncts here — `tryExtractSingleConjunct` dispatches
            // them into `tryExtractOr`. Falling through to `into.add(expr)` would previously have
            // been prevented by the old decline-on-non-AND check; the current single-conjunct
            // slot needs Or as a leaf so the walker sees it unchanged.
        }
        into.add(expr);
        return true;
    }

    /**
     * Match a size-one guard and return the protected field name, else null. Accepted shapes:
     * {@code doc['f'].size() == 1} and {@code 1 == doc['f'].size()}. Anything else (including
     * {@code != 0} / {@code > 0} / {@code >= 1}) only proves presence, not single-valuedness, and
     * is insufficient — {@code .value} reads only index 0, but a native range query matches if
     * any indexed value falls in range.
     */
    private static String extractSizeGuardField(AExpression expr) {
        if ((expr instanceof EComp) == false) return null;
        EComp comp = (EComp) expr;
        if (comp.getOperation() != Operation.EQ) return null;
        String field = extractDocSizeField(comp.getLeftNode());
        Long literal = extractLongLiteral(comp.getRightNode());
        if (field == null || literal == null) {
            field = extractDocSizeField(comp.getRightNode());
            literal = extractLongLiteral(comp.getLeftNode());
            if (field == null || literal == null) {
                return null;
            }
        }
        return literal == 1L ? field : null;
    }

    /**
     * Match {@code doc['name'].size()} or {@code doc.get('name').size()} and return {@code name},
     * else null. Both spellings resolve to the same runtime lookup.
     */
    private static String extractDocSizeField(AExpression expr) {
        if ((expr instanceof ECall) == false) return null;
        ECall call = (ECall) expr;
        if ("size".equals(call.getMethodName()) == false) return null;
        if (call.getArgumentNodes().isEmpty() == false) return null;
        return extractFieldKey(call.getPrefixNode());
    }

    /**
     * Canonicalize either spelling of a field lookup prefix — {@code doc['name']} or
     * {@code doc.get('name')} — and return the field name. Returns null if the expression is
     * neither shape or uses a non-constant key (dynamic field name).
     */
    private static String extractFieldKey(AExpression expr) {
        if (expr instanceof EBrace) {
            EBrace brace = (EBrace) expr;
            AExpression bracePrefix = brace.getPrefixNode();
            AExpression braceIndex = brace.getIndexNode();
            if ((bracePrefix instanceof ESymbol) == false) return null;
            if ("doc".equals(((ESymbol) bracePrefix).getSymbol()) == false) return null;
            if ((braceIndex instanceof EString) == false) return null;
            return ((EString) braceIndex).getString();
        }
        if (expr instanceof ECall) {
            ECall call = (ECall) expr;
            if ("get".equals(call.getMethodName()) == false) return null;
            if (call.getArgumentNodes().size() != 1) return null;
            AExpression callPrefix = call.getPrefixNode();
            if ((callPrefix instanceof ESymbol) == false) return null;
            if ("doc".equals(((ESymbol) callPrefix).getSymbol()) == false) return null;
            AExpression arg = call.getArgumentNodes().get(0);
            if ((arg instanceof EString) == false) return null;
            return ((EString) arg).getString();
        }
        return null;
    }

    /**
     * Recognised shape of a comparison: either {@code fieldRef CMP operand} or {@code operand CMP
     * fieldRef}, together with the canonical operator oriented field-first. The operand is either
     * a {@link Long} literal or an {@link ExtractedPredicate.ParamRef} captured from a
     * {@code params.<name>} reference.
     */
    private static final class ComparisonShape {
        final String field;
        final Object operand; // Long or ExtractedPredicate.ParamRef
        final Operation op; // always expressed as `field OP operand`

        ComparisonShape(String field, Object operand, Operation op) {
            this.field = field;
            this.operand = operand;
            this.op = op;
        }

        static ComparisonShape of(EComp comp) {
            Operation op = comp.getOperation();
            if (op != Operation.LT && op != Operation.LTE && op != Operation.GT && op != Operation.GTE && op != Operation.EQ
                && op != Operation.NE) {
                return null;
            }
            String leftField = extractDocField(comp.getLeftNode());
            Object rightOp = extractNumericOperand(comp.getRightNode());
            if (leftField != null && rightOp != null) {
                return new ComparisonShape(leftField, rightOp, op);
            }
            String rightField = extractDocField(comp.getRightNode());
            Object leftOp = extractNumericOperand(comp.getLeftNode());
            if (rightField != null && leftOp != null) {
                return new ComparisonShape(rightField, leftOp, flip(op));
            }
            return null;
        }

        private static Operation flip(Operation op) {
            switch (op) {
                case LT:
                    return Operation.GT;
                case LTE:
                    return Operation.GTE;
                case GT:
                    return Operation.LT;
                case GTE:
                    return Operation.LTE;
                case EQ:
                case NE:
                    return op;
                default:
                    throw new AssertionError(op);
            }
        }
    }

    /** Intermediate bound produced by a single comparison. Bounds are either {@link Long} or
     *  {@link ExtractedPredicate.ParamRef}; equalTo (from {@code ==}) must be a Long because an
     *  equality-against-a-param would need a Term carrier and is out of scope for Range here. */
    private static final class Bound {
        Object lower;
        Object upper;
        Long equalTo;
        boolean includeLower;
        boolean includeUpper;
    }

    private static Bound applyBound(ComparisonShape shape) {
        if (shape == null) {
            return null;
        }
        Bound b = new Bound();
        switch (shape.op) {
            case GT:
                b.lower = shape.operand;
                b.includeLower = false;
                break;
            case GTE:
                b.lower = shape.operand;
                b.includeLower = true;
                break;
            case LT:
                b.upper = shape.operand;
                b.includeUpper = false;
                break;
            case LTE:
                b.upper = shape.operand;
                b.includeUpper = true;
                break;
            case EQ:
                // Range.equalTo must be a compile-time literal; `doc['f'].value == params.x` would
                // need a Term carrier with a ParamRef value (deferred).
                if (shape.operand instanceof Long == false) {
                    return null;
                }
                b.equalTo = (Long) shape.operand;
                break;
            case NE:
                // Representable as OR(range(<lit), range(>lit)); out of scope for the MVP.
                return null;
            default:
                return null;
        }
        return b;
    }

    /**
     * Match any equivalent spelling of a single-value field read and return the field name, else
     * null. Painless accepts both bean-access and getter forms on each of the two
     * {@link org.opensearch.search.lookup.LeafDocLookup} lookups:
     * <ul>
     *   <li>{@code doc['f'].value}</li>
     *   <li>{@code doc['f'].getValue()}</li>
     *   <li>{@code doc.get('f').value}</li>
     *   <li>{@code doc.get('f').getValue()}</li>
     * </ul>
     * All four compile to the same runtime call chain
     * ({@code LeafDocLookup.get(name).getValue()}), so they have identical soundness properties —
     * missing docs throw, multi-valued docs return index 0.
     */
    private static String extractDocField(AExpression expr) {
        if (expr instanceof EDot) {
            EDot dot = (EDot) expr;
            if (!"value".equals(dot.getIndex())) return null;
            return extractFieldKey(dot.getPrefixNode());
        }
        if (expr instanceof ECall) {
            ECall call = (ECall) expr;
            if ("getValue".equals(call.getMethodName()) == false) return null;
            if (call.getArgumentNodes().isEmpty() == false) return null;
            return extractFieldKey(call.getPrefixNode());
        }
        return null;
    }

    /**
     * Match either a long literal or a {@code params.<name>} reference on the non-field side of a
     * numeric comparison. Returns a {@link Long} or an {@link ExtractedPredicate.ParamRef};
     * {@code null} means the operand is neither and extraction must decline.
     */
    private static Object extractNumericOperand(AExpression expr) {
        ExtractedPredicate.ParamRef paramRef = extractParamRef(expr);
        if (paramRef != null) {
            return paramRef;
        }
        return extractLongLiteral(expr);
    }

    /**
     * Match {@code params.<name>} and return a ParamRef for it. Declines nested access
     * ({@code params.x.y}), dynamic lookups ({@code params[key]}), and anything that isn't an
     * {@link EDot} with {@code ESymbol("params")} as its prefix.
     */
    private static ExtractedPredicate.ParamRef extractParamRef(AExpression expr) {
        if ((expr instanceof EDot) == false) return null;
        EDot dot = (EDot) expr;
        AExpression prefix = dot.getPrefixNode();
        if ((prefix instanceof ESymbol) == false) return null;
        if ("params".equals(((ESymbol) prefix).getSymbol()) == false) return null;
        return new ExtractedPredicate.ParamRef(dot.getIndex());
    }

    /** Match an integer / long literal and return its Long value. Declines decimals and hex. */
    private static Long extractLongLiteral(AExpression expr) {
        // Painless parses `-5` as EUnary(SUB, ENumeric("5")); handle that shape transparently.
        if (expr instanceof EUnary) {
            EUnary unary = (EUnary) expr;
            if (unary.getOperation() != Operation.SUB) return null;
            Long inner = extractLongLiteral(unary.getChildNode());
            if (inner == null) return null;
            return -inner;
        }
        if ((expr instanceof ENumeric) == false) return null;
        ENumeric n = (ENumeric) expr;
        String text = n.getNumeric();
        int radix = n.getRadix();
        // Strip type suffix if present (L/l for long, else integer).
        String raw = text;
        char last = raw.charAt(raw.length() - 1);
        if (last == 'L' || last == 'l') {
            raw = raw.substring(0, raw.length() - 1);
        } else if (last == 'f' || last == 'F' || last == 'd' || last == 'D') {
            // Decimal suffixes — not a long literal.
            return null;
        }
        if (raw.indexOf('.') >= 0) {
            return null;
        }
        try {
            return Long.parseLong(raw, radix);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
