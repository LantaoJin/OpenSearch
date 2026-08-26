/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataformat.arrow.fields.core.data.number;

import org.opensearch.index.engine.dataformat.FieldTypeCapabilities;
import org.opensearch.dataformat.arrow.fields.ArrowField;

import java.util.Set;

/**
 * Arrow field writer for numeric values. Declares the capabilities common to numeric fields.
 */
public abstract class NumericArrowField extends ArrowField {

    @Override
    public Set<FieldTypeCapabilities.Capability> supportedCapabilities() {
        return Set.of(
            FieldTypeCapabilities.Capability.COLUMNAR_STORAGE,
            FieldTypeCapabilities.Capability.BLOOM_FILTER,
            FieldTypeCapabilities.Capability.POINT_RANGE
        );
    }
}
