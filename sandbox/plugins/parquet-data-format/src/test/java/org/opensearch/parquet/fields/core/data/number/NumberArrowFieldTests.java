/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataformat.arrow.fields.core.data.number;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.memory.util.Float16;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float2Vector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.index.mapper.NumberFieldMapper;
import org.opensearch.dataformat.arrow.fields.ArrowField;
import org.opensearch.dataformat.arrow.vsr.ManagedVSR;
import org.opensearch.test.OpenSearchTestCase;

import java.util.List;

public class NumberArrowFieldTests extends OpenSearchTestCase {

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

    public void testIntegerFieldArrowType() {
        IntegerArrowField field = new IntegerArrowField();
        ArrowType.Int type = (ArrowType.Int) field.getArrowType();
        assertEquals(32, type.getBitWidth());
        assertTrue(type.getIsSigned());
        assertTrue(field.getFieldType().isNullable());
    }

    public void testIntegerFieldAddToGroup() {
        IntegerArrowField field = new IntegerArrowField();
        MappedFieldType ft = new NumberFieldMapper.NumberFieldType("val", NumberFieldMapper.NumberType.INTEGER);
        ManagedVSR vsr = createVSR("int-test", field, "val");
        field.createField(ft, vsr, 42);
        vsr.setRowCount(1);
        IntVector vec = (IntVector) vsr.getVector("val");
        assertEquals(42, vec.get(0));
        cleanupVSR(vsr);
    }

    public void testLongFieldArrowType() {
        LongArrowField field = new LongArrowField();
        ArrowType.Int type = (ArrowType.Int) field.getArrowType();
        assertEquals(64, type.getBitWidth());
        assertTrue(type.getIsSigned());
    }

    public void testLongFieldAddToGroup() {
        LongArrowField field = new LongArrowField();
        MappedFieldType ft = new NumberFieldMapper.NumberFieldType("val", NumberFieldMapper.NumberType.LONG);
        ManagedVSR vsr = createVSR("long-test", field, "val");
        field.createField(ft, vsr, 123456789L);
        vsr.setRowCount(1);
        assertEquals(123456789L, ((BigIntVector) vsr.getVector("val")).get(0));
        cleanupVSR(vsr);
    }

    public void testFloatFieldArrowType() {
        FloatArrowField field = new FloatArrowField();
        ArrowType.FloatingPoint type = (ArrowType.FloatingPoint) field.getArrowType();
        assertEquals(FloatingPointPrecision.SINGLE, type.getPrecision());
    }

    public void testFloatFieldAddToGroup() {
        FloatArrowField field = new FloatArrowField();
        MappedFieldType ft = new NumberFieldMapper.NumberFieldType("val", NumberFieldMapper.NumberType.FLOAT);
        ManagedVSR vsr = createVSR("float-test", field, "val");
        field.createField(ft, vsr, 3.14f);
        vsr.setRowCount(1);
        assertEquals(3.14f, ((Float4Vector) vsr.getVector("val")).get(0), 0.001f);
        cleanupVSR(vsr);
    }

    public void testDoubleFieldArrowType() {
        DoubleArrowField field = new DoubleArrowField();
        ArrowType.FloatingPoint type = (ArrowType.FloatingPoint) field.getArrowType();
        assertEquals(FloatingPointPrecision.DOUBLE, type.getPrecision());
    }

    public void testDoubleFieldAddToGroup() {
        DoubleArrowField field = new DoubleArrowField();
        MappedFieldType ft = new NumberFieldMapper.NumberFieldType("val", NumberFieldMapper.NumberType.DOUBLE);
        ManagedVSR vsr = createVSR("double-test", field, "val");
        field.createField(ft, vsr, 2.718);
        vsr.setRowCount(1);
        assertEquals(2.718, ((Float8Vector) vsr.getVector("val")).get(0), 0.001);
        cleanupVSR(vsr);
    }

    public void testHalfFloatFieldArrowType() {
        HalfFloatArrowField field = new HalfFloatArrowField();
        ArrowType.FloatingPoint type = (ArrowType.FloatingPoint) field.getArrowType();
        assertEquals(FloatingPointPrecision.HALF, type.getPrecision());
    }

    public void testHalfFloatFieldAddToGroup() {
        // 7.3 → fp16 → fp32 round-trip should land within fp16 precision (~3 decimal digits).
        HalfFloatArrowField field = new HalfFloatArrowField();
        MappedFieldType ft = new NumberFieldMapper.NumberFieldType("val", NumberFieldMapper.NumberType.HALF_FLOAT);
        ManagedVSR vsr = createVSR("half-float-test", field, "val");
        field.createField(ft, vsr, 7.3);
        vsr.setRowCount(1);
        Float2Vector vec = (Float2Vector) vsr.getVector("val");
        // Value should round-trip to the closest fp16 representation of 7.3 (≈ 7.296875),
        // not stay at the integer 7 (the pre-fix bug) and not collapse to a tiny subnormal.
        assertEquals(7.3f, Float16.toFloat(vec.get(0)), 0.005f);
        cleanupVSR(vsr);
    }

