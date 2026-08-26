/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataformat.arrow.fields.core.metadata;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.dataformat.arrow.fields.ArrowField;
import org.opensearch.dataformat.arrow.vsr.ManagedVSR;
import org.opensearch.test.OpenSearchTestCase;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MetadataArrowFieldTests extends OpenSearchTestCase {

    private BufferAllocator allocator;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        allocator = new RootAllocator();
    }

    @Override
    public void tearDown() throws Exception {
        allocator.close();
        super.tearDown();
    }

    public void testIdFieldArrowType() {
        assertTrue(new IdArrowField().getArrowType() instanceof ArrowType.Binary);
    }

    public void testIdFieldAddToGroup() {
        IdArrowField field = new IdArrowField();
        MappedFieldType ft = mockFieldType("_id");
        ManagedVSR vsr = createVSR("id-test", field, "_id");
        byte[] ref = "doc-id-1".getBytes(StandardCharsets.UTF_8);
        field.createField(ft, vsr, ref);
        vsr.setRowCount(1);
        byte[] result = ((VarBinaryVector) vsr.getVector("_id")).get(0);
        assertEquals("doc-id-1", new String(result, StandardCharsets.UTF_8));
        cleanupVSR(vsr);
    }

    public void testRoutingFieldArrowType() {
        assertTrue(new RoutingArrowField().getArrowType() instanceof ArrowType.Utf8);
    }

    public void testRoutingFieldAddToGroup() {
        RoutingArrowField field = new RoutingArrowField();
        MappedFieldType ft = mockFieldType("_routing");
        ManagedVSR vsr = createVSR("routing-test", field, "_routing");
        field.createField(ft, vsr, "shard-0");
        vsr.setRowCount(1);
        byte[] result = ((VarCharVector) vsr.getVector("_routing")).get(0);
        assertEquals("shard-0", new String(result, StandardCharsets.UTF_8));
        cleanupVSR(vsr);
    }

    public void testIgnoredFieldArrowType() {
        assertTrue(new IgnoredArrowField().getArrowType() instanceof ArrowType.Utf8);
    }

    private MappedFieldType mockFieldType(String name) {
        MappedFieldType ft = mock(MappedFieldType.class);
        when(ft.name()).thenReturn(name);
        return ft;
    }

    private ManagedVSR createVSR(String id, ArrowField pf, String fieldName) {
        Schema schema = new Schema(List.of(new Field(fieldName, pf.getFieldType(), null)));
        BufferAllocator child = allocator.newChildAllocator(id, 0, Long.MAX_VALUE);
        return new ManagedVSR(id, schema, child);
    }

    private void cleanupVSR(ManagedVSR vsr) {
        vsr.moveToFrozen();
        vsr.close();
    }
}
