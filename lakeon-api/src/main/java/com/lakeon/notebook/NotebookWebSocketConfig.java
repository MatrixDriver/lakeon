package com.lakeon.notebook;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class NotebookWebSocketConfig implements WebSocketConfigurer {

    private final NotebookWebSocketHandler handler;

    public NotebookWebSocketConfig(NotebookWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/datalake/notebook/ws")
                .setAllowedOrigins("*");
    }
}
