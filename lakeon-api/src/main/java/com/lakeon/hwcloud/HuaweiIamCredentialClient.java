package com.lakeon.hwcloud;

import java.time.Instant;
import java.util.Map;

public interface HuaweiIamCredentialClient {

    record TemporaryCredentials(String accessKey, String secretKey, String securityToken, Instant expiresAt) {}

    TemporaryCredentials createTemporaryAccessKeyByToken(Map<String, Object> policy, int durationSeconds);

    TemporaryCredentials createTemporaryAccessKeyByAgency(String domainId,
                                                         String domainName,
                                                         String agencyName,
                                                         int durationSeconds,
                                                         Map<String, Object> policy);
}
