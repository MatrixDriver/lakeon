package com.lakeon.hwcloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaweicloud.sdk.bss.v2.BssClient;
import com.huaweicloud.sdk.bss.v2.model.ListCustomerselfResourceRecordsRequest;
import com.huaweicloud.sdk.bss.v2.model.ListCustomerselfResourceRecordsResponse;
import com.huaweicloud.sdk.bss.v2.model.ResFeeRecordV2;
import com.huaweicloud.sdk.bss.v2.model.ShowCustomerMonthlySumRequest;
import com.huaweicloud.sdk.bss.v2.model.ShowCustomerMonthlySumResponse;
import com.lakeon.config.LakeonProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class HuaweiSdkBssBillingClient implements HuaweiBssBillingClient {

    private final LakeonProperties props;
    private final ObjectMapper objectMapper;

    public HuaweiSdkBssBillingClient(LakeonProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public Page listResourceRecords(String billCycle, String resourceId, int offset, int limit) {
        ListCustomerselfResourceRecordsResponse response = bssClient().listCustomerselfResourceRecords(
                new ListCustomerselfResourceRecordsRequest()
                        .withCycle(billCycle)
                        .withResourceId(resourceId)
                        .withOffset(offset)
                        .withLimit(limit));
        List<ResourceRecord> records = response.getFeeRecords() == null ? List.of()
                : response.getFeeRecords().stream().map(this::toRecord).toList();
        return new Page(records,
                response.getTotalCount() == null ? records.size() : response.getTotalCount(),
                response.getCurrency());
    }

    @Override
    public String fetchMonthlyBillSummaryJson(String billCycle) {
        try {
            ShowCustomerMonthlySumResponse response = bssClient().showCustomerMonthlySum(
                    new ShowCustomerMonthlySumRequest().withBillCycle(billCycle));
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize BSS monthly summary", e);
        }
    }

    private ResourceRecord toRecord(ResFeeRecordV2 record) {
        return new ResourceRecord(
                record.getResourceId(),
                record.getResourceName(),
                record.getCloudServiceType(),
                record.getAmount());
    }

    private BssClient bssClient() {
        return BssClient.newBuilder()
                .withCredential(HuaweiCloudSdkSupport.globalCredentials(props))
                .withRegion(HuaweiCloudSdkSupport.region("cn-north-1", "https://bss.myhuaweicloud.com"))
                .build();
    }
}
