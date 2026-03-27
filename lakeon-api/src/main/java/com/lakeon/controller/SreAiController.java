package com.lakeon.controller;

import com.lakeon.service.SreAiService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/v1/admin")
public class SreAiController {

    private final SreAiService sreAiService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public SreAiController(SreAiService sreAiService) {
        this.sreAiService = sreAiService;
    }

    @SuppressWarnings("unchecked")
    @PostMapping(value = "/ai/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2 min timeout

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.getOrDefault("messages", List.of());
        Map<String, String> context = (Map<String, String>) body.get("context");

        executor.execute(() -> sreAiService.chat(messages, context, emitter));

        return emitter;
    }
}
