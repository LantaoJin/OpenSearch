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
        for (int i = 0; i < conjuncts.size(); i++) {
            if (i == guardIndex) {
                continue;
            }
            if (i < guardIndex) {
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
