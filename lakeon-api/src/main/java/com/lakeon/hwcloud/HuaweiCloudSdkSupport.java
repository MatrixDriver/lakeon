package com.lakeon.hwcloud;

import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.core.auth.GlobalCredentials;
import com.huaweicloud.sdk.core.region.Region;
import com.lakeon.config.LakeonProperties;

final class HuaweiCloudSdkSupport {

    private HuaweiCloudSdkSupport() {}

    static GlobalCredentials globalCredentials(LakeonProperties props) {
        GlobalCredentials credentials = new GlobalCredentials()
                .withAk(props.getObs().getAccessKey())
                .withSk(props.getObs().getSecretKey());
        String domainId = props.getHwcloud().getAccountId();
        if (domainId != null && !domainId.isBlank()) {
            credentials.withDomainId(domainId);
        }
        return credentials;
    }

    static BasicCredentials basicCredentials(LakeonProperties props) {
        return new BasicCredentials()
                .withAk(props.getObs().getAccessKey())
                .withSk(props.getObs().getSecretKey());
    }

    static Region region(String id, String endpoint) {
        return new Region(id, endpoint);
    }
}
