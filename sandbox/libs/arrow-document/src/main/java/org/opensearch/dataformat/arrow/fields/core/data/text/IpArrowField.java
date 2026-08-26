/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataformat.arrow.fields.core.data.text;

import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.lucene.document.InetAddressPoint;
import org.apache.lucene.util.BytesRef;
import org.opensearch.index.engine.dataformat.FieldTypeCapabilities;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.dataformat.arrow.fields.ArrowField;
import org.opensearch.dataformat.arrow.vsr.ManagedVSR;

import java.net.InetAddress;
import java.util.Set;

/**
 * Arrow field writer for IP address values stored as binary using {@link VarBinaryVector}.
 */
public class IpArrowField extends ArrowField {

    /** Creates a new IpArrowField. */
    public IpArrowField() {}

    @Override
    protected void addToGroup(MappedFieldType mappedFieldType, ManagedVSR managedVSR, Object parseValue) {
        VarBinaryVector vector = (VarBinaryVector) managedVSR.getVector(mappedFieldType.name());
        BytesRef bytesRef = new BytesRef(InetAddressPoint.encode((InetAddress) parseValue));
        vector.setSafe(managedVSR.getRowCount(), bytesRef.bytes, bytesRef.offset, bytesRef.length);
    }

    @Override
    public ArrowType getArrowType() {
        return new ArrowType.Binary();
    }

    @Override
    public FieldType getFieldType() {
        return FieldType.nullable(getArrowType());
    }

    @Override
    public Set<FieldTypeCapabilities.Capability> supportedCapabilities() {
        return Set.of(
            FieldTypeCapabilities.Capability.COLUMNAR_STORAGE,
            FieldTypeCapabilities.Capability.BLOOM_FILTER,
            FieldTypeCapabilities.Capability.POINT_RANGE
        );
    }
}
