package dev.rook.server.control;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestSizeFilter extends OncePerRequestFilter {

    static final class TooLarge extends IOException {
        TooLarge() {
            super("request body is too large");
        }
    }

    private final long maxBytes;
    private final long webhookBytes;
    private final String webhookPrefix;
    private final MeterRegistry meters;

    public RequestSizeFilter(long maxBytes, MeterRegistry meters) {
        this(maxBytes, maxBytes, "/webhooks/", meters);
    }

    public RequestSizeFilter(
            long maxBytes, long webhookBytes, String webhookPrefix, MeterRegistry meters) {
        this.maxBytes = maxBytes;
        this.webhookBytes = webhookBytes;
        this.webhookPrefix = webhookPrefix;
        this.meters = meters;
    }

    private long limitFor(HttpServletRequest request) {
        return request.getRequestURI().startsWith(webhookPrefix) ? webhookBytes : maxBytes;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = limitFor(request);
        if (request.getContentLengthLong() > limit) {
            reject(response, limit);
            return;
        }
        try {
            chain.doFilter(new Limited(request, limit), response);
        } catch (ServletException | RuntimeException e) {
            if (causedByTooLarge(e) && !response.isCommitted()) {
                reject(response, limit);
                return;
            }
            throw e;
        } catch (TooLarge e) {
            if (!response.isCommitted()) {
                reject(response, limit);
                return;
            }
            throw e;
        }
    }

    private void reject(HttpServletResponse response, long limit) throws IOException {
        meters.counter("rook.http.rejected", "reason", "too_large").increment();
        Problems.write(
                response,
                HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "payload_too_large",
                "the request body is larger than " + limit + " bytes");
    }

    private static boolean causedByTooLarge(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TooLarge) {
                return true;
            }
        }
        return false;
    }

    private static final class Limited extends HttpServletRequestWrapper {

        private final long limit;

        Limited(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int value = delegate.read();
                    if (value >= 0) {
                        count(1);
                    }
                    return value;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int value = delegate.read(buffer, offset, length);
                    if (value > 0) {
                        count(value);
                    }
                    return value;
                }

                private void count(int bytes) throws IOException {
                    read += bytes;
                    if (read > limit) {
                        throw new TooLarge();
                    }
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
