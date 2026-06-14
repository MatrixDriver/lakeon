package com.lakeon.lakebasefs;

public interface LakebaseFSProcessingWorker {
    String processingProfile();
    LakebaseFSProcessingResult process(LakebaseFSFolderEntity folder, LakebaseFSProcessingEvent event);
}
