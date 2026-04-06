package com.lakeon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private AiConfig ai = new AiConfig();
    private boolean inviteRequired = false;
    private SuspendConfig suspend = new SuspendConfig();
    private BackupConfig backup = new BackupConfig();
    private SyncConfig sync = new SyncConfig();
    private JobConfig job = new JobConfig();
    private KnowledgeConfig knowledge = new KnowledgeConfig();
    private DatalakeConfig datalake = new DatalakeConfig();
    private MemoryConfig memory = new MemoryConfig();
    private DemoConfig demo = new DemoConfig();
    private HwcloudConfig hwcloud = new HwcloudConfig();
    private WikiConfig wiki = new WikiConfig();

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
    public AiConfig getAi() { return ai; }
    public void setAi(AiConfig ai) { this.ai = ai; }
    public boolean getInviteRequired() { return inviteRequired; }
    public void setInviteRequired(boolean inviteRequired) { this.inviteRequired = inviteRequired; }
    public SuspendConfig getSuspend() { return suspend; }
    public void setSuspend(SuspendConfig suspend) { this.suspend = suspend; }
    public BackupConfig getBackup() { return backup; }
    public void setBackup(BackupConfig backup) { this.backup = backup; }
    public SyncConfig getSync() { return sync; }
    public void setSync(SyncConfig sync) { this.sync = sync; }
    public JobConfig getJob() { return job; }
    public void setJob(JobConfig job) { this.job = job; }
    public KnowledgeConfig getKnowledge() { return knowledge; }
    public void setKnowledge(KnowledgeConfig knowledge) { this.knowledge = knowledge; }
    public DatalakeConfig getDatalake() { return datalake; }
    public void setDatalake(DatalakeConfig datalake) { this.datalake = datalake; }
    public MemoryConfig getMemory() { return memory; }
    public void setMemory(MemoryConfig memory) { this.memory = memory; }
    public DemoConfig getDemo() { return demo; }
    public void setDemo(DemoConfig demo) { this.demo = demo; }
    public HwcloudConfig getHwcloud() { return hwcloud; }
    public void setHwcloud(HwcloudConfig hwcloud) { this.hwcloud = hwcloud; }
    public WikiConfig getWiki() { return wiki; }
    public void setWiki(WikiConfig wiki) { this.wiki = wiki; }

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
        private String region = "cn-north-4";

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
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
        private String importImage;
        private List<String> imagePullSecrets = List.of();
        private String computeCpu;
        private String computeMemory;
        private String computeNodeSelectorRaw;
        private java.util.Map<String, String> computeNodeSelector = java.util.Map.of();
        private String nodePoolName = "dbay-compute-pool";
        private int nodePoolMin = 1;
        private int nodePoolMax = 5;
        private int scaleDownUnneededMinutes = 10;

        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
        public String getComputeImage() { return computeImage; }
        public void setComputeImage(String computeImage) { this.computeImage = computeImage; }
        public String getImportImage() { return importImage; }
        public void setImportImage(String importImage) { this.importImage = importImage; }
        public List<String> getImagePullSecrets() { return imagePullSecrets; }
        public void setImagePullSecrets(List<String> imagePullSecrets) { this.imagePullSecrets = imagePullSecrets; }
        public String getComputeCpu() { return computeCpu; }
        public void setComputeCpu(String computeCpu) { this.computeCpu = computeCpu; }
        public String getComputeMemory() { return computeMemory; }
        public void setComputeMemory(String computeMemory) { this.computeMemory = computeMemory; }
        public java.util.Map<String, String> getComputeNodeSelector() { return computeNodeSelector; }
        public void setComputeNodeSelector(java.util.Map<String, String> computeNodeSelector) { this.computeNodeSelector = computeNodeSelector; }
        public String getNodePoolName() { return nodePoolName; }
        public void setNodePoolName(String nodePoolName) { this.nodePoolName = nodePoolName; }
        public int getNodePoolMin() { return nodePoolMin; }
        public void setNodePoolMin(int nodePoolMin) { this.nodePoolMin = nodePoolMin; }
        public int getNodePoolMax() { return nodePoolMax; }
        public void setNodePoolMax(int nodePoolMax) { this.nodePoolMax = nodePoolMax; }
        public int getScaleDownUnneededMinutes() { return scaleDownUnneededMinutes; }
        public void setScaleDownUnneededMinutes(int scaleDownUnneededMinutes) { this.scaleDownUnneededMinutes = scaleDownUnneededMinutes; }

        public String getComputeNodeSelectorRaw() { return computeNodeSelectorRaw; }
        public void setComputeNodeSelectorRaw(String raw) {
            this.computeNodeSelectorRaw = raw;
            if (raw != null && !raw.isBlank()) {
                var map = new java.util.LinkedHashMap<String, String>();
                for (String pair : raw.split(",")) {
                    String trimmed = pair.trim();
                    if (trimmed.isEmpty()) continue;
                    int eq = trimmed.indexOf('=');
                    if (eq > 0) {
                        map.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
                    }
                }
                this.computeNodeSelector = map;
            }
        }
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
        private double storageGbMonthly = 0.5;

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
        public double getStorageGbMonthly() { return storageGbMonthly; }
        public void setStorageGbMonthly(double v) { this.storageGbMonthly = v; }
    }

    public static class CloudConfig {
        private String resourcesFile = "/app/data/cloud-resources.json";
        private List<String> resourceIds = List.of();
        private String consoleRegion = "cn-north-4";
        private String cceClusterId = "";
        private String rdsInstanceId = "";
        private String elbId = "";
        private String eipId = "";
        private String nodePoolId = "";

        public String getResourcesFile() { return resourcesFile; }
        public void setResourcesFile(String resourcesFile) { this.resourcesFile = resourcesFile; }
        public List<String> getResourceIds() { return resourceIds; }
        public void setResourceIds(List<String> resourceIds) { this.resourceIds = resourceIds; }
        public String getConsoleRegion() { return consoleRegion; }
        public void setConsoleRegion(String consoleRegion) { this.consoleRegion = consoleRegion; }
        public String getCceClusterId() { return cceClusterId; }
        public void setCceClusterId(String cceClusterId) { this.cceClusterId = cceClusterId; }
        public String getRdsInstanceId() { return rdsInstanceId; }
        public void setRdsInstanceId(String rdsInstanceId) { this.rdsInstanceId = rdsInstanceId; }
        public String getElbId() { return elbId; }
        public void setElbId(String elbId) { this.elbId = elbId; }
        public String getEipId() { return eipId; }
        public void setEipId(String eipId) { this.eipId = eipId; }
        public String getNodePoolId() { return nodePoolId; }
        public void setNodePoolId(String nodePoolId) { this.nodePoolId = nodePoolId; }
    }

    public static class AlertConfig {
        private String smnTopicUrn;
        private String smnRegion;

        public String getSmnTopicUrn() { return smnTopicUrn; }
        public void setSmnTopicUrn(String smnTopicUrn) { this.smnTopicUrn = smnTopicUrn; }
        public String getSmnRegion() { return smnRegion; }
        public void setSmnRegion(String smnRegion) { this.smnRegion = smnRegion; }
    }

    public static class SuspendConfig {
        private int podRetainMinutes = 30;

        public int getPodRetainMinutes() { return podRetainMinutes; }
        public void setPodRetainMinutes(int podRetainMinutes) { this.podRetainMinutes = podRetainMinutes; }
    }

    public static class BackupConfig {
        private boolean scheduledEnabled = false;
        private String cron = "0 0 2 * * ?";
        private int retentionCount = 7;

        public boolean isScheduledEnabled() { return scheduledEnabled; }
        public void setScheduledEnabled(boolean scheduledEnabled) { this.scheduledEnabled = scheduledEnabled; }
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
        public int getRetentionCount() { return retentionCount; }
        public void setRetentionCount(int retentionCount) { this.retentionCount = retentionCount; }
    }

    public static class SyncConfig {
        private long pollIntervalMs = 30000;
        private long walWarnBytes = 1073741824L;
        private int maxTasks = 10;

        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
        public long getWalWarnBytes() { return walWarnBytes; }
        public void setWalWarnBytes(long walWarnBytes) { this.walWarnBytes = walWarnBytes; }
        public int getMaxTasks() { return maxTasks; }
        public void setMaxTasks(int maxTasks) { this.maxTasks = maxTasks; }
    }

    public static class AiConfig {
        private String apiKey;
        private String baseUrl = "https://api.siliconflow.cn/v1";
        private String model = "";

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        /** Override model for all AI services. Empty = use service-specific defaults. */
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
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

    public static class JobConfig {
        private int timeoutMinutes = 30;
        private int pendingTimeoutMinutes = 5;
        private long orphanCheckIntervalMs = 60000;
        private Map<String, JobTypeConfig> types = new HashMap<>();

        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
        public int getPendingTimeoutMinutes() { return pendingTimeoutMinutes; }
        public void setPendingTimeoutMinutes(int pendingTimeoutMinutes) { this.pendingTimeoutMinutes = pendingTimeoutMinutes; }
        public long getOrphanCheckIntervalMs() { return orphanCheckIntervalMs; }
        public void setOrphanCheckIntervalMs(long orphanCheckIntervalMs) { this.orphanCheckIntervalMs = orphanCheckIntervalMs; }
        public Map<String, JobTypeConfig> getTypes() { return types; }
        public void setTypes(Map<String, JobTypeConfig> types) { this.types = types; }
    }

    public static class JobTypeConfig {
        private String image;
        private String cpu = "2";
        private String memory = "4Gi";

        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }
        public String getCpu() { return cpu; }
        public void setCpu(String cpu) { this.cpu = cpu; }
        public String getMemory() { return memory; }
        public void setMemory(String memory) { this.memory = memory; }
    }

    public static class KnowledgeConfig {
        private String embeddingApiUrl = "https://api.siliconflow.cn/v1/embeddings";
        private String embeddingApiKey = "";
        private String embeddingModel = "BAAI/bge-m3";
        private int presignExpireSeconds = 900;
        private long maxFileSizeBytes = 104857600;
        private int maxConcurrentJobs = 2;
        private RerankConfig rerank = new RerankConfig();

        public String getEmbeddingApiUrl() { return embeddingApiUrl; }
        public void setEmbeddingApiUrl(String embeddingApiUrl) { this.embeddingApiUrl = embeddingApiUrl; }
        public String getEmbeddingApiKey() { return embeddingApiKey; }
        public void setEmbeddingApiKey(String embeddingApiKey) { this.embeddingApiKey = embeddingApiKey; }
        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
        public int getPresignExpireSeconds() { return presignExpireSeconds; }
        public void setPresignExpireSeconds(int presignExpireSeconds) { this.presignExpireSeconds = presignExpireSeconds; }
        public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
        public void setMaxFileSizeBytes(long maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }
        public int getMaxConcurrentJobs() { return maxConcurrentJobs; }
        public void setMaxConcurrentJobs(int maxConcurrentJobs) { this.maxConcurrentJobs = maxConcurrentJobs; }
        public RerankConfig getRerank() { return rerank; }
        public void setRerank(RerankConfig rerank) { this.rerank = rerank; }
    }

    public static class RerankConfig {
        private boolean enabled = true;
        private String url = "http://embedding-service:8000/rerank";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }


    public static class MemoryConfig {
        private String serviceUrl = "http://memory-svc:8001";

        public String getServiceUrl() { return serviceUrl; }
        public void setServiceUrl(String serviceUrl) { this.serviceUrl = serviceUrl; }
    }

    public static class DemoConfig {
        private String tenantId;

        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    }

    public static class HwcloudConfig {
        private String accountId = "";
        private String accountName = "";

        public String getAccountId() { return accountId; }
        public void setAccountId(String accountId) { this.accountId = accountId; }
        public String getAccountName() { return accountName; }
        public void setAccountName(String accountName) { this.accountName = accountName; }
    }

    public static class WikiConfig {
        private String apiKey = "";
        private String baseUrl = "https://api.deepseek.com/v1";
        private String ingestPrompt = "";

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getIngestPrompt() { return ingestPrompt; }
        public void setIngestPrompt(String ingestPrompt) { this.ingestPrompt = ingestPrompt; }
    }

    public static class DatalakeConfig {
        private String cciNamespacePrefix = "datalake-";
        private String vkNodeSelectorKey = "type";
        private String vkNodeSelectorValue = "virtual-kubelet";
        private long pollIntervalMs = 10000;
        private boolean warmPoolEnabled = true;
        private int warmPoolSize = 2;
        private String warmPoolNamespace = "datalake-pool";
        private String warmPoolImage = "swr.cn-north-4.myhuaweicloud.com/flex/ray:2.44-py311-data";
        private int warmPoolIdleTimeoutMinutes = 30;
        private Map<String, String> presetImages = new HashMap<>(Map.of(
            "python-slim", "swr.cn-north-4.myhuaweicloud.com/lakeon/python:3.11-slim",
            "python-data",  "swr.cn-north-4.myhuaweicloud.com/lakeon/python:3.11-data",
            "ray",          "swr.cn-north-4.myhuaweicloud.com/lakeon/ray:2.10-py311",
            "ray-gpu",      "swr.cn-north-4.myhuaweicloud.com/lakeon/ray:2.10-py311-gpu"
        ));

        public String getCciNamespacePrefix() { return cciNamespacePrefix; }
        public void setCciNamespacePrefix(String cciNamespacePrefix) { this.cciNamespacePrefix = cciNamespacePrefix; }
        public String getVkNodeSelectorKey() { return vkNodeSelectorKey; }
        public void setVkNodeSelectorKey(String vkNodeSelectorKey) { this.vkNodeSelectorKey = vkNodeSelectorKey; }
        public String getVkNodeSelectorValue() { return vkNodeSelectorValue; }
        public void setVkNodeSelectorValue(String vkNodeSelectorValue) { this.vkNodeSelectorValue = vkNodeSelectorValue; }
        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
        public Map<String, String> getPresetImages() { return presetImages; }
        public void setPresetImages(Map<String, String> presetImages) { this.presetImages = presetImages; }
        public boolean isWarmPoolEnabled() { return warmPoolEnabled; }
        public void setWarmPoolEnabled(boolean warmPoolEnabled) { this.warmPoolEnabled = warmPoolEnabled; }
        public int getWarmPoolSize() { return warmPoolSize; }
        public void setWarmPoolSize(int warmPoolSize) { this.warmPoolSize = warmPoolSize; }
        public String getWarmPoolNamespace() { return warmPoolNamespace; }
        public void setWarmPoolNamespace(String warmPoolNamespace) { this.warmPoolNamespace = warmPoolNamespace; }
        public String getWarmPoolImage() { return warmPoolImage; }
        public void setWarmPoolImage(String warmPoolImage) { this.warmPoolImage = warmPoolImage; }
        public int getWarmPoolIdleTimeoutMinutes() { return warmPoolIdleTimeoutMinutes; }
        public void setWarmPoolIdleTimeoutMinutes(int warmPoolIdleTimeoutMinutes) { this.warmPoolIdleTimeoutMinutes = warmPoolIdleTimeoutMinutes; }
    }
}
