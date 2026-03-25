package com.lakeon.datalake;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public class DatalakeJobRequest {

    private String name;

    private DatalakeJobType type;

    private String entrypoint;

    private String requirements;

    @JsonProperty("env_vars")
    private Map<String, String> envVars;

    private Map<String, String> resources;

    private Map<String, Object> head;

    private Map<String, Object> workers;

    @JsonProperty("timeout_seconds")
    private Integer timeoutSeconds;

    @JsonProperty("base_model")
    private String baseModel;

    @JsonProperty("dataset_path")
    private String datasetPath;

    @JsonProperty("output_path")
    private String outputPath;

    private Map<String, Object> hyperparams;

    private Map<String, Object> gpu;

    @JsonProperty("image_key")
    private String imageKey;

    @JsonProperty("input_dataset_ids")
    private java.util.List<String> inputDatasetIds;

    @JsonProperty("output_dataset_name")
    private String outputDatasetName;

    @JsonProperty("inline_script")
    private String inlineScript;

    @JsonProperty("retry_count")
    private int retryCount = 0;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public DatalakeJobType getType() {
        return type;
    }

    public void setType(DatalakeJobType type) {
        this.type = type;
    }

    public String getEntrypoint() {
        return entrypoint;
    }

    public void setEntrypoint(String entrypoint) {
        this.entrypoint = entrypoint;
    }

    public String getRequirements() {
        return requirements;
    }

    public void setRequirements(String requirements) {
        this.requirements = requirements;
    }

    public Map<String, String> getEnvVars() {
        return envVars;
    }

    public void setEnvVars(Map<String, String> envVars) {
        this.envVars = envVars;
    }

    public Map<String, String> getResources() {
        return resources;
    }

    public void setResources(Map<String, String> resources) {
        this.resources = resources;
    }

    public Map<String, Object> getHead() {
        return head;
    }

    public void setHead(Map<String, Object> head) {
        this.head = head;
    }

    public Map<String, Object> getWorkers() {
        return workers;
    }

    public void setWorkers(Map<String, Object> workers) {
        this.workers = workers;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getBaseModel() {
        return baseModel;
    }

    public void setBaseModel(String baseModel) {
        this.baseModel = baseModel;
    }

    public String getDatasetPath() {
        return datasetPath;
    }

    public void setDatasetPath(String datasetPath) {
        this.datasetPath = datasetPath;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public Map<String, Object> getHyperparams() {
        return hyperparams;
    }

    public void setHyperparams(Map<String, Object> hyperparams) {
        this.hyperparams = hyperparams;
    }

    public Map<String, Object> getGpu() {
        return gpu;
    }

    public void setGpu(Map<String, Object> gpu) {
        this.gpu = gpu;
    }

    public String getImageKey() {
        return imageKey;
    }

    public void setImageKey(String imageKey) {
        this.imageKey = imageKey;
    }

    public java.util.List<String> getInputDatasetIds() {
        return inputDatasetIds;
    }

    public void setInputDatasetIds(java.util.List<String> inputDatasetIds) {
        this.inputDatasetIds = inputDatasetIds;
    }

    public String getOutputDatasetName() {
        return outputDatasetName;
    }

    public void setOutputDatasetName(String outputDatasetName) {
        this.outputDatasetName = outputDatasetName;
    }

    public String getInlineScript() {
        return inlineScript;
    }

    public void setInlineScript(String inlineScript) {
        this.inlineScript = inlineScript;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }
}
