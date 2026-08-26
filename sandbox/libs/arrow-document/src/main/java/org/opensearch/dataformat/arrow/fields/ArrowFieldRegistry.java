/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataformat.arrow.fields;

import org.opensearch.index.engine.dataformat.FieldTypeCapabilities;
import org.opensearch.dataformat.arrow.fields.plugins.CoreDataFieldPlugin;
import org.opensearch.dataformat.arrow.fields.plugins.MetadataFieldPlugin;
import org.opensearch.dataformat.arrow.fields.plugins.ArrowFieldPlugin;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry mapping OpenSearch field types to their corresponding Arrow field implementations.
 * Populated via {@link ArrowFieldPlugin} registrations.
 */
public final class ArrowFieldRegistry {

    private static final Map<String, ArrowField> FIELD_REGISTRY = new ConcurrentHashMap<>();

    static {
        initialize();
    }

    private ArrowFieldRegistry() {}

    private static void initialize() {
        registerPlugin(new CoreDataFieldPlugin(), "CoreDataFields");
        registerPlugin(new MetadataFieldPlugin(), "MetadataFields");
    }

    private static void registerPlugin(ArrowFieldPlugin plugin, String pluginName) {
        Map<String, ArrowField> fields = plugin.getArrowFields();
        if (fields == null || fields.isEmpty()) {
            return;
        }
        for (Map.Entry<String, org.opensearch.dataformat.arrow.fields.ArrowField> entry : fields.entrySet()) {
            String fieldType = entry.getKey();
            ArrowField arrowField = entry.getValue();
            if (fieldType == null || fieldType.trim().isEmpty()) {
                throw new IllegalArgumentException("Field type name cannot be null or empty");
            }
            if (arrowField == null) {
                throw new IllegalArgumentException("ArrowField implementation cannot be null for type [" + fieldType + "]");
            }
            if (FIELD_REGISTRY.containsKey(fieldType)) {
                throw new IllegalArgumentException(
                    "Field type [" + fieldType + "] is already registered. Plugin [" + pluginName + "] cannot override it."
                );
            }
            FIELD_REGISTRY.put(fieldType, arrowField);
        }
    }

    /**
     * Returns the ArrowField for the given field type.
     * @param fieldType the field type name
     * @return the registered ArrowField, or null if not found
     */
    public static ArrowField getArrowField(String fieldType) {
        return FIELD_REGISTRY.get(fieldType);
    }

    /**
     * Returns the supported field type capabilities for all registered fields,
     * querying each {@link ArrowField} for its supported capabilities.
     *
     * @return unmodifiable set of {@link FieldTypeCapabilities} for all registered field types
     */
    public static Set<FieldTypeCapabilities> getSupportedFieldCapabilities() {
        return FIELD_REGISTRY.entrySet()
            .stream()
            .map(e -> new FieldTypeCapabilities(e.getKey(), e.getValue().supportedCapabilities()))
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns an unmodifiable view of all registered fields.
     * @return map of field type names to ArrowField implementations
     */
    public static Map<String, ArrowField> getRegisteredFields() {
        return Collections.unmodifiableMap(FIELD_REGISTRY);
    }
}
