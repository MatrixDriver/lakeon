package com.lakeon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "lakeon")
public class LakeonProperties {
    private NeonConfig neon = new NeonConfig();
    private ObsConfig obs = new ObsConfig();
    private ProxyConfig proxy = new ProxyConfig();
    private K8sConfig k8s = new K8sConfig();
    private DefaultsConfig defaults = new DefaultsConfig();

    public NeonConfig getNeon() { return neon; }
    public void setNeon(NeonConfig neon) { this.neon = neon; }
    public ObsConfig getObs() { return obs; }
    public void setObs(ObsConfig obs) { this.obs = obs; }
    public ProxyConfig getProxy() { return proxy; }
    public void setProxy(ProxyConfig proxy) { this.proxy = proxy; }
    public K8sConfig getK8s() { return k8s; }
    public void setK8s(K8sConfig k8s) { this.k8s = k8s; }
    public DefaultsConfig getDefaults() { return defaults; }
    public void setDefaults(DefaultsConfig defaults) { this.defaults = defaults; }

    public static class NeonConfig {
        private String pageserverUrl;
        private String safekeeperUrls;
        private String storageBrokerUrl;

        public String getPageserverUrl() { return pageserverUrl; }
        public void setPageserverUrl(String pageserverUrl) { this.pageserverUrl = pageserverUrl; }
        public String getSafekeeperUrls() { return safekeeperUrls; }
        public void setSafekeeperUrls(String safekeeperUrls) { this.safekeeperUrls = safekeeperUrls; }
        public String getStorageBrokerUrl() { return storageBrokerUrl; }
        public void setStorageBrokerUrl(String storageBrokerUrl) { this.storageBrokerUrl = storageBrokerUrl; }
    }

    public static class ObsConfig {
        private String endpoint;
        private String bucket;
        private String accessKey;
        private String secretKey;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    }

    public static class ProxyConfig {
        private String internalToken;

        public String getInternalToken() { return internalToken; }
        public void setInternalToken(String internalToken) { this.internalToken = internalToken; }
    }

    public static class K8sConfig {
        private String namespace;
        private String computeImage;

        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
        public String getComputeImage() { return computeImage; }
        public void setComputeImage(String computeImage) { this.computeImage = computeImage; }
    }

    public static class DefaultsConfig {
        private String computeSize;
        private String suspendTimeout;
        private Integer storageLimitGb;

        public String getComputeSize() { return computeSize; }
        public void setComputeSize(String computeSize) { this.computeSize = computeSize; }
        public String getSuspendTimeout() { return suspendTimeout; }
        public void setSuspendTimeout(String suspendTimeout) { this.suspendTimeout = suspendTimeout; }
        public Integer getStorageLimitGb() { return storageLimitGb; }
        public void setStorageLimitGb(Integer storageLimitGb) { this.storageLimitGb = storageLimitGb; }
    }
}
