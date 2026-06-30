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
    private DataPlaneConfig dataPlane = new DataPlaneConfig();
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
    private HwcloudConfig hwcloud = new HwcloudConfig();
    private OAuthConfig oauth = new OAuthConfig();
    private ComputeJwtConfig computeJwt = new ComputeJwtConfig();
    private ComputeWarmPoolConfig computeWarmPool = new ComputeWarmPoolConfig();
    private DicerConfig dicer = new DicerConfig();

    public NeonConfig getNeon() { return neon; }
    public void setNeon(NeonConfig neon) { this.neon = neon; }
    public ObsConfig getObs() { return obs; }
    public void setObs(ObsConfig obs) { this.obs = obs; }
    public ProxyConfig getProxy() { return proxy; }
    public void setProxy(ProxyConfig proxy) { this.proxy = proxy; }
    public DataPlaneConfig getDataPlane() { return dataPlane; }
    public void setDataPlane(DataPlaneConfig dataPlane) { this.dataPlane = dataPlane; }
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
    public HwcloudConfig getHwcloud() { return hwcloud; }
    public void setHwcloud(HwcloudConfig hwcloud) { this.hwcloud = hwcloud; }
    public OAuthConfig getOauth() { return oauth; }
    public void setOauth(OAuthConfig oauth) { this.oauth = oauth; }
    public ComputeJwtConfig getComputeJwt() { return computeJwt; }
    public void setComputeJwt(ComputeJwtConfig computeJwt) { this.computeJwt = computeJwt; }
    public ComputeWarmPoolConfig getComputeWarmPool() { return computeWarmPool; }
    public void setComputeWarmPool(ComputeWarmPoolConfig v) { this.computeWarmPool = v; }
    public DicerConfig getDicer() { return dicer; }
    public void setDicer(DicerConfig dicer) { this.dicer = dicer; }

    public static class NeonConfig {
        private String pageserverUrl;
        private String safekeeperUrls;
        private String storageBrokerUrl;
        private List<PageserverNodeConfig> pageserverNodes = List.of();
        private String pageserverNodesRaw;
        private boolean pageserverDiscoveryEnabled = false;
        private String pageserverDiscoveryNamespace;
        private String pageserverDiscoveryLabelSelector = "app=pageserver";
        private String pageserverDiscoveryHeadlessService = "pageserver-headless";
        private int pageserverDiscoveryHttpPort = 9898;
        private int pageserverDiscoveryPgPort = 6400;

        public String getPageserverUrl() { return pageserverUrl; }
        public void setPageserverUrl(String pageserverUrl) { this.pageserverUrl = pageserverUrl; }
        public String getSafekeeperUrls() { return safekeeperUrls; }
        public void setSafekeeperUrls(String safekeeperUrls) { this.safekeeperUrls = safekeeperUrls; }
        public String getStorageBrokerUrl() { return storageBrokerUrl; }
        public void setStorageBrokerUrl(String storageBrokerUrl) { this.storageBrokerUrl = storageBrokerUrl; }
        public List<PageserverNodeConfig> getPageserverNodes() { return pageserverNodes; }
        public void setPageserverNodes(List<PageserverNodeConfig> pageserverNodes) {
            this.pageserverNodes = pageserverNodes != null ? pageserverNodes : List.of();
        }
        public String getPageserverNodesRaw() { return pageserverNodesRaw; }
        public void setPageserverNodesRaw(String pageserverNodesRaw) {
            this.pageserverNodesRaw = pageserverNodesRaw;
            if (pageserverNodesRaw == null || pageserverNodesRaw.isBlank()) {
                return;
            }
            this.pageserverNodes = pageserverNodesRaw.lines()
                .flatMap(line -> List.of(line.split(",")).stream())
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(PageserverNodeConfig::parse)
                .toList();
        }
        public boolean isPageserverDiscoveryEnabled() { return pageserverDiscoveryEnabled; }
        public void setPageserverDiscoveryEnabled(boolean pageserverDiscoveryEnabled) { this.pageserverDiscoveryEnabled = pageserverDiscoveryEnabled; }
        public String getPageserverDiscoveryNamespace() { return pageserverDiscoveryNamespace; }
        public void setPageserverDiscoveryNamespace(String pageserverDiscoveryNamespace) { this.pageserverDiscoveryNamespace = pageserverDiscoveryNamespace; }
        public String getPageserverDiscoveryLabelSelector() { return pageserverDiscoveryLabelSelector; }
        public void setPageserverDiscoveryLabelSelector(String pageserverDiscoveryLabelSelector) { this.pageserverDiscoveryLabelSelector = pageserverDiscoveryLabelSelector; }
        public String getPageserverDiscoveryHeadlessService() { return pageserverDiscoveryHeadlessService; }
        public void setPageserverDiscoveryHeadlessService(String pageserverDiscoveryHeadlessService) { this.pageserverDiscoveryHeadlessService = pageserverDiscoveryHeadlessService; }
        public int getPageserverDiscoveryHttpPort() { return pageserverDiscoveryHttpPort; }
        public void setPageserverDiscoveryHttpPort(int pageserverDiscoveryHttpPort) { this.pageserverDiscoveryHttpPort = pageserverDiscoveryHttpPort; }
        public int getPageserverDiscoveryPgPort() { return pageserverDiscoveryPgPort; }
        public void setPageserverDiscoveryPgPort(int pageserverDiscoveryPgPort) { this.pageserverDiscoveryPgPort = pageserverDiscoveryPgPort; }
    }

    public static class PageserverNodeConfig {
        private String id;
        private String httpUrl;
        private String pgHost;
        private int pgPort = 6400;

        public PageserverNodeConfig() {}

        public PageserverNodeConfig(String id, String httpUrl, String pgHost, int pgPort) {
            this.id = id;
            this.httpUrl = httpUrl;
            this.pgHost = pgHost;
            this.pgPort = pgPort;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getHttpUrl() { return httpUrl; }
        public void setHttpUrl(String httpUrl) { this.httpUrl = httpUrl; }
        public String getPgHost() { return pgHost; }
        public void setPgHost(String pgHost) { this.pgHost = pgHost; }
        public int getPgPort() { return pgPort; }
        public void setPgPort(int pgPort) { this.pgPort = pgPort; }

        static PageserverNodeConfig parse(String value) {
            String[] idAndRest = value.split("=", 2);
            if (idAndRest.length != 2) {
                throw new IllegalArgumentException("pageserver node must be id=httpUrl|pgHost|pgPort: " + value);
            }
            String[] parts = idAndRest[1].split("\\|", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("pageserver node must be id=httpUrl|pgHost|pgPort: " + value);
            }
            return new PageserverNodeConfig(
                idAndRest[0].trim(),
                parts[0].trim(),
                parts[1].trim(),
                Integer.parseInt(parts[2].trim())
            );
        }
    }

    public static class DicerConfig {
        private boolean enabled = false;
        private String endpoint;
        private String nodeLoadsRaw;
        private String unavailableNodesRaw;
        private boolean liveLoadEnabled = false;
        private long liveLoadPollIntervalMs = 10000L;
        private long liveLoadInitialDelayMs = 5000L;
        private long metricsTimeoutMs = 1000L;
        private long snapshotTtlMs = 30000L;
        private boolean autoFailoverEnabled = false;
        private long autoFailoverPollIntervalMs = 15000L;
        private long failoverNodeCooldownMs = 300000L;
        private boolean autoRebalanceEnabled = false;
        private long autoRebalancePollIntervalMs = 60000L;
        private int autoRebalanceMinMoves = 10;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getNodeLoadsRaw() { return nodeLoadsRaw; }
        public void setNodeLoadsRaw(String nodeLoadsRaw) { this.nodeLoadsRaw = nodeLoadsRaw; }
        public String getUnavailableNodesRaw() { return unavailableNodesRaw; }
        public void setUnavailableNodesRaw(String unavailableNodesRaw) { this.unavailableNodesRaw = unavailableNodesRaw; }
        public boolean isLiveLoadEnabled() { return liveLoadEnabled; }
        public void setLiveLoadEnabled(boolean liveLoadEnabled) { this.liveLoadEnabled = liveLoadEnabled; }
        public long getLiveLoadPollIntervalMs() { return liveLoadPollIntervalMs; }
        public void setLiveLoadPollIntervalMs(long liveLoadPollIntervalMs) { this.liveLoadPollIntervalMs = liveLoadPollIntervalMs; }
        public long getLiveLoadInitialDelayMs() { return liveLoadInitialDelayMs; }
        public void setLiveLoadInitialDelayMs(long liveLoadInitialDelayMs) { this.liveLoadInitialDelayMs = liveLoadInitialDelayMs; }
        public long getMetricsTimeoutMs() { return metricsTimeoutMs; }
        public void setMetricsTimeoutMs(long metricsTimeoutMs) { this.metricsTimeoutMs = metricsTimeoutMs; }
        public long getSnapshotTtlMs() { return snapshotTtlMs; }
        public void setSnapshotTtlMs(long snapshotTtlMs) { this.snapshotTtlMs = snapshotTtlMs; }
        public boolean isAutoFailoverEnabled() { return autoFailoverEnabled; }
        public void setAutoFailoverEnabled(boolean autoFailoverEnabled) { this.autoFailoverEnabled = autoFailoverEnabled; }
        public long getAutoFailoverPollIntervalMs() { return autoFailoverPollIntervalMs; }
        public void setAutoFailoverPollIntervalMs(long autoFailoverPollIntervalMs) { this.autoFailoverPollIntervalMs = autoFailoverPollIntervalMs; }
        public long getFailoverNodeCooldownMs() { return failoverNodeCooldownMs; }
        public void setFailoverNodeCooldownMs(long failoverNodeCooldownMs) { this.failoverNodeCooldownMs = failoverNodeCooldownMs; }
        public boolean isAutoRebalanceEnabled() { return autoRebalanceEnabled; }
        public void setAutoRebalanceEnabled(boolean autoRebalanceEnabled) { this.autoRebalanceEnabled = autoRebalanceEnabled; }
        public long getAutoRebalancePollIntervalMs() { return autoRebalancePollIntervalMs; }
        public void setAutoRebalancePollIntervalMs(long autoRebalancePollIntervalMs) { this.autoRebalancePollIntervalMs = autoRebalancePollIntervalMs; }
        public int getAutoRebalanceMinMoves() { return autoRebalanceMinMoves; }
        public void setAutoRebalanceMinMoves(int autoRebalanceMinMoves) { this.autoRebalanceMinMoves = autoRebalanceMinMoves; }
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
        private String internalHost;
        private int internalPort = 4432;

        public String getInternalToken() { return internalToken; }
        public void setInternalToken(String internalToken) { this.internalToken = internalToken; }
        public String getExternalHost() { return externalHost; }
        public void setExternalHost(String externalHost) { this.externalHost = externalHost; }
        public int getExternalPort() { return externalPort; }
        public void setExternalPort(int externalPort) { this.externalPort = externalPort; }
        public String getInternalHost() { return internalHost; }
        public void setInternalHost(String internalHost) { this.internalHost = internalHost; }
        public int getInternalPort() { return internalPort; }
        public void setInternalPort(int internalPort) { this.internalPort = internalPort; }
    }

    public static class DataPlaneConfig {
        private String kubeApiServer;
        private String kubeToken;
        private String kubeCaB64;
        private String namespace = "lakeon";
        private String computeNamespace = "lakeon-compute";

        public String getKubeApiServer() { return kubeApiServer; }
        public void setKubeApiServer(String kubeApiServer) { this.kubeApiServer = kubeApiServer; }
        public String getKubeToken() { return kubeToken; }
        public void setKubeToken(String kubeToken) { this.kubeToken = kubeToken; }
        public String getKubeCaB64() { return kubeCaB64; }
        public void setKubeCaB64(String kubeCaB64) { this.kubeCaB64 = kubeCaB64; }
        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
        public String getComputeNamespace() { return computeNamespace; }
        public void setComputeNamespace(String computeNamespace) { this.computeNamespace = computeNamespace; }
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
        private String controlCceClusterId = "";
        private String controlCceClusterName = "dbay-control-cce";
        private String dataCceClusterId = "";
        private String dataCceClusterName = "dbay-cce";
        private String rdsInstanceId = "";
        private String elbId = "";
        private String eipId = "";
        private String nodePoolId = "";
        private String publicApiElbId = "";
        private String controlPrivateElbIp = "";

        public String getResourcesFile() { return resourcesFile; }
        public void setResourcesFile(String resourcesFile) { this.resourcesFile = resourcesFile; }
        public List<String> getResourceIds() { return resourceIds; }
        public void setResourceIds(List<String> resourceIds) { this.resourceIds = resourceIds; }
        public String getConsoleRegion() { return consoleRegion; }
        public void setConsoleRegion(String consoleRegion) { this.consoleRegion = consoleRegion; }
        public String getCceClusterId() { return cceClusterId; }
        public void setCceClusterId(String cceClusterId) { this.cceClusterId = cceClusterId; }
        public String getControlCceClusterId() { return controlCceClusterId; }
        public void setControlCceClusterId(String controlCceClusterId) { this.controlCceClusterId = controlCceClusterId; }
        public String getControlCceClusterName() { return controlCceClusterName; }
        public void setControlCceClusterName(String controlCceClusterName) { this.controlCceClusterName = controlCceClusterName; }
        public String getDataCceClusterId() { return dataCceClusterId; }
        public void setDataCceClusterId(String dataCceClusterId) { this.dataCceClusterId = dataCceClusterId; }
        public String getDataCceClusterName() { return dataCceClusterName; }
        public void setDataCceClusterName(String dataCceClusterName) { this.dataCceClusterName = dataCceClusterName; }
        public String getRdsInstanceId() { return rdsInstanceId; }
        public void setRdsInstanceId(String rdsInstanceId) { this.rdsInstanceId = rdsInstanceId; }
        public String getElbId() { return elbId; }
        public void setElbId(String elbId) { this.elbId = elbId; }
        public String getEipId() { return eipId; }
        public void setEipId(String eipId) { this.eipId = eipId; }
        public String getNodePoolId() { return nodePoolId; }
        public void setNodePoolId(String nodePoolId) { this.nodePoolId = nodePoolId; }
        public String getPublicApiElbId() { return publicApiElbId; }
        public void setPublicApiElbId(String publicApiElbId) { this.publicApiElbId = publicApiElbId; }
        public String getControlPrivateElbIp() { return controlPrivateElbIp; }
        public void setControlPrivateElbIp(String controlPrivateElbIp) { this.controlPrivateElbIp = controlPrivateElbIp; }
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
        private String baseUrl = "https://api.modelarts-maas.com/openai/v1";
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

    public static class HwcloudConfig {
        private String accountId = "";
        private String accountName = "";

        public String getAccountId() { return accountId; }
        public void setAccountId(String accountId) { this.accountId = accountId; }
        public String getAccountName() { return accountName; }
        public void setAccountName(String accountName) { this.accountName = accountName; }
    }

    public static class OAuthConfig {
        private OAuthProviderConfig google = new OAuthProviderConfig();
        private OAuthProviderConfig github = new OAuthProviderConfig();
        private String callbackBaseUrl = "";
        private String relayBaseUrl = "";

        public OAuthProviderConfig getGoogle() { return google; }
        public void setGoogle(OAuthProviderConfig google) { this.google = google; }
        public OAuthProviderConfig getGithub() { return github; }
        public void setGithub(OAuthProviderConfig github) { this.github = github; }
        public String getCallbackBaseUrl() { return callbackBaseUrl; }
        public void setCallbackBaseUrl(String callbackBaseUrl) { this.callbackBaseUrl = callbackBaseUrl; }
        public String getRelayBaseUrl() { return relayBaseUrl; }
        public void setRelayBaseUrl(String relayBaseUrl) { this.relayBaseUrl = relayBaseUrl; }
    }

    public static class OAuthProviderConfig {
        private String clientId = "";
        private String clientSecret = "";

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    }

    public static class ComputeJwtConfig {
        /**
         * PEM-encoded RSA private key in PKCS#8 format. Newlines may be `\n`-escaped
         * when sourced from env (e.g., via helm Secret stringData). Keys produced by
         * {@code openssl genpkey -algorithm RSA -out priv.pem -pkeyopt rsa_keygen_bits:2048}
         * are PKCS#8 by default (header "BEGIN PRIVATE KEY"). Older {@code openssl genrsa}
         * produces PKCS#1 (header "BEGIN RSA PRIVATE KEY") which is NOT supported —
         * convert with {@code openssl pkcs8 -topk8 -nocrypt -in pkcs1.pem -out pkcs8.pem}.
         */
        private String privateKey = "";
        /** JWK JSON of the public key (single line). */
        private String publicJwk = "";
        /** Key ID embedded in the JWT header and JWKS entry. */
        private String kid = "lakeon-compute-1";
        /** Optional issuer claim. */
        private String issuer = "lakeon-api";
        /** Token TTL in seconds. */
        private int ttlSeconds = 300;

        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
        public String getPublicJwk() { return publicJwk; }
        public void setPublicJwk(String publicJwk) { this.publicJwk = publicJwk; }
        public String getKid() { return kid; }
        public void setKid(String kid) { this.kid = kid; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public int getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(int ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    }

    /**
     * Warm pool configuration. When enabled, lakeon-api maintains N idle
     * compute pods labeled `lakeon.io/pool=warm` in the lakeon-compute
     * namespace. Cold starts try to claim one (POST /configure with the
     * real spec, ~500ms reconfigure) before falling back to creating a
     * fresh Pod (~2s). Plan: docs/superpowers/plans/2026-05-16-compute-warm-pool.md
     */
    public static class ComputeWarmPoolConfig {
        private boolean enabled = false;
        private int size = 2;
        private String podLabelValue = "warm";
        private String mockTenantId = "";
        private String mockTimelineId = "";
        private String image = "";  // empty = use default compute image
        // 5s budget. First production warm-claim test (2026-05-16) showed real-tenant
        // reconfigure took ~1607ms — bigger than mock-tenant cold-init (382ms) because
        // /configure does basebackup + PG restart + apply spec for the new catalog.
        // 1.5s forced fallback to cold path; 5s gives 3x headroom.
        private int reconfigureTimeoutMs = 5000;
        /**
         * Port compute_ctl listens on inside each compute pod. Per Neon
         * convention this is 3080; only overridden in tests that target a
         * local HttpServer mock on a random port.
         */
        private int computeCtlPort = 3080;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
        public String getPodLabelValue() { return podLabelValue; }
        public void setPodLabelValue(String v) { this.podLabelValue = v; }
        public String getMockTenantId() { return mockTenantId; }
        public void setMockTenantId(String v) { this.mockTenantId = v; }
        public String getMockTimelineId() { return mockTimelineId; }
        public void setMockTimelineId(String v) { this.mockTimelineId = v; }
        public String getImage() { return image; }
        public void setImage(String v) { this.image = v; }
        public int getReconfigureTimeoutMs() { return reconfigureTimeoutMs; }
        public void setReconfigureTimeoutMs(int v) { this.reconfigureTimeoutMs = v; }
        public int getComputeCtlPort() { return computeCtlPort; }
        public void setComputeCtlPort(int v) { this.computeCtlPort = v; }
    }

}
