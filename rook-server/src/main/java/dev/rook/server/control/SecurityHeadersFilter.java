package dev.rook.server.control;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class SecurityHeadersFilter extends OncePerRequestFilter {

    private final String protectedPrefix;

    public SecurityHeadersFilter(String protectedPrefix) {
        this.protectedPrefix = protectedPrefix;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        if (request.getRequestURI().startsWith(protectedPrefix)) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader(
                    "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        }
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        chain.doFilter(request, response);
    }
}