    public void testHalfFloatFieldAddToGroupPreservesFractional() {
        HalfFloatArrowField field = new HalfFloatArrowField();
        MappedFieldType ft = new NumberFieldMapper.NumberFieldType("val", NumberFieldMapper.NumberType.HALF_FLOAT);
        ManagedVSR vsr = createVSR("half-float-fractional-test", field, "val");
        field.createField(ft, vsr, 1.5);
        vsr.setRowCount(1);
        Float2Vector vec = (Float2Vector) vsr.getVector("val");
        // 1.5 is exactly representable in fp16, so this round-trips with zero error.
        assertEquals(1.5f, Float16.toFloat(vec.get(0)), 0.0f);
        cleanupVSR(vsr);
    }

    public void testHalfFloatFieldAddToGroupNegative() {
        // Sign and magnitude both preserved.
        HalfFloatArrowField field = new HalfFloatArrowField();
        MappedFieldType ft = new NumberFieldMapper.NumberFieldType("val", NumberFieldMapper.NumberType.HALF_FLOAT);
        ManagedVSR vsr = createVSR("half-float-negative-test", field, "val");
        field.createField(ft, vsr, -2.5);
        vsr.setRowCount(1);
        Float2Vector vec = (Float2Vector) vsr.getVector("val");
        assertEquals(-2.5f, Float16.toFloat(vec.get(0)), 0.0f);
        cleanupVSR(vsr);
    }

    public void testHalfFloatFieldAddToGroupZero() {
        HalfFloatArrowField field = new HalfFloatArrowField();
        MappedFieldType ft = new NumberFieldMapper.NumberFieldType("val", NumberFieldMapper.NumberType.HALF_FLOAT);
        ManagedVSR vsr = createVSR("half-float-zero-test", field, "val");
        field.createField(ft, vsr, 0.0);
        vsr.setRowCount(1);
        Float2Vector vec = (Float2Vector) vsr.getVector("val");
        assertEquals(0.0f, Float16.toFloat(vec.get(0)), 0.0f);
        cleanupVSR(vsr);
    }

    public void testShortFieldArrowType() {
        ShortArrowField field = new ShortArrowField();
        ArrowType.Int type = (ArrowType.Int) field.getArrowType();
        assertEquals(16, type.getBitWidth());
        assertTrue(type.getIsSigned());
    }

    public void testShortFieldAddToGroup() {
        ShortArrowField field = new ShortArrowField();
        MappedFieldType ft = new NumberFieldMapper.NumberFieldType("val", NumberFieldMapper.NumberType.SHORT);
        ManagedVSR vsr = createVSR("short-test", field, "val");
        field.createField(ft, vsr, (short) 7);
        vsr.setRowCount(1);
        assertEquals(7, ((SmallIntVector) vsr.getVector("val")).get(0));
        cleanupVSR(vsr);
    }

    public void testByteFieldArrowType() {
        ByteArrowField field = new ByteArrowField();
        ArrowType.Int type = (ArrowType.Int) field.getArrowType();
        assertEquals(8, type.getBitWidth());
        assertTrue(type.getIsSigned());
    }

    public void testByteFieldAddToGroup() {
        ByteArrowField field = new ByteArrowField();
        MappedFieldType ft = new NumberFieldMapper.NumberFieldType("val", NumberFieldMapper.NumberType.BYTE);
        ManagedVSR vsr = createVSR("byte-test", field, "val");
        field.createField(ft, vsr, (byte) 3);
        vsr.setRowCount(1);
        assertEquals(3, ((TinyIntVector) vsr.getVector("val")).get(0));
        cleanupVSR(vsr);
    }

    public void testUnsignedLongFieldArrowType() {
        UnsignedLongArrowField field = new UnsignedLongArrowField();
        ArrowType.Int type = (ArrowType.Int) field.getArrowType();
        assertEquals(64, type.getBitWidth());
        assertFalse(type.getIsSigned());
    }

    public void testTokenCountFieldArrowType() {
        TokenCountArrowField field = new TokenCountArrowField();
        ArrowType.Int type = (ArrowType.Int) field.getArrowType();
        assertEquals(32, type.getBitWidth());
        assertTrue(type.getIsSigned());
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
