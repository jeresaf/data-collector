package org.odk.platform.service;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * M3D4C2B2B2C3: INACTIVE server-side boundary for strictly allowlisted
 * external checkpoint and independent witness-receipt retrieval.
 *
 * IMPORTANT: There is intentionally NO production network transport or
 * endpoint configuration. A future audited PinnedTlsTransport MUST connect
 * to the already validated IP address (NOT resolve the host again), enforce
 * TLS certificate validation/SNI/hostname, disable all redirects, bound
 * bytes WHILE streaming and return independent connection observations.
 *
 * Calling an ordinary HttpClient after pre-resolving DNS is NOT safe against
 * DNS rebinding. This class never calls such a client. No positive trust,
 * publication, audit anchor, grant or historical-answer read is provided.
 */
public final class ExternalWitnessRetrievalPolicy {
    private ExternalWitnessRetrievalPolicy() {}

    public enum Role { PUBLISHER, WITNESS_A, WITNESS_B }

    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int MAX_DNS_ADDRESSES = 8;
    public static final int CONNECT_TIMEOUT_MS = 3000;
    public static final int READ_TIMEOUT_MS = 5000;
    private static final Set<String> FORBIDDEN_TLDS =
            Set.of("localhost", "local", "internal", "test", "invalid", "example", "onion");
    private static final java.util.regex.Pattern HOST_LABEL =
            java.util.regex.Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Map<Role, String> EXACT_PATHS = Map.of(
            Role.PUBLISHER, "/v1/ceremony-checkpoint",
            Role.WITNESS_A, "/v1/checkpoint-witness",
            Role.WITNESS_B, "/v1/checkpoint-witness");

    /** Server-approved *exact* HTTPS resources, never request-selected URLs. */
    public record Endpoint(Role role, URI uri) {
        public Endpoint {
            if (role == null || uri == null || !validEndpoint(role, uri)) {
                throw new IllegalArgumentException(
                        "Only fixed server-allowlisted public HTTPS resources are valid");
            }
        }
    }

    /** Exactly one immutable URL per role on three different hostnames. */
    public record Registry(List<Endpoint> endpoints) {
        public Registry {
            if (endpoints == null || endpoints.size() != 3) {
                throw new IllegalArgumentException(
                        "Exactly one publisher and two witness URLs are required");
            }
            var roles = EnumSet.noneOf(Role.class);
            var hosts = new HashSet<String>();
            for (Endpoint e : endpoints) {
                if (e == null || !roles.add(e.role()) ||
                        !hosts.add(e.uri().getHost())) {
                    throw new IllegalArgumentException(
                            "Duplicate endpoint role or hostname");
                }
            }
            if (roles.size() != Role.values().length) {
                throw new IllegalArgumentException(
                        "Publisher and both external witnesses are required");
            }
            endpoints = List.copyOf(endpoints);
        }

        public Endpoint endpoint(Role role) {
            return endpoints.stream().filter(e -> e.role() == role)
                    .findFirst().orElseThrow();
        }
    }

    @FunctionalInterface
    public interface DnsResolver {
        List<InetAddress> resolve(String approvedHost) throws Exception;
    }

    /**
     * This is a *required* future transport contract, NOT an implemented
     * network client. The transport must connect to target.validatedAddress()
     * and never re-resolve target.approvedHostname(). It must verify that the
     * TLS certificate is valid for the ORIGINAL hostname (with SNI), enforce
     * certificate chain verification, disallow proxy/redirect/alternate-svc
     * and constrain all receive bytes before allocation.
     *
     * Evidence returned by an untrusted/mock transport is not proof of real
     * network/TLS verification, and cannot be used as security authority.
     */
    @FunctionalInterface
    public interface PinnedTlsTransport {
        TransportResponse get(PinnedTarget target) throws Exception;
    }

    public record PinnedTarget(
            Role role, URI approvedUri, String approvedHostname,
            InetAddress validatedAddress, int port,
            int maxBodyBytes, int connectTimeoutMs, int readTimeoutMs) {
        public PinnedTarget {
            if (role == null || approvedUri == null ||
                    !validEndpoint(role, approvedUri) ||
                    !approvedUri.getHost().equals(approvedHostname) ||
                    validatedAddress == null ||
                    !globallyRoutable(validatedAddress) || port != 443 ||
                    maxBodyBytes != MAX_BODY_BYTES ||
                    connectTimeoutMs != CONNECT_TIMEOUT_MS ||
                    readTimeoutMs != READ_TIMEOUT_MS) {
                throw new IllegalArgumentException("Unsafe pinned transport target");
            }
        }
    }

