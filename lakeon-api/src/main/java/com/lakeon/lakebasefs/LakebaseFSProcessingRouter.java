package com.lakeon.lakebasefs;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class LakebaseFSProcessingRouter {

    private final Map<String, LakebaseFSProcessingWorker> workers;

    public LakebaseFSProcessingRouter(List<LakebaseFSProcessingWorker> workers) {
        this.workers = workers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        LakebaseFSProcessingWorker::processingProfile,
                        Function.identity(),
                        (left, right) -> left));
    }

    public LakebaseFSProcessingResult dispatch(LakebaseFSFolderEntity folder, LakebaseFSProcessingEvent event) {
        if (folder == null || LakebaseFSFolderProfile.PROCESSING_NONE.equals(folder.getProcessingProfile())) {
            return LakebaseFSProcessingResult.done("processing skipped");
        }
        LakebaseFSProcessingWorker worker = workers.get(folder.getProcessingProfile());
        if (worker != null) {
            return worker.process(folder, event);
        }
        return LakebaseFSProcessingResult.retry("missing worker: " + folder.getProcessingProfile());
    }
}
