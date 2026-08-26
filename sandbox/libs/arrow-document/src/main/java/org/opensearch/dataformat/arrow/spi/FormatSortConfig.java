/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataformat.arrow.spi;

import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.IndexSortConfig;
import org.opensearch.search.sort.SortOrder;

import java.util.Collections;
import java.util.List;

/**
 * The index sort, resolved from index settings into the parallel-array form native writers expect.
 *
 * <p>Format-independent: {@code index.sort.field} / {@code .order} / {@code .missing} mean the same
 * thing whichever format materialises them. Applying a sort is what may reorder rows, which is why
 * {@link NativeFormatWriter#getRowIdMapping()} exists.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public record FormatSortConfig(List<String> sortColumns, List<Boolean> reverseSorts, List<Boolean> nullsFirst) {

    private static final FormatSortConfig EMPTY = new FormatSortConfig(
        Collections.emptyList(),
        Collections.emptyList(),
        Collections.emptyList()
    );

    /** Resolves the sort from index settings. */
    public FormatSortConfig(IndexSettings indexSettings) {
        this(
            IndexSortConfig.INDEX_SORT_FIELD_SETTING.get(indexSettings.getSettings()),
            IndexSortConfig.INDEX_SORT_ORDER_SETTING.get(indexSettings.getSettings()).stream().map(o -> o == SortOrder.DESC).toList(),
            IndexSortConfig.INDEX_SORT_MISSING_SETTING.get(indexSettings.getSettings()).stream().map("_first"::equals).toList()
        );
    }

    /** The no-sort configuration. */
    public static FormatSortConfig empty() {
        return EMPTY;
    }

    /** Whether any sort column is configured. */
    public boolean isEmpty() {
        return sortColumns.isEmpty();
    }
}
