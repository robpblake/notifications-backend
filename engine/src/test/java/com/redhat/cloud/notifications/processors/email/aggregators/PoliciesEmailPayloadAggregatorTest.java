package com.redhat.cloud.notifications.processors.email.aggregators;

import com.redhat.cloud.notifications.TestHelpers;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PoliciesEmailPayloadAggregatorTest {

    private Integer getUniqueHostForPolicy(PoliciesEmailPayloadAggregator aggregator, String policy) {
        Map<String, Map> policies = (Map<String, Map>) aggregator.getContext().get("policies");
        return (Integer) policies.get(policy).get("unique_system_count");
    }

    @Test
    void emptyAggregatorHasNoOrgId() {
        PoliciesEmailPayloadAggregator aggregator = new PoliciesEmailPayloadAggregator();
        assertNull(aggregator.getOrgId(), "Empty aggregator has no orgId");
    }

    @Test
    void aggregatorTests() {
        PoliciesEmailPayloadAggregator aggregator = new PoliciesEmailPayloadAggregator();
        aggregator.aggregate(TestHelpers.createEmailAggregation("org-id", "insights", "policies", "policy-01", "host-01"));
        assertEquals("org-id", aggregator.getOrgId());

        // 1 host
        assertEquals(1, aggregator.getUniqueHostCount());

        aggregator.aggregate(TestHelpers.createEmailAggregation("org-id", "insights", "policies", "policy-02", "host-01"));

        // 1 host (even if two policies)
        assertEquals(1, aggregator.getUniqueHostCount());
        assertEquals(1, getUniqueHostForPolicy(aggregator, "policy-01"));
        assertEquals(1, getUniqueHostForPolicy(aggregator, "policy-02"));

        aggregator.aggregate(TestHelpers.createEmailAggregation("org-id", "insights", "policies", "policy-03", "host-02"));
        aggregator.aggregate(TestHelpers.createEmailAggregation("org-id", "insights", "policies", "policy-03", "host-03"));

        // 3 hosts
        assertEquals(3, aggregator.getUniqueHostCount());
        assertEquals(1, getUniqueHostForPolicy(aggregator, "policy-01"));
        assertEquals(1, getUniqueHostForPolicy(aggregator, "policy-02"));
        assertEquals(2, getUniqueHostForPolicy(aggregator, "policy-03"));
    }

    @Test
    void emailWithDifferentOrgIdThrowsError() {
        PoliciesEmailPayloadAggregator aggregator = new PoliciesEmailPayloadAggregator();

        assertThrows(RuntimeException.class, () -> {
            aggregator.aggregate(TestHelpers.createEmailAggregation("org-id-1", "insights", "policies", "policy-02", "host-01"));
            aggregator.aggregate(TestHelpers.createEmailAggregation("org-id-2", "insights", "policies", "policy-02", "host-01"));
        });
    }
}
