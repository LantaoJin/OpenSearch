/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.painless;

import org.opensearch.common.settings.Settings;
import org.opensearch.painless.spi.Allowlist;
import org.opensearch.script.ExtractedPredicate;
import org.opensearch.script.FilterScript;
import org.opensearch.script.ScriptContext;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link org.opensearch.painless.phase.PredicateExtractionPhase}. Compiles real {@link
 * FilterScript} scripts and asserts the shape of the resulting extracted predicate.
 */
public class PredicateExtractionPhaseTests extends ScriptTestCase {
    private static PainlessScriptEngine SCRIPT_ENGINE;

    @BeforeClass
    public static void beforeClass() {
        Map<ScriptContext<?>, List<Allowlist>> contexts = newDefaultContexts();
        contexts.put(FilterScript.CONTEXT, Allowlist.BASE_ALLOWLISTS);
        SCRIPT_ENGINE = new PainlessScriptEngine(Settings.EMPTY, contexts);
    }

    @AfterClass
    public static void afterClass() {
        SCRIPT_ENGINE = null;
    }

    @Override
    protected PainlessScriptEngine getEngine() {
        return SCRIPT_ENGINE;
    }

    private ExtractedPredicate extract(String source) {
        FilterScript.Factory factory = getEngine().compile(
            "test",
            source,
            FilterScript.CONTEXT,
            Collections.emptyMap()
        );
        return factory.extractedPredicate();
    }

    // --- unguarded scripts must decline extraction --------------------------
    //
    // Unguarded doc['field'].value reads are not rewrite-safe: on missing docs the script throws,
    // while a native range query would silently skip the document. Only scripts whose `&&` chain
    // contains a size() guard on the same field extract successfully.

    public void testDeclineOnUnguardedGreaterThan() {
        assertNull(extract("doc['price'].value > 10"));
    }

    public void testDeclineOnUnguardedBoundedRange() {
        assertNull(extract("doc['price'].value > 10 && doc['price'].value < 100"));
    }

    public void testDeclineOnUnguardedLessThanThatWouldAlsoIncludeZero() {
        assertNull(extract("doc['price'].value < 100"));
    }

    // --- guarded scripts extract a Range ------------------------------------
    //
    // Only `size() == 1` is accepted as a guard. Presence-only guards (`!= 0`, `> 0`, `>= 1`) are
    // unsound on multi-valued fields: Painless' `.value` reads only the first value, but a native
    // range query matches if ANY indexed value falls in range, so they would produce false
    // positives on docs like [5, 20].

    public void testGuardedGreaterThan() {
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract("doc['price'].size() == 1 && doc['price'].value > 10");
        assertNotNull(r);
        assertEquals("price", r.field());
        assertEquals(Long.valueOf(10L), r.lower());
        assertNull(r.upper());
        assertFalse(r.includeLower());
    }

    public void testGuardedGreaterOrEqual() {
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract("doc['price'].size() == 1 && doc['price'].value >= 10");
        assertNotNull(r);
        assertEquals(Long.valueOf(10L), r.lower());
        assertTrue(r.includeLower());
    }

    public void testGuardedBoundedRange() {
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract(
            "doc['price'].size() == 1 && doc['price'].value > 10 && doc['price'].value < 100"
        );
        assertNotNull(r);
        assertEquals(Long.valueOf(10L), r.lower());
        assertEquals(Long.valueOf(100L), r.upper());
        assertFalse(r.includeLower());
        assertFalse(r.includeUpper());
    }

    public void testGuardedLessThanNegative() {
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract("doc['price'].size() == 1 && doc['price'].value < -5");
        assertNotNull(r);
        assertNull(r.lower());
        assertEquals(Long.valueOf(-5L), r.upper());
        assertFalse(r.includeUpper());
    }

    public void testGuardedLiteralOnLeft() {
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract("doc['price'].size() == 1 && 10 < doc['price'].value");
        assertNotNull(r);
        assertEquals(Long.valueOf(10L), r.lower());
        assertFalse(r.includeLower());
    }

    public void testGuardedLongSuffixLiteral() {
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract("doc['price'].size() == 1 && doc['price'].value > 10L");
        assertNotNull(r);
        assertEquals(Long.valueOf(10L), r.lower());
    }

