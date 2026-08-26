/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataformat.arrow.fields.plugins;

import org.opensearch.index.engine.dataformat.FieldTypeCapabilities;
import org.opensearch.index.mapper.DocCountFieldMapper;
import org.opensearch.index.mapper.IdFieldMapper;
import org.opensearch.index.mapper.IgnoredFieldMapper;
import org.opensearch.index.mapper.IndexFieldMapper;
import org.opensearch.index.mapper.RoutingFieldMapper;
import org.opensearch.index.mapper.SeqNoFieldMapper;
import org.opensearch.index.mapper.SourceFieldMapper;
import org.opensearch.index.mapper.VersionFieldMapper;
import org.opensearch.dataformat.arrow.fields.ArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.BinaryArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.number.IntegerArrowField;
import org.opensearch.dataformat.arrow.fields.core.data.number.LongArrowField;
import org.opensearch.dataformat.arrow.fields.core.metadata.IdArrowField;
import org.opensearch.dataformat.arrow.fields.core.metadata.IgnoredArrowField;
import org.opensearch.dataformat.arrow.fields.core.metadata.IndexArrowField;
import org.opensearch.dataformat.arrow.fields.core.metadata.RoutingArrowField;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.opensearch.index.engine.dataformat.FieldTypeCapabilities.Capability.COLUMNAR_STORAGE;
import static org.opensearch.index.engine.dataformat.FieldTypeCapabilities.Capability.STORED_FIELDS;

/**
 * Metadata fields plugin providing Arrow field implementations for OpenSearch metadata fields.
 */
public class MetadataFieldPlugin implements ArrowFieldPlugin {

    /** Creates a new MetadataFieldPlugin. */
    public MetadataFieldPlugin() {}

    @Override
    public Map<String, ArrowField> getArrowFields() {
        final Map<String, ArrowField> fieldMap = new HashMap<>();
        fieldMap.put(DocCountFieldMapper.CONTENT_TYPE, new LongArrowField());
        fieldMap.put("_size", new IntegerArrowField());
        fieldMap.put(IndexFieldMapper.CONTENT_TYPE, new IndexArrowField());
        fieldMap.put(RoutingFieldMapper.CONTENT_TYPE, new RoutingArrowField());
        fieldMap.put(IgnoredFieldMapper.CONTENT_TYPE, new IgnoredArrowField());
        fieldMap.put(IdFieldMapper.CONTENT_TYPE, new IdArrowField());
        fieldMap.put(SeqNoFieldMapper.CONTENT_TYPE, new LongArrowField(false));
        fieldMap.put(SeqNoFieldMapper.PRIMARY_TERM_NAME, new LongArrowField(false));
        fieldMap.put(VersionFieldMapper.CONTENT_TYPE, new LongArrowField(false));
        fieldMap.put(SourceFieldMapper.NAME, new BinaryArrowField() {
            @Override
            public Set<FieldTypeCapabilities.Capability> supportedCapabilities() {
                return Set.of(STORED_FIELDS, COLUMNAR_STORAGE);
            }
        });
        return fieldMap;
    }
}
