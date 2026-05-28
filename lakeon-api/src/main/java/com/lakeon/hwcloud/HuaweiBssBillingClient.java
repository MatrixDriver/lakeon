package com.lakeon.hwcloud;

import java.math.BigDecimal;
import java.util.List;

public interface HuaweiBssBillingClient {

    record ResourceRecord(String resourceId,
                          String resourceName,
                          String cloudServiceType,
                          BigDecimal amount) {}

    record Page(List<ResourceRecord> records, int totalCount, String currency) {}

    Page listResourceRecords(String billCycle, String resourceId, int offset, int limit);

    String fetchMonthlyBillSummaryJson(String billCycle);
}