    public void testGuardedRangeThatCrossesZero() {
        // The guard makes this safe even when the range contains 0L — missing docs don't reach
        // the comparisons at runtime, and the native range query also skips them.
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract(
            "doc['price'].size() == 1 && doc['price'].value > -5 && doc['price'].value < 5"
        );
        assertNotNull(r);
        assertEquals(Long.valueOf(-5L), r.lower());
        assertEquals(Long.valueOf(5L), r.upper());
    }

    public void testGuardedWithLiteralOnLeftInGuard() {
        // `1 == doc['f'].size()` — literal on the left of the guard.
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract("1 == doc['price'].size() && doc['price'].value > 10");
        assertNotNull(r);
        assertEquals(Long.valueOf(10L), r.lower());
    }

    // --- presence-only guards must decline (multi-value unsoundness) --------
    //
    // All three of these were accepted as guards in an earlier revision. They were tightened to
    // `== 1` after review noted that a doc with multi-valued [5, 20] and script `.value > 10`
    // returns false in Painless (only reads get(0)) but matches a native `rangeQuery(10, +∞)`.

    public void testDeclineOnSizeNotEqualZeroGuard() {
        assertNull(extract("doc['price'].size() != 0 && doc['price'].value > 10"));
    }

    public void testDeclineOnSizeGreaterZeroGuard() {
        assertNull(extract("doc['price'].size() > 0 && doc['price'].value > 10"));
    }

    public void testDeclineOnSizeGreaterOrEqualOneGuard() {
        assertNull(extract("doc['price'].size() >= 1 && doc['price'].value > 10"));
    }

    // --- guard-aware declines -----------------------------------------------

    public void testDeclineWhenGuardIsOnDifferentField() {
        // Guarding one field doesn't prove the .value reads on another field are safe.
        assertNull(extract("doc['age'].size() == 1 && doc['price'].value > 10"));
    }

    public void testDeclineWhenGuardIsInsideOr() {
        // `||` breaks dominance — the guard no longer precedes every .value read.
        assertNull(extract("doc['price'].size() != 1 || doc['price'].value > 10"));
    }

    public void testDeclineWhenGuardFollowsComparison() {
        // `&&` evaluates left-to-right, so a guard after the `.value` read doesn't short-circuit
        // in time — the read has already happened and thrown on missing docs.
        assertNull(extract("doc['price'].value > 10 && doc['price'].size() == 1"));
    }

    public void testDeclineWhenGuardUsesWrongLiteral() {
        // `size() == 2` is not a single-valuedness guard.
        assertNull(extract("doc['price'].size() == 2 && doc['price'].value > 10"));
    }

    public void testDeclineWhenMultipleGuardsOnSameField() {
        assertNull(extract("doc['price'].size() == 1 && doc['price'].size() == 1 && doc['price'].value > 10"));
    }

    public void testDeclineGuardWithoutComparison() {
        assertNull(extract("doc['price'].size() == 1"));
    }

    // --- guarded string equality extracts a Term ---------------------------

    public void testGuardedStringEquality() {
        ExtractedPredicate.Term t = (ExtractedPredicate.Term) extract("doc['status'].size() == 1 && doc['status'].value == 'active'");
        assertNotNull(t);
        assertEquals("status", t.field());
        assertEquals("active", t.value());
    }

    public void testGuardedStringEqualityLiteralOnLeft() {
        ExtractedPredicate.Term t = (ExtractedPredicate.Term) extract("doc['status'].size() == 1 && 'active' == doc['status'].value");
        assertNotNull(t);
        assertEquals("active", t.value());
    }

    public void testDeclineStringInequality() {
        // `!=` on a Term has no clean single-query Lucene equivalent.
        assertNull(extract("doc['status'].size() == 1 && doc['status'].value != 'active'"));
    }

    public void testDeclineStringEqualityWithoutGuard() {
        assertNull(extract("doc['status'].value == 'active'"));
    }

    public void testDeclineStringEqualityWithPresenceOnlyGuard() {
        // size() != 0 doesn't prove single-valuedness; multi-valued docs like ["active","old"]
        // would false-positive since Painless reads get(0) but a TermQuery matches any-of.
        assertNull(extract("doc['status'].size() != 0 && doc['status'].value == 'active'"));
    }

