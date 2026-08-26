/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataformat.arrow.fields.core.data.date;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.TimeStampNanoVector;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.opensearch.index.mapper.DateFieldMapper;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.dataformat.arrow.fields.ArrowField;
import org.opensearch.dataformat.arrow.vsr.ManagedVSR;
import org.opensearch.test.OpenSearchTestCase;

import java.util.List;

public class DateArrowFieldTests extends OpenSearchTestCase {

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

    public void testDateFieldArrowType() {
        DateArrowField field = new DateArrowField();
        ArrowType.Timestamp type = (ArrowType.Timestamp) field.getArrowType();
        assertEquals(TimeUnit.MILLISECOND, type.getUnit());
        assertNull(type.getTimezone());
        assertTrue(field.getFieldType().isNullable());
    }

    public void testDateFieldAddToGroup() {
        DateArrowField field = new DateArrowField();
        MappedFieldType ft = new DateFieldMapper.DateFieldType("val");
        ManagedVSR vsr = createVSR("date-test", field, "val");
        long millis = 1700000000000L;
        field.createField(ft, vsr, millis);
        vsr.setRowCount(1);
        assertEquals(millis, ((TimeStampMilliVector) vsr.getVector("val")).get(0));
        cleanupVSR(vsr);
    }

    public void testDateNanosFieldArrowType() {
        DateNanosArrowField field = new DateNanosArrowField();
        ArrowType.Timestamp type = (ArrowType.Timestamp) field.getArrowType();
        assertEquals(TimeUnit.NANOSECOND, type.getUnit());
        assertNull(type.getTimezone());
    }

    public void testDateNanosFieldAddToGroup() {
        DateNanosArrowField field = new DateNanosArrowField();
        MappedFieldType ft = new DateFieldMapper.DateFieldType("val");
        ManagedVSR vsr = createVSR("datenanos-test", field, "val");
        long nanos = 1700000000000000000L;
        field.createField(ft, vsr, nanos);
        vsr.setRowCount(1);
        assertEquals(nanos, ((TimeStampNanoVector) vsr.getVector("val")).get(0));
        cleanupVSR(vsr);
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
