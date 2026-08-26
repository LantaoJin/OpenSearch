/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataformat.arrow.fields.core.data.number;

import org.apache.arrow.vector.Float2Vector;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.dataformat.arrow.vsr.ManagedVSR;

/**
 * Arrow field writer for half-precision (16-bit) floating-point values using {@link Float2Vector}.
 */
public class HalfFloatArrowField extends NumericArrowField {

    /** Creates a new HalfFloatArrowField. */
    public HalfFloatArrowField() {}

    @Override
    protected void addToGroup(MappedFieldType mappedFieldType, ManagedVSR managedVSR, Object parseValue) {
        ((Float2Vector) managedVSR.getVector(mappedFieldType.name())).setSafeWithPossibleTruncate(
            managedVSR.getRowCount(),
            ((Number) parseValue).floatValue()
        );
    }

    @Override
    public ArrowType getArrowType() {
        return new ArrowType.FloatingPoint(FloatingPointPrecision.HALF);
    }

    @Override
    public FieldType getFieldType() {
        return FieldType.nullable(getArrowType());
    }
}