    public void testDeclineStringEqualityWhenGuardIsOnDifferentField() {
        assertNull(extract("doc['region'].size() == 1 && doc['status'].value == 'active'"));
    }

    public void testDeclineMixedStringAndNumericConjuncts() {
        // This PR only handles single-comparison Term extraction. Chains mixing a numeric range
        // with a string equality would need an `And` carrier — declined for now.
        assertNull(extract("doc['status'].size() == 1 && doc['status'].value == 'active' && doc['status'].value == 'x'"));
    }

    // --- guarded numeric range with params.x on a bound --------------------

    public void testGuardedGreaterThanParam() {
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract(
            "doc['price'].size() == 1 && doc['price'].value > params.threshold"
        );
        assertNotNull(r);
        assertEquals("price", r.field());
        assertEquals(new ExtractedPredicate.ParamRef("threshold"), r.lower());
        assertNull(r.upper());
        assertFalse(r.includeLower());
    }

    public void testGuardedBoundedRangeBothParams() {
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract(
            "doc['price'].size() == 1 && doc['price'].value > params.low && doc['price'].value < params.high"
        );
        assertNotNull(r);
        assertEquals(new ExtractedPredicate.ParamRef("low"), r.lower());
        assertEquals(new ExtractedPredicate.ParamRef("high"), r.upper());
        assertFalse(r.includeLower());
        assertFalse(r.includeUpper());
    }

    public void testGuardedBoundedRangeMixedLiteralAndParam() {
        // Literal on one side, param on the other — different sides, so folding is unambiguous.
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract(
            "doc['price'].size() == 1 && doc['price'].value > 10 && doc['price'].value < params.max"
        );
        assertNotNull(r);
        assertEquals(Long.valueOf(10L), r.lower());
        assertEquals(new ExtractedPredicate.ParamRef("max"), r.upper());
    }

    public void testGuardedParamLiteralOnLeft() {
        // `params.threshold < doc['price'].value` — param on the left of the comparison, which
        // the existing flip logic orients field-first.
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract(
            "doc['price'].size() == 1 && params.threshold < doc['price'].value"
        );
        assertNotNull(r);
        assertEquals(new ExtractedPredicate.ParamRef("threshold"), r.lower());
        assertFalse(r.includeLower());
    }

    public void testDeclineParamOnBothSidesOfOneComparison() {
        // `params.a > params.b` has no field reference; not a valid comparison shape.
        assertNull(extract("doc['price'].size() == 1 && params.a < params.b"));
    }

    public void testDeclineWhenLiteralAndParamShareSameBoundSide() {
        // `> 10 && > params.low` — can't fold at compile time without knowing params.low. Phase
        // declines rather than guessing.
        assertNull(extract("doc['price'].size() == 1 && doc['price'].value > 10 && doc['price'].value > params.low"));
    }

    public void testDeclineWhenTwoParamsShareSameBoundSide() {
        assertNull(extract("doc['price'].size() == 1 && doc['price'].value > params.a && doc['price'].value > params.b"));
    }

    public void testGuardedStringEqualityAgainstParam() {
        ExtractedPredicate.Term t = (ExtractedPredicate.Term) extract(
            "doc['status'].size() == 1 && doc['status'].value == params.expected"
        );
        assertNotNull(t);
        assertEquals("status", t.field());
        assertEquals(new ExtractedPredicate.ParamRef("expected"), t.value());
    }

    public void testGuardedStringEqualityParamLiteralOnLeft() {
        ExtractedPredicate.Term t = (ExtractedPredicate.Term) extract(
            "doc['status'].size() == 1 && params.expected == doc['status'].value"
        );
        assertNotNull(t);
        assertEquals(new ExtractedPredicate.ParamRef("expected"), t.value());
    }

    public void testDeclineUnguardedParamComparison() {
        assertNull(extract("doc['price'].value > params.threshold"));
    }

    // --- alternate .value spellings under the guard ------------------------
    //
    // `doc['f'].value`, `doc['f'].getValue()`, `doc.get('f').value` and
    // `doc.get('f').getValue()` all compile to the same runtime call chain. Once the guard
    // holds, they're interchangeable.

