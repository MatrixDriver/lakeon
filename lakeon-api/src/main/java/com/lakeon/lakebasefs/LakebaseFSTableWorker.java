package com.lakeon.lakebasefs;

import org.springframework.stereotype.Component;

@Component
public class LakebaseFSTableWorker implements LakebaseFSProcessingWorker {

    @Override
    public String processingProfile() {
        return LakebaseFSFolderProfile.PROCESSING_ICEBERG;
    }

    @Override
    public LakebaseFSProcessingResult process(LakebaseFSFolderEntity folder, LakebaseFSProcessingEvent event) {
        String profile = folder.getProcessingProfile();
        if (LakebaseFSFolderProfile.PROCESSING_ICEBERG.equals(profile)) {
            return LakebaseFSProcessingResult.done("iceberg table event accepted: " + event.path());
        }
        if (LakebaseFSFolderProfile.PROCESSING_LANCE.equals(profile)) {
            return LakebaseFSProcessingResult.done("lance table event accepted: " + event.path());
        }
        return LakebaseFSProcessingResult.retry("unsupported table profile: " + profile);
    }
}