    /**
     * A transport MUST produce this evidence from its *actual* TLS channel,
     * not by copying requested values into the response.
     *
     * HTTP bodies can contain a JSON or binary signed checkpoint/receipt, but
     * NO parsing or Ed25519 signature verification is performed in this slice.
     */
    public record TransportResponse(
            int status, URI finalUri, InetAddress connectedAddress,
            String tlsVerifiedHostname, boolean redirected,
            String mediaType, String contentEncoding, byte[] body) {
        public TransportResponse {
            body = body == null ? null : body.clone();
        }
        @Override public byte[] body() {
            return body == null ? null : body.clone();
        }
    }

    /** Success is bounded untrusted input, NOT independent publication. */
    public record FetchEvidence(
            Role role, String state, String approvedHost,
            String remoteBodySha256, int bodyLength, byte[] body,
            boolean independentlyPublished, boolean trustedKeyRegistered,
            boolean canIssueGrant, boolean canReadHistoricalAnswers,
            boolean canTransfer, boolean canSubmit) {
        public FetchEvidence {
            body = body == null ? null : body.clone();
            if (independentlyPublished || trustedKeyRegistered ||
                    canIssueGrant || canReadHistoricalAnswers ||
                    canTransfer || canSubmit ||
                    (body != null && (body.length > MAX_BODY_BYTES ||
                        body.length == 0 || body.length != bodyLength ||
                        remoteBodySha256 == null ||
                        !state.equals("BOUNDED_TRANSPORT_EVIDENCE_ONLY_UNVERIFIED_NO_TRUST"))) {
                throw new IllegalArgumentException(
                        "Network bytes cannot establish independent publication or rights");
            }
        }
        @Override public byte[] body() {
            return body == null ? null : body.clone();
        }
    }

    /**
     * Restrictive preflight followed by an abstract TLS transport that MUST
     * dial the same pinned numeric IP. No live implementation is registered
     * or injected into a Spring controller. Never pass URLs from a user.
     */
    public static FetchEvidence retrieve(
            Registry approvedRegistry, Role role, DnsResolver resolver,
            PinnedTlsTransport transport) {
        if (role == null || approvedRegistry == null ||
                resolver == null || transport == null) {
            return blocked(role, "BLOCKED_NO_SERVER_APPROVED_TRANSPORT");
        }
        Endpoint endpoint;
        try {
            endpoint = approvedRegistry.endpoint(role);
        } catch (RuntimeException invalid) {
            return blocked(role, "BLOCKED_UNAPPROVED_EXTERNAL_ENDPOINT");
        }
        List<InetAddress> resolved;
        try {
            resolved = resolver.resolve(endpoint.uri().getHost());
            if (resolved == null || resolved.isEmpty() ||
                    resolved.size() > MAX_DNS_ADDRESSES ||
                    resolved.stream().anyMatch(ip -> !globallyRoutable(ip))) {
                return blocked(role, "BLOCKED_PRIVATE_AMBIGUOUS_OR_INVALID_DNS");
            }
        } catch (Exception invalidDns) {
            return blocked(role, "BLOCKED_PRIVATE_AMBIGUOUS_OR_INVALID_DNS");
        }
        // Deterministic first validated IP. A real transport MUST pin exactly
        // this InetAddress and use original approvedHost for TLS/SNI checks.
        InetAddress selected = resolved.get(0);
        PinnedTarget target = new PinnedTarget(role, endpoint.uri(),
                endpoint.uri().getHost(), selected, 443, MAX_BODY_BYTES,
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
        TransportResponse response;
        try {
            response = transport.get(target);
        } catch (Exception unavailable) {
            return blocked(role, "BLOCKED_EXTERNAL_TRANSPORT_UNAVAILABLE");
        }
        if (response == null || response.status() != 200 ||
                response.redirected() || response.finalUri() == null ||
                !response.finalUri().equals(target.approvedUri()) ||
                response.connectedAddress() == null ||
                !Arrays.equals(response.connectedAddress().getAddress(),
                        target.validatedAddress().getAddress()) ||
                !target.approvedHostname().equals(
                        response.tlsVerifiedHostname()) ||
                response.mediaType() == null ||
                !Set.of("application/json", "application/octet-stream")
                        .contains(response.mediaType()) ||
                response.contentEncoding() == null ||
                !"identity".equals(response.contentEncoding()) ||
                response.body() == null || response.body().length == 0 ||
                response.body().length > MAX_BODY_BYTES) {
            return blocked(role, "BLOCKED_EXTERNAL_TRANSPORT_POLICY");
        }
        byte[] bounded = response.body();
        return new FetchEvidence(role,
                "BOUNDED_TRANSPORT_EVIDENCE_ONLY_UNVERIFIED_NO_TRUST",
                target.approvedHostname(), sha256(bounded), bounded.length,
                bounded, false, false, false, false, false, false);
    }

    private static FetchEvidence blocked(Role role, String reason) {
        return new FetchEvidence(role, reason, null, null, 0, null,
                false, false, false, false, false, false);
    }

    private static boolean validEndpoint(Role role, URI url) {
        if (!"https".equals(url.getScheme()) || url.isOpaque() ||
                url.getRawAuthority() == null || url.getUserInfo() != null ||
                url.getRawQuery() != null || url.getRawFragment() != null ||
                url.getPort() != -1 || url.getHost() == null ||
                !EXACT_PATHS.get(role).equals(url.getRawPath())) {
            return false;
        }
        String host = url.getHost();
        if (host.length() > 253 || !host.equals(host.toLowerCase(Locale.ROOT)) ||
                host.startsWith(".") || host.endsWith(".") ||
                host.indexOf(':') >= 0 || host.indexOf('%') >= 0 ||
                url.getRawAuthority().contains("@") ||
                url.getRawAuthority().contains("\\")) {
            return false;
        }
        String[] labels = host.split("\\.", -1);
        if (labels.length < 3 ||
                !labels[labels.length - 1].matches("[a-z]{2,63}") ||
                FORBIDDEN_TLDS.contains(labels[labels.length - 1])) {
            return false;
        }
        for (String label : labels) {
            if (!HOST_LABEL.matcher(label).matches()) return false;
        }
        // Canonical exact URL only; no percent escapes or Unicode.
        return url.toASCIIString().equals("https://" + host +
                EXACT_PATHS.get(role));
    }

    /**
     * Deny by default for address families and well-known non-global spaces.
     * This is deliberately more restrictive than Java isSiteLocalAddress().
     * A real transport must ALSO enforce the current IANA special-purpose
     * registry and egress firewall; this standalone policy is not sufficient
     * operational network isolation.
     */
    static boolean globallyRoutable(InetAddress ip) {
        if (ip == null || ip.isAnyLocalAddress() ||
                ip.isLoopbackAddress() || ip.isLinkLocalAddress() ||
                ip.isSiteLocalAddress() || ip.isMulticastAddress()) {
            return false;
        }
        byte[] b = ip.getAddress();
        if (ip instanceof Inet4Address && b.length == 4) {
            long n = ((b[0] & 255L) << 24) | ((b[1] & 255L) << 16) |
                    ((b[2] & 255L) << 8) | (b[3] & 255L);
            // Exact special-purpose CIDRs: do not overblock entire
            // routable 192/8 or 198/8 networks.
            long[][] forbidden = {
                {0x00000000L,8}, {0x0a000000L,8},
                {0x64400000L,10}, {0x7f000000L,8},
                {0xa9fe0000L,16}, {0xac100000L,12},
                {0xc0000000L,24}, {0xc0000200L,24},
                {0xc0586300L,24}, {0xc0a80000L,16},
                {0xc6120000L,15}, {0xc6336400L,24},
                {0xcb007100L,24}, {0xe0000000L,4},
                {0xf0000000L,4}
            };
            for (long[] cidr : forbidden) {
                int bits = (int)cidr[1];
                long mask = (0xffffffffL << (32 - bits)) & 0xffffffffL;
                if ((n & mask) == cidr[0]) return false;
            }
            return true;
        }
        if (ip instanceof Inet6Address && b.length == 16) {
            // Unicast 2000::/3 only, exclude special 2001::/23,
            // documentation 2001:db8::/32, and embedded-v4 6to4 2002::/16.
            if ((b[0] & 0xe0) != 0x20) return false;
            if ((b[0] & 255) == 0x20 && (b[1] & 255) == 0x01 &&
                    (b[2] & 0xfe) == 0x00) return false;
            if ((b[0] & 255) == 0x20 && (b[1] & 255) == 0x01 &&
                    (b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8) return false;
            if ((b[0] & 255) == 0x20 && (b[1] & 255) == 0x02) return false;
            return true;
        }
        return false;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("SHA256 unavailable", unavailable);
        }
    }
}
