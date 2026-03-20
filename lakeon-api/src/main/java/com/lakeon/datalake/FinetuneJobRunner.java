package com.lakeon.datalake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class FinetuneJobRunner {
    private static final Logger log = LoggerFactory.getLogger(FinetuneJobRunner.class);

    private final RayJobRunner rayJobRunner;

    public FinetuneJobRunner(RayJobRunner rayJobRunner) {
        this.rayJobRunner = rayJobRunner;
    }

    /**
     * Start a fine-tuning job by injecting a Ray Train entrypoint template
     * and delegating to RayJobRunner.
     *
     * The user fills out a form (baseModel, datasetPath, outputPath, hyperparams, gpu)
     * instead of writing code. We generate the entrypoint and resource spec here.
     */
    public void start(DatalakeJobEntity job, DatalakeJobRequest req) {
        // 1. Build the generated entrypoint string from the template
        String entrypoint = buildFinetuneEntrypoint(req);

        // 2. Determine GPU image
        String imageKey = "ray-gpu";

        // 3. Clone/modify req to inject the generated values
        // IMPORTANT: Save the original spec first, restore it after delegation
        // We modify req fields temporarily to pass to RayJobRunner
        String originalEntrypoint = req.getEntrypoint();
        String originalImageKey = req.getImageKey();
        Map<String, Object> originalWorkers = req.getWorkers();

        try {
            // Inject finetune template values
            req.setEntrypoint(entrypoint);
            req.setImageKey(imageKey);

            // Map GPU config to worker spec (workers get the GPU)
            if (req.getGpu() != null) {
                int gpuCount = ((Number) req.getGpu().getOrDefault("count", 1)).intValue();
                Map<String, Object> workers = new java.util.LinkedHashMap<>();
                workers.put("count", gpuCount);
                workers.put("cpu", "4");
                workers.put("memory", "16Gi");
                req.setWorkers(workers);
            }

            // Delegate to RayJobRunner
            rayJobRunner.start(job, req);
        } finally {
            // ALWAYS restore original spec so entity.spec reflects what the user submitted
            req.setEntrypoint(originalEntrypoint);
            req.setImageKey(originalImageKey);
            req.setWorkers(originalWorkers);
        }
    }

    public void cancel(DatalakeJobEntity job) {
        rayJobRunner.cancel(job);
    }

    /**
     * Generate the Ray Train entrypoint script from finetune parameters.
     * This is the auto-generated script a user would otherwise write manually.
     */
    private String buildFinetuneEntrypoint(DatalakeJobRequest req) {
        String baseModel = req.getBaseModel() != null ? req.getBaseModel() : "Qwen2.5-7B";
        String datasetPath = req.getDatasetPath() != null ? req.getDatasetPath() : "";
        String outputPath = req.getOutputPath() != null ? req.getOutputPath() : "/tmp/output";

        Map<String, Object> hp = req.getHyperparams() != null ? req.getHyperparams() : Map.of();
        int epochs = ((Number) hp.getOrDefault("epochs", 3)).intValue();
        int batchSize = ((Number) hp.getOrDefault("batchSize", 4)).intValue();
        double learningRate = ((Number) hp.getOrDefault("learningRate", 2e-4)).doubleValue();
        int loraRank = ((Number) hp.getOrDefault("loraRank", 16)).intValue();

        return String.format(
            "python -c \"" +
            "import ray; ray.init();" +
            "from ray import train;" +
            "print('Finetune: model=%s dataset=%s output=%s epochs=%d batch=%d lr=%s lora=%d')" +
            "\"",
            baseModel, datasetPath, outputPath, epochs, batchSize, learningRate, loraRank
        );
        // Note: MVP uses a placeholder entrypoint. Full Ray Train template comes in Phase 2
        // when the actual training script infrastructure is ready.
    }
}
