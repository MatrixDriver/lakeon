package com.lakeon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "lakeon")
public class LakeonProperties {
    private NeonConfig neon = new NeonConfig();
    private ObsConfig obs = new ObsConfig();
    private ProxyConfig proxy = new ProxyConfig();
    private K8sConfig k8s = new K8sConfig();
    private DefaultsConfig defaults = new DefaultsConfig();
    private AdminConfig admin = new AdminConfig();
    private CostConfig cost = new CostConfig();
    private CloudConfig cloud = new CloudConfig();
    private AlertConfig alert = new AlertConfig();

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
    public AdminConfig getAdmin() { return admin; }
    public void setAdmin(AdminConfig admin) { this.admin = admin; }
    public CostConfig getCost() { return cost; }
    public void setCost(CostConfig cost) { this.cost = cost; }
    public CloudConfig getCloud() { return cloud; }
    public void setCloud(CloudConfig cloud) { this.cloud = cloud; }
    public AlertConfig getAlert() { return alert; }
    public void setAlert(AlertConfig alert) { this.alert = alert; }

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
        private String externalHost;
        private int externalPort = 4432;

        public String getInternalToken() { return internalToken; }
        public void setInternalToken(String internalToken) { this.internalToken = internalToken; }
        public String getExternalHost() { return externalHost; }
        public void setExternalHost(String externalHost) { this.externalHost = externalHost; }
        public int getExternalPort() { return externalPort; }
        public void setExternalPort(int externalPort) { this.externalPort = externalPort; }
    }

    public static class K8sConfig {
        private String namespace;
        private String computeImage;
        private List<String> imagePullSecrets = List.of();
        private String computeCpu;
        private String computeMemory;

        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
        public String getComputeImage() { return computeImage; }
        public void setComputeImage(String computeImage) { this.computeImage = computeImage; }
        public List<String> getImagePullSecrets() { return imagePullSecrets; }
        public void setImagePullSecrets(List<String> imagePullSecrets) { this.imagePullSecrets = imagePullSecrets; }
        public String getComputeCpu() { return computeCpu; }
        public void setComputeCpu(String computeCpu) { this.computeCpu = computeCpu; }
        public String getComputeMemory() { return computeMemory; }
        public void setComputeMemory(String computeMemory) { this.computeMemory = computeMemory; }
    }

    public static class AdminConfig {
        private String token;

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public static class CostConfig {
        private double cceClusterHourly = 1.0;
        private double cceNodeHourly = 1.5;
        private int cceNodeCount = 3;
        private double elbMonthly = 30;
        private double obsPerGbMonthly = 0.099;
        private double rdsMonthly = 500;
        private double eipMonthly = 150;
        private double computeCuHourly = 0.5;

        public double getCceClusterHourly() { return cceClusterHourly; }
        public void setCceClusterHourly(double v) { this.cceClusterHourly = v; }
        public double getCceNodeHourly() { return cceNodeHourly; }
        public void setCceNodeHourly(double v) { this.cceNodeHourly = v; }
        public int getCceNodeCount() { return cceNodeCount; }
        public void setCceNodeCount(int v) { this.cceNodeCount = v; }
        public double getElbMonthly() { return elbMonthly; }
        public void setElbMonthly(double v) { this.elbMonthly = v; }
        public double getObsPerGbMonthly() { return obsPerGbMonthly; }
        public void setObsPerGbMonthly(double v) { this.obsPerGbMonthly = v; }
        public double getRdsMonthly() { return rdsMonthly; }
        public void setRdsMonthly(double v) { this.rdsMonthly = v; }
        public double getEipMonthly() { return eipMonthly; }
        public void setEipMonthly(double v) { this.eipMonthly = v; }
        public double getComputeCuHourly() { return computeCuHourly; }
        public void setComputeCuHourly(double v) { this.computeCuHourly = v; }
    }

    public static class CloudConfig {
        private String resourcesFile = "/app/data/cloud-resources.json";

        public String getResourcesFile() { return resourcesFile; }
        public void setResourcesFile(String resourcesFile) { this.resourcesFile = resourcesFile; }
    }

    public static class AlertConfig {
        private String smnTopicUrn;
        private String smnRegion;

        public String getSmnTopicUrn() { return smnTopicUrn; }
        public void setSmnTopicUrn(String smnTopicUrn) { this.smnTopicUrn = smnTopicUrn; }
        public String getSmnRegion() { return smnRegion; }
        public void setSmnRegion(String smnRegion) { this.smnRegion = smnRegion; }
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
