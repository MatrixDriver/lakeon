package com.lakeon.controller;

import com.lakeon.service.TrialService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class TrialController {

    private final TrialService trialService;

    public TrialController(TrialService trialService) {
        this.trialService = trialService;
    }

    /**
     * Create a trial account with a temporary database.
     * No authentication required. Trial expires after 24 hours.
     */
    @PostMapping("/trial")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createTrial() {
        return trialService.createTrial();
    }
}
