package com.lakeon.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.SecureRandom;

@Component
@Order(-1)
public class RequestContextFilter implements Filter {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String requestId = "req_" + String.format("%08x", RANDOM.nextInt());
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);

        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
        }
    }
}
