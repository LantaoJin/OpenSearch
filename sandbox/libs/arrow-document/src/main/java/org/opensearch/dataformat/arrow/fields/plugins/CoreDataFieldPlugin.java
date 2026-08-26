/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataformat.arrow.fields.plugins;

import org.opensearch.index.mapper.BinaryFieldMapper;
import org.opensearch.index.mapper.BooleanFieldMapper;
import org.opensearch.index.mapper.DateFieldMapper;
import org.opensearch.index.mapper.IpFieldMapper;
import org.opensearch.index.mapper.KeywordFieldMapper;
import org.opensearch.index.mapper.MatchOnlyTextFieldMapper;
import org.opensearch.index.mapper.NumberFieldMapper;
import org.opensearch.index.mapper.TextFieldMapper;
import org.opensearch.dataformat.arrow.fields.ArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.BinaryArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.BooleanArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.date.DateNanosArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.date.DateArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.number.ByteArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.number.DoubleArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.number.FloatArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.number.HalfFloatArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.number.IntegerArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.number.LongArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.number.ShortArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.number.TokenCountArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.number.UnsignedLongArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.text.IpArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.text.KeywordArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.text.TextArrowField;

import java.util.HashMap;
import java.util.Map;

/**
 * Core data fields plugin providing Arrow field implementations for all built-in OpenSearch field types.
 */
public class CoreDataFieldPlugin implements ArrowFieldPlugin {

    /** Creates a new CoreDataFieldPlugin. */
    public CoreDataFieldPlugin() {}

    @Override
    public Map<String, ArrowField> getArrowFields() {
        final Map<String, ArrowField> fieldMap = new HashMap<>();
        registerNumericFields(fieldMap);
        registerTemporalFields(fieldMap);
        registerBooleanFields(fieldMap);
        registerTextFields(fieldMap);
        registerBinaryFields(fieldMap);
        return fieldMap;
    }

    private static void registerNumericFields(Map<String, ArrowField> fieldMap) {
        fieldMap.put(NumberFieldMapper.NumberType.HALF_FLOAT.typeName(), new HalfFloatArrowField());
        fieldMap.put(NumberFieldMapper.NumberType.FLOAT.typeName(), new FloatArrowField());
        fieldMap.put(NumberFieldMapper.NumberType.DOUBLE.typeName(), new DoubleArrowField());
        fieldMap.put(NumberFieldMapper.NumberType.BYTE.typeName(), new ByteArrowField());
        fieldMap.put(NumberFieldMapper.NumberType.SHORT.typeName(), new ShortArrowField());
        fieldMap.put(NumberFieldMapper.NumberType.INTEGER.typeName(), new IntegerArrowField());
        fieldMap.put(NumberFieldMapper.NumberType.LONG.typeName(), new LongArrowField());
        fieldMap.put(NumberFieldMapper.NumberType.UNSIGNED_LONG.typeName(), new UnsignedLongArrowField());
        fieldMap.put("token_count", new TokenCountArrowField());
        fieldMap.put("scaled_float", new LongArrowField());
    }

    private static void registerTemporalFields(Map<String, ArrowField> fieldMap) {
        fieldMap.put(DateFieldMapper.CONTENT_TYPE, new DateArrowField());
        fieldMap.put(DateFieldMapper.DATE_NANOS_CONTENT_TYPE, new DateNanosArrowField());
    }

    private static void registerBooleanFields(Map<String, ArrowField> fieldMap) {
        fieldMap.put(BooleanFieldMapper.CONTENT_TYPE, new BooleanArrowField());
    }

    private static void registerTextFields(Map<String, ArrowField> fieldMap) {
        fieldMap.put(TextFieldMapper.CONTENT_TYPE, new TextArrowField());
        fieldMap.put(KeywordFieldMapper.CONTENT_TYPE, new KeywordArrowField());
        fieldMap.put(IpFieldMapper.CONTENT_TYPE, new IpArrowField());
        fieldMap.put(MatchOnlyTextFieldMapper.CONTENT_TYPE, new TextArrowField());
    }

    private static void registerBinaryFields(Map<String, ArrowField> fieldMap) {
        fieldMap.put(BinaryFieldMapper.CONTENT_TYPE, new BinaryArrowField());
    }
}