    public void testGuardedGetValueMethodSpelling() {
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract(
            "doc['price'].size() == 1 && doc['price'].getValue() > 10"
        );
        assertNotNull(r);
        assertEquals(Long.valueOf(10L), r.lower());
    }

    public void testGuardedDocGetSpelling() {
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract(
            "doc['price'].size() == 1 && doc.get('price').value > 10"
        );
        assertNotNull(r);
        assertEquals(Long.valueOf(10L), r.lower());
    }

    public void testGuardedDocGetWithGetValue() {
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract(
            "doc['price'].size() == 1 && doc.get('price').getValue() > 10"
        );
        assertNotNull(r);
        assertEquals(Long.valueOf(10L), r.lower());
    }

    public void testGuardedDocGetInGuard() {
        // Guard itself uses doc.get('f').size(); the read uses doc['f'].value.
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract(
            "doc.get('price').size() == 1 && doc['price'].value > 10"
        );
        assertNotNull(r);
        assertEquals(Long.valueOf(10L), r.lower());
    }

    public void testGuardedMixedSpellings() {
        // Guard uses doc.get('f').size(); read uses doc.get('f').getValue(). Different spellings
        // of the same field resolve to the same key "price" and the same-field check holds.
        ExtractedPredicate.Range r = (ExtractedPredicate.Range) extract(
            "doc.get('price').size() == 1 && doc.get('price').getValue() > 10"
        );
        assertNotNull(r);
        assertEquals(Long.valueOf(10L), r.lower());
    }

    public void testDeclineDocGetWithDynamicKey() {
        // `doc.get(var)` still declines because the argument isn't a compile-time string literal.
        assertNull(extract("String f = 'price'; return doc.get(f).size() == 1 && doc.get(f).value > 10;"));
    }

    public void testDeclineParamNestedAccess() {
        // `params.thresholds[0]` isn't an EDot(ESymbol("params"), name); the matcher only accepts
        // the flat shape.
        assertNull(extract("doc['price'].size() == 1 && doc['price'].value > params.thresholds[0]"));
    }

    // --- other negative cases (must decline extraction) ---------------------

    public void testDeclineOnLogicalOr() {
        assertNull(extract("doc['price'].value > 10 || doc['price'].value < 0"));
    }

    public void testDeclineOnArithmetic() {
        assertNull(extract("doc['price'].value * 2 > 10"));
    }

    public void testDeclineOnDifferentFields() {
        // Mixing two fields — out of scope for the MVP.
        assertNull(extract("doc['price'].value > 10 && doc['age'].value < 30"));
    }

    public void testDeclineOnDynamicFieldKey() {
        assertNull(extract("String f = 'price'; return doc[f].value > 10;"));
    }

    public void testDeclineOnNotEqual() {
        // != would need an OR-of-ranges representation; declined for now.
        assertNull(extract("doc['price'].value != 10"));
    }

    public void testDeclineOnParamsReference() {
        // params.x is not a literal; MVP does not resolve it at compile time.
        assertNull(extract("doc['price'].value > params.threshold"));
    }

    public void testDeclineOnMultipleStatements() {
        assertNull(extract("def x = 1; return doc['price'].value > 10;"));
    }

    // --- list.contains(doc.value) → Terms ----------------------------------

    public void testGuardedListContains() {
        ExtractedPredicate.Terms t = (ExtractedPredicate.Terms) extract(
            "doc['status'].size() == 1 && ['active', 'pending'].contains(doc['status'].value)"
        );
        assertNotNull(t);
        assertEquals("status", t.field());
        assertEquals(java.util.Arrays.asList("active", "pending"), t.values());
    }

    public void testGuardedListContainsSingleElement() {
        ExtractedPredicate.Terms t = (ExtractedPredicate.Terms) extract(
            "doc['status'].size() == 1 && ['active'].contains(doc['status'].value)"
        );
        assertNotNull(t);
        assertEquals(java.util.Arrays.asList("active"), t.values());
    }

    public void testGuardedListContainsEmptyList() {
        // `[].contains(x)` is always false in Painless. Rewrite to termsQuery with an empty list,
        // which MappedFieldType's default renders as a BooleanQuery with no SHOULD clauses —
        // match-nothing. Same result, no semantic change.
        ExtractedPredicate.Terms t = (ExtractedPredicate.Terms) extract(
            "doc['status'].size() == 1 && [].contains(doc['status'].value)"
        );
        assertNotNull(t);
        assertTrue(t.values().isEmpty());
    }

