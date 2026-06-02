/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.spi;

import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

import java.io.IOException;

/**
 * Instruction node for base shard scan setup — reader acquisition, SessionContext creation,
 * default table provider registration.
 *
 * <p>Carries the planner's <em>logical</em> table name (the alias / index-pattern / index the
 * query referenced), captured on the coordinator from the {@code OpenSearchTableScan} leaf. The
 * backend registers the per-shard table under this name so the Substrait plan's {@code NamedTable}
 * reference binds, regardless of which concrete shard index is actually being scanned. May be
 * {@code null} when unknown (e.g. backend self-constructed nodes in tests); the data-node handler
 * falls back to the concrete shard index name in that case.
 *
 * @opensearch.internal
 */
public class ShardScanInstructionNode implements InstructionNode {

    private final String logicalTableName;

    public ShardScanInstructionNode() {
        this((String) null);
    }

    public ShardScanInstructionNode(String logicalTableName) {
        this.logicalTableName = logicalTableName;
    }

    public ShardScanInstructionNode(StreamInput in) throws IOException {
        this.logicalTableName = in.readOptionalString();
    }

    /**
     * The logical table name (alias / index pattern / index) the query referenced, or {@code null}
     * if unknown. The backend registers the scanned shard's table under this name so the Substrait
     * plan's {@code NamedTable} binds.
     */
    public String getLogicalTableName() {
        return logicalTableName;
    }

    @Override
    public InstructionType type() {
        return InstructionType.SETUP_SHARD_SCAN;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeOptionalString(logicalTableName);
    }
}
