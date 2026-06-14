package com.lakeon.lakebasefs;

import org.springframework.stereotype.Component;

@Component
public class LakebaseFSLanceTableWorker implements LakebaseFSProcessingWorker {

    @Override
    public String processingProfile() {
        return LakebaseFSFolderProfile.PROCESSING_LANCE;
    }

    @Override
    public LakebaseFSProcessingResult process(LakebaseFSFolderEntity folder, LakebaseFSProcessingEvent event) {
        return LakebaseFSProcessingResult.done("lance table event accepted: " + event.path());
    }
}