    public void testGuardedListContainsWithAltSpelling() {
        // Argument uses `doc.get('f').value` instead of `doc['f'].value`. extractDocField
        // already canonicalizes both spellings.
        ExtractedPredicate.Terms t = (ExtractedPredicate.Terms) extract(
            "doc['status'].size() == 1 && ['active'].contains(doc.get('status').value)"
        );
        assertNotNull(t);
        assertEquals(java.util.Arrays.asList("active"), t.values());
    }

    public void testDeclineListContainsWithNonStringElement() {
        // Numeric element: Painless walks the list with `1.equals(docValue)`, always false for a
        // String docValue. Rewrite would stringify to "1" and match a doc with keyword "1" —
        // silently more permissive. Decline.
        assertNull(extract("doc['status'].size() == 1 && ['active', 1].contains(doc['status'].value)"));
    }

    public void testDeclineListContainsWithNonLiteralElement() {
        // `String v = 'active'; [v].contains(...)` — element is an ESymbol, not EString. The
        // phase can't fold it at compile time, decline.
        assertNull(extract("String v = 'active'; return doc['status'].size() == 1 && [v].contains(doc['status'].value);"));
    }

    public void testDeclineListContainsOnDifferentField() {
        assertNull(extract("doc['status'].size() == 1 && ['active'].contains(doc['tier'].value)"));
    }

    public void testDeclineUnguardedListContains() {
        assertNull(extract("['active', 'pending'].contains(doc['status'].value)"));
    }

    public void testDeclineListContainsWithParamArg() {
        // `['active'].contains(params.x)` — argument isn't a field reference. This phase scope
        // is list-of-literals-contains-field, not list-of-literals-contains-param. Decline.
        assertNull(extract("doc['status'].size() == 1 && ['active'].contains(params.x)"));
    }

    public void testDeclineOtherMethodOnListLiteral() {
        // Not the `contains` method — e.g. `.size()` on a list literal.
        assertNull(extract("doc['status'].size() == 1 && ['active'].size() == 1"));
    }

    // --- Or of same-field comparisons → Or carrier --------------------------

    public void testGuardedOrOfTwoNumericRanges() {
        ExtractedPredicate.Or or = (ExtractedPredicate.Or) extract(
            "doc['price'].size() == 1 && (doc['price'].value < 10 || doc['price'].value > 100)"
        );
        assertNotNull(or);
        assertEquals(2, or.clauses().size());
        ExtractedPredicate.Range first = (ExtractedPredicate.Range) or.clauses().get(0);
        assertEquals("price", first.field());
        assertNull(first.lower());
        assertEquals(Long.valueOf(10L), first.upper());
        assertFalse(first.includeUpper());
        ExtractedPredicate.Range second = (ExtractedPredicate.Range) or.clauses().get(1);
        assertEquals(Long.valueOf(100L), second.lower());
        assertNull(second.upper());
        assertFalse(second.includeLower());
    }

    public void testGuardedOrOfThreeArms() {
        // Painless parses `a || b || c` left-associatively as `(a || b) || c`. flattenOrChain
        // walks the OR subtree into a flat list of three arms.
        ExtractedPredicate.Or or = (ExtractedPredicate.Or) extract(
            "doc['price'].size() == 1 && "
                + "(doc['price'].value < 0 || doc['price'].value == 42 || doc['price'].value > 1000)"
        );
        assertNotNull(or);
        assertEquals(3, or.clauses().size());
    }

    public void testGuardedOrMixedRangeAndTerm() {
        ExtractedPredicate.Or or = (ExtractedPredicate.Or) extract(
            "doc['status'].size() == 1 && "
                + "(doc['status'].value == 'active' || doc['status'].value == 'pending')"
        );
        assertNotNull(or);
        assertEquals(2, or.clauses().size());
        assertTrue(or.clauses().get(0) instanceof ExtractedPredicate.Term);
        assertTrue(or.clauses().get(1) instanceof ExtractedPredicate.Term);
    }

