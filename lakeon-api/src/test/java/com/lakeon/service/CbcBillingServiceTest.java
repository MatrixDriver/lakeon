package com.lakeon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.hwcloud.HuaweiBssBillingClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CbcBillingServiceTest {

    @Test
    void getMonthlyBillParsedAggregatesBssSdkRecordsForConfiguredResources() {
        LakeonProperties props = new LakeonProperties();
        props.getCloud().setResourceIds(List.of("rds-1", "obs-1"));
        props.getObs().setAccessKey("ak");
        props.getObs().setSecretKey("sk");
        FakeBillingClient billingClient = new FakeBillingClient();
        CbcBillingService service = new CbcBillingService(props, new ObjectMapper(), billingClient);

        Map<String, Object> result = service.getMonthlyBillParsed("2026-05");

        assertThat(billingClient.lastBillCycle).isEqualTo("2026-05");
        assertThat(billingClient.requestedResourceIds).containsExactly("rds-1", "obs-1");
        assertThat(result).containsEntry("source", "cbc");
        assertThat(result).containsEntry("total", 15.75);
        assertThat(result).containsEntry("currency", "CNY");
        assertThat(result).containsEntry("resource_count", 2);

        @SuppressWarnings("unchecked")
        Map<String, Object> breakdown = (Map<String, Object>) result.get("breakdown");
        assertThat(breakdown).containsEntry("RDS 数据库", 12.5);
        assertThat(breakdown).containsEntry("OBS 对象存储", 3.25);
    }

    private static class FakeBillingClient implements HuaweiBssBillingClient {
        String lastBillCycle;
        final java.util.ArrayList<String> requestedResourceIds = new java.util.ArrayList<>();

        @Override
        public Page listResourceRecords(String billCycle, String resourceId, int offset, int limit) {
            this.lastBillCycle = billCycle;
            this.requestedResourceIds.add(resourceId);
            if ("rds-1".equals(resourceId)) {
                return new Page(List.of(
                        new ResourceRecord("rds-1", "prod-rds", "hws.service.type.rds", BigDecimal.valueOf(12.50))
                ), 1, "CNY");
            }
            if ("obs-1".equals(resourceId)) {
                return new Page(List.of(
                        new ResourceRecord("obs-1", "prod-obs", "hws.service.type.obs", BigDecimal.valueOf(3.25))
                ), 1, "CNY");
            }
            return new Page(List.of(), 0, "CNY");
        }

        @Override
        public String fetchMonthlyBillSummaryJson(String billCycle) {
            throw new AssertionError("raw monthly summary should not be used by parsed billing");
        }
    }
}
