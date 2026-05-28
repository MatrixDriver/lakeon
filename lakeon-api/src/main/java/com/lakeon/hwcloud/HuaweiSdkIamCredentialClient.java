package com.lakeon.hwcloud;

import com.huaweicloud.sdk.iam.v3.IamClient;
import com.huaweicloud.sdk.iam.v3.model.AgencyAuth;
import com.huaweicloud.sdk.iam.v3.model.AgencyAuthIdentity;
import com.huaweicloud.sdk.iam.v3.model.CreateTemporaryAccessKeyByAgencyRequest;
import com.huaweicloud.sdk.iam.v3.model.CreateTemporaryAccessKeyByAgencyRequestBody;
import com.huaweicloud.sdk.iam.v3.model.CreateTemporaryAccessKeyByAgencyResponse;
import com.huaweicloud.sdk.iam.v3.model.CreateTemporaryAccessKeyByTokenRequest;
import com.huaweicloud.sdk.iam.v3.model.CreateTemporaryAccessKeyByTokenRequestBody;
import com.huaweicloud.sdk.iam.v3.model.CreateTemporaryAccessKeyByTokenResponse;
import com.huaweicloud.sdk.iam.v3.model.Credential;
import com.huaweicloud.sdk.iam.v3.model.IdentityAssumerole;
import com.huaweicloud.sdk.iam.v3.model.IdentityToken;
import com.huaweicloud.sdk.iam.v3.model.ServicePolicy;
import com.huaweicloud.sdk.iam.v3.model.ServiceStatement;
import com.huaweicloud.sdk.iam.v3.model.TokenAuth;
import com.huaweicloud.sdk.iam.v3.model.TokenAuthIdentity;
import com.lakeon.config.LakeonProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class HuaweiSdkIamCredentialClient implements HuaweiIamCredentialClient {

    private final LakeonProperties props;

    public HuaweiSdkIamCredentialClient(LakeonProperties props) {
        this.props = props;
    }

    @Override
    public TemporaryCredentials createTemporaryAccessKeyByToken(Map<String, Object> policy, int durationSeconds) {
        TokenAuthIdentity identity = new TokenAuthIdentity()
                .withMethods(List.of(TokenAuthIdentity.MethodsEnum.TOKEN))
                .withToken(new IdentityToken().withDurationSeconds(durationSeconds));
        if (policy != null) {
            identity.withPolicy(toServicePolicy(policy));
        }

        CreateTemporaryAccessKeyByTokenResponse response = iamClient().createTemporaryAccessKeyByToken(
                new CreateTemporaryAccessKeyByTokenRequest()
                        .withBody(new CreateTemporaryAccessKeyByTokenRequestBody()
                                .withAuth(new TokenAuth().withIdentity(identity))));
        return toTemporaryCredentials(response.getCredential());
    }

    @Override
    public TemporaryCredentials createTemporaryAccessKeyByAgency(String domainId,
                                                                String domainName,
                                                                String agencyName,
                                                                int durationSeconds,
                                                                Map<String, Object> policy) {
        IdentityAssumerole assumeRole = new IdentityAssumerole()
                .withAgencyName(agencyName)
                .withDurationSeconds(durationSeconds);
        if (domainId != null && !domainId.isBlank()) {
            assumeRole.withDomainId(domainId);
        } else {
            assumeRole.withDomainName(domainName);
        }

        AgencyAuthIdentity identity = new AgencyAuthIdentity()
                .withMethods(List.of(AgencyAuthIdentity.MethodsEnum.ASSUME_ROLE))
                .withAssumeRole(assumeRole);
        if (policy != null) {
            identity.withPolicy(toServicePolicy(policy));
        }

        CreateTemporaryAccessKeyByAgencyResponse response = iamClient().createTemporaryAccessKeyByAgency(
                new CreateTemporaryAccessKeyByAgencyRequest()
                        .withBody(new CreateTemporaryAccessKeyByAgencyRequestBody()
                                .withAuth(new AgencyAuth().withIdentity(identity))));
        return toTemporaryCredentials(response.getCredential());
    }

    private IamClient iamClient() {
        String region = props.getObs().getRegion() != null ? props.getObs().getRegion() : "cn-north-4";
        return IamClient.newBuilder()
                .withCredential(HuaweiCloudSdkSupport.globalCredentials(props))
                .withRegion(HuaweiCloudSdkSupport.region(region, "https://iam." + region + ".myhuaweicloud.com"))
                .build();
    }

    private static TemporaryCredentials toTemporaryCredentials(Credential credential) {
        if (credential == null) {
            throw new IllegalStateException("Huawei IAM response missing credential");
        }
        return new TemporaryCredentials(
                credential.getAccess(),
                credential.getSecret(),
                credential.getSecuritytoken(),
                Instant.parse(credential.getExpiresAt()));
    }

    @SuppressWarnings("unchecked")
    private static ServicePolicy toServicePolicy(Map<String, Object> policy) {
        ServicePolicy out = new ServicePolicy().withVersion((String) policy.get("Version"));
        List<ServiceStatement> statements = new ArrayList<>();
        Object rawStatements = policy.get("Statement");
        if (rawStatements instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> raw = (Map<String, Object>) item;
                ServiceStatement statement = new ServiceStatement()
                        .withEffect(ServiceStatement.EffectEnum.fromValue((String) raw.get("Effect")))
                        .withAction((List<String>) raw.get("Action"))
                        .withResource((List<String>) raw.get("Resource"));
                Object condition = raw.get("Condition");
                if (condition != null) {
                    statement.withCondition((Map<String, Map<String, List<String>>>) condition);
                }
                statements.add(statement);
            }
        }
        return out.withStatement(statements);
    }
}