    public void testDeclineOrOnDifferentFields() {
        // `a || b` where arms reference different fields — neither arm can share the guard's
        // single-valuedness proof for the other's field.
        assertNull(extract(
            "doc['status'].size() == 1 && "
                + "(doc['status'].value == 'x' || doc['tier'].value == 'y')"
        ));
    }

    public void testDeclineOrWhenAnArmIsUnrewritable() {
        // Arithmetic in one arm means that arm can't be extracted individually — the whole Or
        // must decline.
        assertNull(extract(
            "doc['price'].size() == 1 && "
                + "(doc['price'].value < 10 || doc['price'].value * 2 > 100)"
        ));
    }

    public void testDeclineOrWithNestedAndArm() {
        // `a || (b && c)` inside a guarded chain — the `&&` arm carries its own conjunct shape
        // that arm extraction isn't set up to analyse under just the outer guard.
        assertNull(extract(
            "doc['price'].size() == 1 && "
                + "(doc['price'].value < 10 || (doc['price'].value > 100 && doc['price'].value < 200))"
        ));
    }

    public void testDeclineUnguardedOr() {
        assertNull(extract("doc['price'].value < 10 || doc['price'].value > 100"));
    }

    // --- multi-field `And` → And carrier ------------------------------------

    public void testGuardedTwoFieldAnd() {
        ExtractedPredicate.And and = (ExtractedPredicate.And) extract(
            "doc['price'].size() == 1 && doc['price'].value > 10 && "
                + "doc['status'].size() == 1 && doc['status'].value == 'active'"
        );
        assertNotNull(and);
        assertEquals(2, and.clauses().size());
        ExtractedPredicate.Range first = (ExtractedPredicate.Range) and.clauses().get(0);
        assertEquals("price", first.field());
        assertEquals(Long.valueOf(10L), first.lower());
        ExtractedPredicate.Term second = (ExtractedPredicate.Term) and.clauses().get(1);
        assertEquals("status", second.field());
        assertEquals("active", second.value());
    }

    public void testGuardedThreeFieldAnd() {
        ExtractedPredicate.And and = (ExtractedPredicate.And) extract(
            "doc['price'].size() == 1 && doc['price'].value > 10 && "
                + "doc['status'].size() == 1 && doc['status'].value == 'active' && "
                + "doc['region'].size() == 1 && doc['region'].value == 'us-east'"
        );
        assertNotNull(and);
        assertEquals(3, and.clauses().size());
    }

    public void testGuardedAndWithBoundedRangeBlock() {
        // One block has two numeric comparisons that fold into a bounded range; the other
        // block has a single term equality.
        ExtractedPredicate.And and = (ExtractedPredicate.And) extract(
            "doc['price'].size() == 1 && doc['price'].value > 10 && doc['price'].value < 100 && "
                + "doc['status'].size() == 1 && doc['status'].value == 'active'"
        );
        assertNotNull(and);
        assertEquals(2, and.clauses().size());
        ExtractedPredicate.Range first = (ExtractedPredicate.Range) and.clauses().get(0);
        assertEquals(Long.valueOf(10L), first.lower());
        assertEquals(Long.valueOf(100L), first.upper());
    }

    public void testDeclineAndWhenBlockHasNoComparison() {
        // `guard_a && guard_b && ...` without any comparison between guards — block A would be
        // guard-only. Decline; a guard alone isn't a useful predicate.
        assertNull(extract(
            "doc['price'].size() == 1 && doc['status'].size() == 1 && doc['status'].value == 'active'"
        ));
    }

    public void testDeclineAndWhenFirstConjunctIsNotGuard() {
        // Chain must start with a guard. An unguarded comparison at the head means its
        // `.value` read isn't short-circuited by any guard.
        assertNull(extract(
            "doc['price'].value > 10 && doc['status'].size() == 1 && doc['status'].value == 'active'"
        ));
    }

    public void testDeclineAndWhenUnguardedConjunctAppearsAfterBlock() {
        // Duplicate block B that's on a field with an earlier block's guard — we treat guards
        // as block boundaries; the second guard on `price` starts a new block with only a
        // guard and no comparison, which declines.
        assertNull(extract(
            "doc['price'].size() == 1 && doc['price'].value > 10 && doc['price'].size() == 1"
        ));
    }
}
