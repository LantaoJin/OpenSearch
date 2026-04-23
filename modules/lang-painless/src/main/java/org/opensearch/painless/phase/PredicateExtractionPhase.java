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
        String guardedField = null;
        int guardIndex = -1;
        for (int i = 0; i < conjuncts.size(); i++) {
            String field = extractSizeGuardField(conjuncts.get(i));
            if (field == null) {
                continue;
            }
            if (guardedField != null) {
                // Multiple guards complicate dominance reasoning; conservative decline.
                return null;
            }
            guardedField = field;
            guardIndex = i;
        }
        if (guardedField == null) {
            return null;
        }

        // Single-comparison string-equality: shape is `guard && doc['f'].value == 'literal'`.
        // Emit a Term carrier. Numeric comparisons fall through to the range-folding loop below.
        if (guardIndex == 0 && conjuncts.size() == 2 && conjuncts.get(1) instanceof EComp) {
            ExtractedPredicate.Term term = tryExtractTerm((EComp) conjuncts.get(1), guardedField);
            if (term != null) {
                return term;
            }
        }

        Long lower = null;
        Long upper = null;
        boolean includeLower = true;
        boolean includeUpper = true;
        String comparisonField = null;
        boolean sawComparison = false;
        for (int i = 0; i < conjuncts.size(); i++) {
            if (i == guardIndex) {
                continue;
            }
            if (i < guardIndex) {
                // Painless `&&` is left-associative and short-circuits left-to-right, so the
                // guard only dominates conjuncts to its right. A comparison at a lower index
                // would be evaluated before the guard — its `.value` read would throw on a
                // missing doc before short-circuit protection kicked in.
                return null;
            }
            AExpression conjunct = conjuncts.get(i);
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
                if (lower == null || bound.lower > lower) {
                    lower = bound.lower;
                    includeLower = bound.includeLower;
                } else if (bound.lower.equals(lower)) {
                    includeLower = includeLower && bound.includeLower;
                }
            }
            if (bound.upper != null) {
                if (upper == null || bound.upper < upper) {
                    upper = bound.upper;
                    includeUpper = bound.includeUpper;
                } else if (bound.upper.equals(upper)) {
                    includeUpper = includeUpper && bound.includeUpper;
                }
            }
            if (bound.equalTo != null) {
                if (lower != null && (lower > bound.equalTo || (lower == bound.equalTo && includeLower == false))) return null;
                if (upper != null && (upper < bound.equalTo || (upper == bound.equalTo && includeUpper == false))) return null;
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
     * Match a guarded single string-equality: {@code doc['f'].value == 'literal'} or the literal-
     * on-left form. Returns a {@link ExtractedPredicate.Term} only if the comparison's field
     * matches {@code guardedField} — otherwise the guard doesn't cover the read and we decline.
     * Only {@code ==} is accepted here; {@code !=} would need an OR-of-everything-else that
     * Lucene doesn't express natively on a term.
     */
    private static ExtractedPredicate.Term tryExtractTerm(EComp comp, String guardedField) {
        if (comp.getOperation() != Operation.EQ) {
            return null;
        }
        String leftField = extractDocField(comp.getLeftNode());
        String rightLit = extractStringLiteral(comp.getRightNode());
        String field;
        String literal;
        if (leftField != null && rightLit != null) {
            field = leftField;
            literal = rightLit;
        } else {
            String rightField = extractDocField(comp.getRightNode());
            String leftLit = extractStringLiteral(comp.getLeftNode());
            if (rightField == null || leftLit == null) {
                return null;
            }
            field = rightField;
            literal = leftLit;
        }
        if (guardedField.equals(field) == false) {
            return null;
        }
        return new ExtractedPredicate.Term(field, literal);
    }

    /** Match a bare string literal. Declines everything else, including {@code null}. */
    private static String extractStringLiteral(AExpression expr) {
        if ((expr instanceof EString) == false) return null;
        return ((EString) expr).getString();
    }

    /**
     * Flatten a tree of {@code &&} nodes. Each leaf is returned as-is so callers can distinguish
     * comparisons from other conjunct shapes such as size-guards.
     */
    private boolean collectAndChain(AExpression expr, List<AExpression> into) {
        if (expr instanceof EBooleanComp) {
            EBooleanComp bc = (EBooleanComp) expr;
            if (bc.getOperation() != Operation.AND) {
                return false;
            }
            return collectAndChain(bc.getLeftNode(), into) && collectAndChain(bc.getRightNode(), into);
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

    /** Match {@code doc['name'].size()} and return {@code name}, else null. */
    private static String extractDocSizeField(AExpression expr) {
        if ((expr instanceof ECall) == false) return null;
        ECall call = (ECall) expr;
        if ("size".equals(call.getMethodName()) == false) return null;
        if (call.getArgumentNodes().isEmpty() == false) return null;
        AExpression prefix = call.getPrefixNode();
        if ((prefix instanceof EBrace) == false) return null;
        EBrace brace = (EBrace) prefix;
        AExpression bracePrefix = brace.getPrefixNode();
        AExpression braceIndex = brace.getIndexNode();
        if ((bracePrefix instanceof ESymbol) == false) return null;
        if ("doc".equals(((ESymbol) bracePrefix).getSymbol()) == false) return null;
        if ((braceIndex instanceof EString) == false) return null;
        return ((EString) braceIndex).getString();
    }

    /**
     * Recognised shape of a comparison: either {@code fieldRef CMP literal} or {@code literal CMP
     * fieldRef}, together with the canonical operator oriented field-first.
     */
    private static final class ComparisonShape {
        final String field;
        final long literal;
        final Operation op; // always expressed as `field OP literal`

        ComparisonShape(String field, long literal, Operation op) {
            this.field = field;
            this.literal = literal;
            this.op = op;
        }

        static ComparisonShape of(EComp comp) {
            Operation op = comp.getOperation();
            if (op != Operation.LT && op != Operation.LTE && op != Operation.GT && op != Operation.GTE && op != Operation.EQ
                && op != Operation.NE) {
                return null;
            }
            String leftField = extractDocField(comp.getLeftNode());
            Long rightLit = extractLongLiteral(comp.getRightNode());
            if (leftField != null && rightLit != null) {
                return new ComparisonShape(leftField, rightLit, op);
            }
            String rightField = extractDocField(comp.getRightNode());
            Long leftLit = extractLongLiteral(comp.getLeftNode());
            if (rightField != null && leftLit != null) {
                return new ComparisonShape(rightField, leftLit, flip(op));
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

    /** Intermediate bound produced by a single comparison. */
    private static final class Bound {
        Long lower;
        Long upper;
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
                b.lower = shape.literal;
                b.includeLower = false;
                break;
            case GTE:
                b.lower = shape.literal;
                b.includeLower = true;
                break;
            case LT:
                b.upper = shape.literal;
                b.includeUpper = false;
                break;
            case LTE:
                b.upper = shape.literal;
                b.includeUpper = true;
                break;
            case EQ:
                b.equalTo = shape.literal;
                break;
            case NE:
                // Representable as OR(range(<lit), range(>lit)); out of scope for the MVP.
                return null;
            default:
                return null;
        }
        return b;
    }

    /** Match {@code doc['name'].value} and return {@code name}, else null. */
    private static String extractDocField(AExpression expr) {
        if ((expr instanceof EDot) == false) return null;
        EDot dot = (EDot) expr;
        if (!"value".equals(dot.getIndex())) return null;
        AExpression prefix = dot.getPrefixNode();
        if ((prefix instanceof EBrace) == false) return null;
        EBrace brace = (EBrace) prefix;
        AExpression bracePrefix = brace.getPrefixNode();
        AExpression braceIndex = brace.getIndexNode();
        if ((bracePrefix instanceof ESymbol) == false) return null;
        if ("doc".equals(((ESymbol) bracePrefix).getSymbol()) == false) return null;
        if ((braceIndex instanceof EString) == false) return null;
        return ((EString) braceIndex).getString();
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
