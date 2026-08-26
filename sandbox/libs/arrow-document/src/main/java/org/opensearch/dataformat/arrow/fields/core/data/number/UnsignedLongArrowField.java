/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataformat.arrow.fields.core.data.number;

import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.dataformat.arrow.vsr.ManagedVSR;

/**
 * Arrow field writer for 64-bit unsigned long values using {@link UInt8Vector}.
 */
public class UnsignedLongArrowField extends NumericArrowField {

    /** Creates a new UnsignedLongArrowField. */
    public UnsignedLongArrowField() {}

    @Override
    protected void addToGroup(MappedFieldType mappedFieldType, ManagedVSR managedVSR, Object parseValue) {
        ((UInt8Vector) managedVSR.getVector(mappedFieldType.name())).setSafe(managedVSR.getRowCount(), ((Number) parseValue).longValue());
    }

    @Override
    public ArrowType getArrowType() {
        return new ArrowType.Int(64, false);
    }

    @Override
    public FieldType getFieldType() {
        return FieldType.nullable(getArrowType());
    }
}
