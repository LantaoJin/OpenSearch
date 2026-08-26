/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataformat.arrow.fields.plugins;

import org.opensearch.dataformat.arrow.fields.ArrowField;

import java.util.Collections;
import java.util.Map;

/**
 * Plugin interface for registering custom Arrow field implementations.
 */
public interface ArrowFieldPlugin {

    /**
     * Returns the Arrow field implementations provided by this plugin.
     * @return map of field type names to ArrowField implementations
     */
    default Map<String, ArrowField> getArrowFields() {
        return Collections.emptyMap();
    }
}
