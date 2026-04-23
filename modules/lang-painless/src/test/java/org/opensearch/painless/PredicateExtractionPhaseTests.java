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

    public void testDeclineOnDocGetCall() {
        // doc.get('f') is convertible in principle but out of scope for the MVP.
        assertNull(extract("doc.get('price').value > 10"));
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
}
