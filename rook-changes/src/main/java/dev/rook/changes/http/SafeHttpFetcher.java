package dev.rook.changes.http;

import dev.rook.core.error.ErrorCode;
import dev.rook.core.error.RookException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;

public final class SafeHttpFetcher implements AutoCloseable {

    private static final String USER_AGENT = "rook-fetcher/0.1";

    private final FetchPolicy policy;
    private final CloseableHttpClient client;

    public SafeHttpFetcher(FetchPolicy policy) {
        this.policy = policy;
        DnsResolver resolver = new GuardedResolver(policy);
        PoolingHttpClientConnectionManager manager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(resolver)
                        .setDefaultSocketConfig(
                                SocketConfig.custom()
                                        .setSoTimeout(Timeout.of(policy.readTimeout()))
                                        .build())
                        .setDefaultConnectionConfig(
                                ConnectionConfig.custom()
                                        .setConnectTimeout(Timeout.of(policy.connectTimeout()))
                                        .setSocketTimeout(Timeout.of(policy.readTimeout()))
                                        .build())
                        .setMaxConnTotal(20)
                        .setMaxConnPerRoute(4)
                        .build();
        this.client =
                HttpClients.custom()
                        .setConnectionManager(manager)
                        .disableRedirectHandling()
                        .disableAutomaticRetries()
                        .disableCookieManagement()
                        .setUserAgent(USER_AGENT)
                        .build();
    }

    public FetchResult get(URI uri) {
        return get(uri, Map.of());
    }

    public FetchResult get(URI uri, Map<String, String> headers) {
        long deadline = System.nanoTime() + policy.totalTimeout().toNanos();
        URI current = uri;
        for (int hop = 0; hop <= policy.maxRedirects(); hop++) {
            validate(current);
            FetchResult result = fetchOnce(current, headers, deadline);
            if (!isRedirect(result.status())) {
                return result;
            }
            String location = result.headers().get("location");
            if (location == null || location.isBlank()) {
                throw new RookException(ErrorCode.UNAVAILABLE, "redirect without a location");
            }
            current = current.resolve(location);
        }
        throw new RookException(ErrorCode.UNAVAILABLE, "too many redirects");
    }

    void validate(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!policy.allowedSchemes().contains(scheme)) {
            throw RookException.invalid("scheme " + scheme + " is not allowed");
        }
        if (uri.getUserInfo() != null) {
            throw RookException.invalid("credentials in urls are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw RookException.invalid("url has no host");
        }
        if (!policy.allowPrivateAddresses()) {
            try {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (!AddressGuard.isPublic(address)) {
                        throw RookException.invalid("host resolves to a non public address");
                    }
                }
            } catch (UnknownHostException e) {
                throw new RookException(ErrorCode.UNAVAILABLE, "cannot resolve " + host, e);
            }
        }
    }

    private FetchResult fetchOnce(URI uri, Map<String, String> headers, long deadline) {
        HttpGet request = new HttpGet(uri);
        headers.forEach(request::addHeader);
        request.setConfig(
                RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(policy.readTimeout()))
                        .setRedirectsEnabled(false)
                        .build());
        try {
            return client.execute(request, response -> read(uri, response, deadline));
        } catch (RookException e) {
            throw e;
        } catch (IOException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RookException rook) {
                throw rook;
            }
            throw new RookException(ErrorCode.UNAVAILABLE, "fetch failed: " + e.getMessage(), e);
        }
    }

    private FetchResult read(URI uri, ClassicHttpResponse response, long deadline)
            throws IOException {
        Map<String, String> headers = new HashMap<>();
        for (Header header : response.getHeaders()) {
            headers.putIfAbsent(header.getName().toLowerCase(Locale.ROOT), header.getValue());
        }
        int status = response.getCode();
        HttpEntity entity = response.getEntity();
        byte[] body = new byte[0];
        if (entity != null && !isRedirect(status) && status != 304) {
            if (entity.getContentLength() > policy.maxBytes()) {
                throw tooLarge();
            }
            body = readBounded(entity.getContent(), deadline);
        }
        return new FetchResult(status, body, headers, uri);
    }

    private byte[] readBounded(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        try (in) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > policy.maxBytes()) {
                    throw tooLarge();
                }
                if (System.nanoTime() > deadline) {
                    throw new RookException(
                            ErrorCode.UNAVAILABLE, "fetch exceeded its time budget");
                }
                out.write(buffer, 0, read);
            }
        }
        return out.toByteArray();
    }

    private RookException tooLarge() {
        return new RookException(
                ErrorCode.PAYLOAD_TOO_LARGE,
                "response larger than " + policy.maxBytes() + " bytes");
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class GuardedResolver implements DnsResolver {

        private final FetchPolicy policy;

        GuardedResolver(FetchPolicy policy) {
            this.policy = policy;
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (!policy.allowPrivateAddresses()) {
                for (InetAddress address : addresses) {
                    if (!AddressGuard.isPublic(address)) {
                        throw new UnknownHostException(host + " resolves to a blocked address");
                    }
                }
            }
            return addresses;
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            return InetAddress.getByName(host).getCanonicalHostName();
        }
    }
}
