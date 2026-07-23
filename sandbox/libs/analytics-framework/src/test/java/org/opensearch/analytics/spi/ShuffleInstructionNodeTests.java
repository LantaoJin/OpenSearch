/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.spi;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.test.OpenSearchTestCase;

import java.util.List;

public class ShuffleInstructionNodeTests extends OpenSearchTestCase {

    public void testShuffleScanWireRoundtrip() throws Exception {
        ShuffleScanInstructionNode original = new ShuffleScanInstructionNode("input-0", 2, 5, "q-xyz", 9, "left");
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            original.writeTo(out);
            try (StreamInput in = out.bytes().streamInput()) {
                ShuffleScanInstructionNode decoded = new ShuffleScanInstructionNode(in);
                assertEquals("input-0", decoded.getNamedInputId());
                assertEquals(2, decoded.getShufflePartitionIndex());
                assertEquals(5, decoded.getExpectedSenders());
                assertEquals("q-xyz", decoded.getQueryId());
                assertEquals(9, decoded.getTargetStageId());
                assertEquals("left", decoded.getSide());
                assertEquals(InstructionType.SHUFFLE_SCAN, decoded.type());
                // The 6-arg ctor leaves producerPlanBytes null and usesFlightShuffle false (default:
                // the Java shuffle path). Both must survive the wire round-trip.
                assertNull(decoded.getProducerPlanBytes());
                assertFalse(decoded.usesFlightShuffle());
            }
        }
    }

    public void testShuffleScanWireRoundtripWithProducerPlanAndFlight() throws Exception {
        byte[] partialPlan = new byte[] { 1, 2, 3, 4, 5 };
        ShuffleScanInstructionNode original = new ShuffleScanInstructionNode(
            "input-7",
            3,
            2,
            "q-flight",
            4,
            "right",
            partialPlan,
            /* usesFlightShuffle */ true
        );
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            original.writeTo(out);
            try (StreamInput in = out.bytes().streamInput()) {
                ShuffleScanInstructionNode decoded = new ShuffleScanInstructionNode(in);
                assertEquals("input-7", decoded.getNamedInputId());
                assertEquals(3, decoded.getShufflePartitionIndex());
                assertEquals(2, decoded.getExpectedSenders());
                assertEquals("q-flight", decoded.getQueryId());
                assertEquals(4, decoded.getTargetStageId());
                assertEquals("right", decoded.getSide());
                assertArrayEquals(partialPlan, decoded.getProducerPlanBytes());
                assertTrue("usesFlightShuffle must round-trip true", decoded.usesFlightShuffle());
            }
        }
    }

    public void testShuffleScanFlightFlagDefaultsFalseWithProducerPlan() throws Exception {
        // The 7-arg ctor (producerPlanBytes, no flight flag) must default usesFlightShuffle=false, so
        // an agg-shuffle stage built the old way never silently activates the Flight consumer path.
        byte[] partialPlan = new byte[] { 9, 8, 7 };
        ShuffleScanInstructionNode original = new ShuffleScanInstructionNode("input-1", 0, 1, "q", 0, "left", partialPlan);
        assertFalse("7-arg ctor must default usesFlightShuffle=false", original.usesFlightShuffle());
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            original.writeTo(out);
            try (StreamInput in = out.bytes().streamInput()) {
                ShuffleScanInstructionNode decoded = new ShuffleScanInstructionNode(in);
                assertArrayEquals(partialPlan, decoded.getProducerPlanBytes());
                assertFalse(decoded.usesFlightShuffle());
            }
        }
    }

    public void testShuffleProducerWireRoundtrip() throws Exception {
        ShuffleProducerInstructionNode original = new ShuffleProducerInstructionNode(
            List.of(3, 7),
            4,
            List.of("node-a", "node-b", "node-c", "node-d"),
            "q-xyz",
            2,
            "right"
        );
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            original.writeTo(out);
            try (StreamInput in = out.bytes().streamInput()) {
                ShuffleProducerInstructionNode decoded = new ShuffleProducerInstructionNode(in);
                assertEquals(List.of(3, 7), decoded.getHashKeyChannels());
                assertEquals(4, decoded.getPartitionCount());
                assertEquals(List.of("node-a", "node-b", "node-c", "node-d"), decoded.getTargetWorkerNodeIds());
                assertEquals("q-xyz", decoded.getQueryId());
                assertEquals(2, decoded.getTargetStageId());
                assertEquals("right", decoded.getSide());
                assertEquals(InstructionType.SHUFFLE_PRODUCER, decoded.type());
                // 6-arg ctor defaults usesFlightShuffle=false and it must round-trip.
                assertFalse(decoded.usesFlightShuffle());
            }
        }
    }

    public void testShuffleProducerFlightFlagWireRoundtrip() throws Exception {
        ShuffleProducerInstructionNode original = new ShuffleProducerInstructionNode(
            List.of(1),
            2,
            List.of("node-a", "node-b"),
            "q-flight",
            5,
            "left",
            /* usesFlightShuffle */ true
        );
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            original.writeTo(out);
            try (StreamInput in = out.bytes().streamInput()) {
                ShuffleProducerInstructionNode decoded = new ShuffleProducerInstructionNode(in);
                assertTrue("usesFlightShuffle must round-trip true", decoded.usesFlightShuffle());
                assertEquals(List.of(1), decoded.getHashKeyChannels());
                assertEquals("q-flight", decoded.getQueryId());
            }
        }
    }

    public void testInstructionTypeReadNodeDispatch() throws Exception {
        ShuffleScanInstructionNode scan = new ShuffleScanInstructionNode("x", 0, 1, "q", 0, "right");
        try (BytesStreamOutput out = new BytesStreamOutput()) {
            scan.writeTo(out);
            try (StreamInput in = out.bytes().streamInput()) {
                InstructionNode decoded = InstructionType.SHUFFLE_SCAN.readNode(in);
                assertTrue(decoded instanceof ShuffleScanInstructionNode);
            }
        }
    }
}
