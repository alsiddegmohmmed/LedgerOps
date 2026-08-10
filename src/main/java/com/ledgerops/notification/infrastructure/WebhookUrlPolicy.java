package com.ledgerops.notification.infrastructure;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

public final class WebhookUrlPolicy {

    private final boolean allowLocal;

    public WebhookUrlPolicy(boolean allowLocal) {
        this.allowLocal = allowLocal;
    }

    public URI validate(String value) {
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Webhook URL is not a valid URI", exception);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Webhook URL must have a host and no credentials, query, or fragment");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            if (!(allowLocal && "http".equalsIgnoreCase(uri.getScheme()) && isLocalHost(uri.getHost()))) {
                throw new IllegalArgumentException("Webhook URL must use HTTPS");
            }
        }
        if (!allowLocal && uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("Webhook URL must use the standard HTTPS port");
        }
        if (!allowLocal || !isLocalHost(uri.getHost())) {
            rejectResolvedAddresses(uri.getHost());
        }
        return uri;
    }

    public void validateResolvedHost(String host) {
        if (!allowLocal || !isLocalHost(host)) {
            rejectResolvedAddresses(host);
        }
    }

    private void rejectResolvedAddresses(String host) {
        try {
            List.of(InetAddress.getAllByName(host)).forEach(address -> {
                if (isProhibited(address)) {
                    throw new IllegalArgumentException("Webhook URL resolves to a prohibited network address");
                }
            });
        } catch (java.net.UnknownHostException exception) {
            throw new IllegalArgumentException("Webhook URL host cannot be resolved", exception);
        }
    }

    private boolean isProhibited(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || "169.254.169.254".equals(address.getHostAddress());
    }

    private boolean isLocalHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }
}
