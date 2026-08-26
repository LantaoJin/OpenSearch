/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataformat.arrow.fields.core.data.number;

import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.dataformat.arrow.vsr.ManagedVSR;

/**
 * Arrow field writer for 16-bit signed short values using {@link SmallIntVector}.
 */
public class ShortArrowField extends NumericArrowField {

    /** Creates a new ShortArrowField. */
    public ShortArrowField() {}

    @Override
    protected void addToGroup(MappedFieldType mappedFieldType, ManagedVSR managedVSR, Object parseValue) {
        ((SmallIntVector) managedVSR.getVector(mappedFieldType.name())).setSafe(managedVSR.getRowCount(), (Short) parseValue);
    }

    @Override
    public ArrowType getArrowType() {
        return new ArrowType.Int(16, true);
    }

    @Override
    public FieldType getFieldType() {
        return FieldType.nullable(getArrowType());
    }
}
