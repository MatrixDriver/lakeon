package com.lakeon.hwcloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaweicloud.sdk.swr.v2.SwrClient;
import com.huaweicloud.sdk.swr.v2.model.CreateSecretRequest;
import com.huaweicloud.sdk.swr.v2.model.CreateSecretResponse;
import com.huaweicloud.sdk.swr.v2.region.SwrRegion;
import com.lakeon.config.LakeonProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class HuaweiSdkSwrSecretClient implements HuaweiSwrSecretClient {

    private final LakeonProperties props;
    private final ObjectMapper objectMapper;

    public HuaweiSdkSwrSecretClient(LakeonProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public String createDockerConfigJson(String projectName) {
        try {
            CreateSecretResponse response = swrClient().createSecret(
                    new CreateSecretRequest().withProjectname(projectName));
            return objectMapper.writeValueAsString(Map.of("auths", response.getAuths()));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SWR docker config", e);
        }
    }

    private SwrClient swrClient() {
        String region = props.getObs().getRegion() != null ? props.getObs().getRegion() : "cn-north-4";
        return SwrClient.newBuilder()
                .withCredential(HuaweiCloudSdkSupport.basicCredentials(props))
                .withRegion(SwrRegion.valueOf(region))
                .build();
    }
}
