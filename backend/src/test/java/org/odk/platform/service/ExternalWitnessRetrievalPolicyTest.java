package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-only simulated, pinned TLS channel. No external network calls, trust
 * anchors, key activation, source answer read or Android data modifications.
 */
class ExternalWitnessRetrievalPolicyTest {
    private static final String PUBLISHER = "publisher.audit.example.org";
    private static final String WITNESS_ONE = "witness-one.audit.example.org";
    private static final String WITNESS_TWO = "witness-two.audit.example.org";

    private static ExternalWitnessRetrievalPolicy.Registry registry() {
        return new ExternalWitnessRetrievalPolicy.Registry(List.of(
                endpoint(ExternalWitnessRetrievalPolicy.Role.PUBLISHER,
                        PUBLISHER),
                endpoint(ExternalWitnessRetrievalPolicy.Role.WITNESS_A,
                        WITNESS_ONE),
                endpoint(ExternalWitnessRetrievalPolicy.Role.WITNESS_B,
                        WITNESS_TWO)));
    }

    private static ExternalWitnessRetrievalPolicy.Endpoint endpoint(
            ExternalWitnessRetrievalPolicy.Role role, String host) {
        String path = role == ExternalWitnessRetrievalPolicy.Role.PUBLISHER ?
                "/v1/ceremony-checkpoint" : "/v1/checkpoint-witness";
        return new ExternalWitnessRetrievalPolicy.Endpoint(
                role, URI.create("https://" + host + path));
    }

    private static InetAddress ip(int a, int b, int c, int d) throws Exception {
        return InetAddress.getByAddress(new byte[]{
                (byte)a, (byte)b, (byte)c, (byte)d});
    }

    private static ExternalWitnessRetrievalPolicy.TransportResponse good(
            ExternalWitnessRetrievalPolicy.PinnedTarget t) {
        return new ExternalWitnessRetrievalPolicy.TransportResponse(
                200, t.approvedUri(), t.validatedAddress(),
                t.approvedHostname(), false,
                "application/json", "identity",
                "{\"proof\":\"synthetic-unverified\"}".getBytes(
                    StandardCharsets.UTF_8));
    }

    private static ExternalWitnessRetrievalPolicy.FetchEvidence fetch(
            ExternalWitnessRetrievalPolicy.DnsResolver dns,
            ExternalWitnessRetrievalPolicy.PinnedTlsTransport transport) {
        return ExternalWitnessRetrievalPolicy.retrieve(
                registry(), ExternalWitnessRetrievalPolicy.Role.WITNESS_A,
                dns, transport);
    }

    @Test
    void exactApprovedHostAndPinnedIpYieldBytesOnlyWithoutAuthority()
            throws Exception {
        var data = fetch(
                host -> {
                    assertEquals(WITNESS_ONE, host);
                    return List.of(ip(1,1,1,1));
                },
                target -> {
                    assertEquals("https", target.approvedUri().getScheme());
                    assertEquals(WITNESS_ONE, target.approvedHostname());
                    assertArrayEquals(ip(1,1,1,1).getAddress(),
                            target.validatedAddress().getAddress());
                    assertEquals(443, target.port());
                    assertEquals(16384, target.maxBodyBytes());
                    assertEquals(3000, target.connectTimeoutMs());
                    assertEquals(5000, target.readTimeoutMs());
                    return good(target);
                });
        assertEquals("BOUNDED_TRANSPORT_EVIDENCE_ONLY_UNVERIFIED_NO_TRUST",
                data.state());
        assertEquals(WITNESS_ONE, data.approvedHost());
        assertEquals(32, data.body().length);
        assertTrue(data.remoteBodySha256().matches("[0-9a-f]{64}"));
        assertFalse(data.independentlyPublished());
        assertFalse(data.trustedKeyRegistered());
        assertFalse(data.canIssueGrant());
        assertFalse(data.canReadHistoricalAnswers());
        assertFalse(data.canTransfer());
        assertFalse(data.canSubmit());

        byte[] first = data.body();
        first[0] = (byte)'X';
        assertNotEquals(first[0], data.body()[0],
                "Retrieved bytes cannot be changed by mutating a getter copy");
    }

    @Test
    void onlyExactServerApprovedHttpsEndpointsWithThreeDistinctHosts()
            throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalWitnessRetrievalPolicy.Registry(
                    List.of(endpoint(
                        ExternalWitnessRetrievalPolicy.Role.PUBLISHER,
                        PUBLISHER))));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalWitnessRetrievalPolicy.Registry(List.of(
                    endpoint(ExternalWitnessRetrievalPolicy.Role.PUBLISHER,
                        PUBLISHER),
                    endpoint(ExternalWitnessRetrievalPolicy.Role.WITNESS_A,
                        WITNESS_ONE),
                    endpoint(ExternalWitnessRetrievalPolicy.Role.WITNESS_B,
                        WITNESS_ONE))));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalWitnessRetrievalPolicy.Registry(List.of(
                    endpoint(ExternalWitnessRetrievalPolicy.Role.PUBLISHER,
                        PUBLISHER),
                    endpoint(ExternalWitnessRetrievalPolicy.Role.WITNESS_A,
                        WITNESS_ONE),
                    endpoint(ExternalWitnessRetrievalPolicy.Role.WITNESS_A,
                        WITNESS_TWO))));

        for (String url : List.of(
                "http://witness-one.audit.example.org/v1/checkpoint-witness",
                "https://localhost/v1/checkpoint-witness",
                "https://127.0.0.1/v1/checkpoint-witness",
                "https://192.168.1.1/v1/checkpoint-witness",
                "https://witness-one.audit.example.org:8443/v1/checkpoint-witness",
                "https://witness-one.audit.example.org:443/v1/checkpoint-witness",
                "https://user:pw@witness-one.audit.example.org/v1/checkpoint-witness",
                "https://witness-one.audit.example.org/v1/checkpoint-witness?next=1",
                "https://witness-one.audit.example.org/v1/checkpoint-witness#fragment",
                "https://witness-one.audit.example.org/v1/../v1/checkpoint-witness",
                "https://witness-one.audit.example.org/v1/%63heckpoint-witness",
                "https://WITNESS-ONE.audit.example.org/v1/checkpoint-witness",
                "https://witness-one.audit.internal/v1/checkpoint-witness",
                "https://witness-one.audit.example.org/v1/ceremony-checkpoint",
                "https://witness-one.audit.example.org//v1/checkpoint-witness"
                )) {
            assertThrows(IllegalArgumentException.class, () ->
                new ExternalWitnessRetrievalPolicy.Endpoint(
                    ExternalWitnessRetrievalPolicy.Role.WITNESS_A,
                    URI.create(url)), url);
        }
    }

    @Test
    void privateSharedCloudMetadataAndDocumentationAddressDnsFailClosed()
            throws Exception {
        for (int[] address : List.of(
                new int[]{0,1,2,3},
                new int[]{10,0,0,1},
                new int[]{100,64,1,1},
                new int[]{127,0,0,1},
                new int[]{169,254,169,254},
                new int[]{172,16,0,1},
                new int[]{192,0,2,10},
                new int[]{192,168,0,1},
                new int[]{198,18,0,1},
                new int[]{198,51,100,1},
                new int[]{203,0,113,12},
                new int[]{224,0,0,1},
                new int[]{240,0,0,1}
                )) {
            InetAddress bad = ip(address[0],address[1],address[2],address[3]);
            assertFalse(ExternalWitnessRetrievalPolicy.globallyRoutable(bad),
                bad.getHostAddress());
            var result = fetch(host -> List.of(bad),
                target -> { fail("Cannot transport to special IPv4 address");
                    return good(target); });
            assertEquals("BLOCKED_PRIVATE_AMBIGUOUS_OR_INVALID_DNS",
                result.state(), bad.getHostAddress());
        }
        assertTrue(ExternalWitnessRetrievalPolicy.globallyRoutable(
                ip(1,1,1,1)));
        assertTrue(ExternalWitnessRetrievalPolicy.globallyRoutable(
                ip(8,8,8,8)));
        for (String address : List.of("::1", "fe80::1", "fc00::1",
                "2001:db8::1", "2002::1", "2001::1")) {
            InetAddress bad = InetAddress.getByName(address);
            assertFalse(ExternalWitnessRetrievalPolicy.globallyRoutable(bad),
                address);
        }
        assertTrue(ExternalWitnessRetrievalPolicy.globallyRoutable(
                InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void dnsCannotReturnMixedPrivateAddressesNullEmptyOrExcessiveResponses()
            throws Exception {
        var dnsCases = List.<List<InetAddress>>of(
                List.of(),
                List.of(ip(1,1,1,1), ip(10,0,0,1)),
                List.of(ip(8,8,8,8), ip(169,254,169,254)),
                java.util.Collections.nCopies(9, ip(1,1,1,1))
        );
        for (List<InetAddress> result : dnsCases) {
            assertEquals("BLOCKED_PRIVATE_AMBIGUOUS_OR_INVALID_DNS",
                fetch(host -> result,
                    target -> { fail("DNS failed closed");
                        return good(target); }).state());
        }
        assertEquals("BLOCKED_PRIVATE_AMBIGUOUS_OR_INVALID_DNS",
                fetch(host -> null,
                    target -> { fail("DNS null denied");
                        return good(target); }).state());
        assertEquals("BLOCKED_PRIVATE_AMBIGUOUS_OR_INVALID_DNS",
                fetch(host -> {throw new IllegalStateException("DNS failure");},
                    target -> good(target)).state());
    }

    @Test
    void noArbitraryUriRedirectDifferentTcpPeerOrUnverifiedTlsHost()
            throws Exception {
        var resolver = (ExternalWitnessRetrievalPolicy.DnsResolver)
                host -> List.of(ip(1,1,1,1));
        var blocked = List.<ExternalWitnessRetrievalPolicy.PinnedTlsTransport>of(
            t -> new ExternalWitnessRetrievalPolicy.TransportResponse(
                    301, t.approvedUri(), t.validatedAddress(),
                    t.approvedHostname(), false, "application/json",
                    "identity", "{}".getBytes(StandardCharsets.UTF_8)),
            t -> new ExternalWitnessRetrievalPolicy.TransportResponse(
                    200, URI.create("https://evil.remote.org/v1/checkpoint-witness"),
                    t.validatedAddress(), t.approvedHostname(), true,
                    "application/json", "identity",
                    "{}".getBytes(StandardCharsets.UTF_8)),
            t -> new ExternalWitnessRetrievalPolicy.TransportResponse(
                    200, t.approvedUri(), ip(8,8,8,8), t.approvedHostname(),
                    false, "application/json", "identity",
                    "{}".getBytes(StandardCharsets.UTF_8)),
            t -> new ExternalWitnessRetrievalPolicy.TransportResponse(
                    200, t.approvedUri(), t.validatedAddress(),
                    "other.verified.host", false, "application/json",
                    "identity", "{}".getBytes(StandardCharsets.UTF_8)),
            t -> new ExternalWitnessRetrievalPolicy.TransportResponse(
                    200, t.approvedUri(), t.validatedAddress(),
                    t.approvedHostname(), true, "application/json",
                    "identity", "{}".getBytes(StandardCharsets.UTF_8))
        );
        for (var transport : blocked) {
            assertEquals("BLOCKED_EXTERNAL_TRANSPORT_POLICY",
                fetch(resolver, transport).state());
        }
    }

    @Test
    void excessiveBodyWrongMediaTypeCompressionAndMissingEvidenceDenied()
            throws Exception {
        var dns = (ExternalWitnessRetrievalPolicy.DnsResolver)
                host -> List.of(ip(1,1,1,1));
        for (String type : List.of("text/html", "application/xml",
                "application/json; charset=utf-8", "text/plain")) {
            assertEquals("BLOCKED_EXTERNAL_TRANSPORT_POLICY",
                fetch(dns, t -> new ExternalWitnessRetrievalPolicy.TransportResponse(
                    200, t.approvedUri(), t.validatedAddress(),
                    t.approvedHostname(), false, type, "identity",
                    "{}".getBytes(StandardCharsets.UTF_8))).state());
        }
        for (String encoding : List.of("gzip", "deflate", "br")) {
            assertEquals("BLOCKED_EXTERNAL_TRANSPORT_POLICY",
                fetch(dns, t -> new ExternalWitnessRetrievalPolicy.TransportResponse(
                    200, t.approvedUri(), t.validatedAddress(),
                    t.approvedHostname(), false, "application/json", encoding,
                    "{}".getBytes(StandardCharsets.UTF_8))).state());
        }
        for (byte[] body : List.of(new byte[0], new byte[16385])) {
            assertEquals("BLOCKED_EXTERNAL_TRANSPORT_POLICY",
                fetch(dns, t -> new ExternalWitnessRetrievalPolicy.TransportResponse(
                    200, t.approvedUri(), t.validatedAddress(),
                    t.approvedHostname(), false,
                    "application/octet-stream", "identity", body)).state());
        }
        assertEquals("BLOCKED_EXTERNAL_TRANSPORT_POLICY",
                fetch(dns, t -> new ExternalWitnessRetrievalPolicy.TransportResponse(
                    200, t.approvedUri(), t.validatedAddress(),
                    t.approvedHostname(), false,
                    "application/json", "identity", null)).state());
        assertEquals("BLOCKED_EXTERNAL_TRANSPORT_UNAVAILABLE",
                fetch(dns, t -> {throw new IllegalStateException("TLS failed");})
                    .state());
        assertEquals("BLOCKED_EXTERNAL_TRANSPORT_POLICY",
                fetch(dns, t -> null).state());
    }

    @Test
    void missingTransportOrDnsRemainsInactive() throws Exception {
        assertEquals("BLOCKED_NO_SERVER_APPROVED_TRANSPORT",
                ExternalWitnessRetrievalPolicy.retrieve(registry(),
                    ExternalWitnessRetrievalPolicy.Role.WITNESS_A,
                    host -> List.of(ip(1,1,1,1)), null).state());
        assertEquals("BLOCKED_NO_SERVER_APPROVED_TRANSPORT",
                ExternalWitnessRetrievalPolicy.retrieve(registry(),
                    ExternalWitnessRetrievalPolicy.Role.WITNESS_A,
                    null, t -> good(t)).state());
        assertEquals("BLOCKED_NO_SERVER_APPROVED_TRANSPORT",
                ExternalWitnessRetrievalPolicy.retrieve(null,
                    ExternalWitnessRetrievalPolicy.Role.WITNESS_A,
                    host -> List.of(ip(1,1,1,1)), t -> good(t)).state());

        var denied = new ExternalWitnessRetrievalPolicy.FetchEvidence(
                ExternalWitnessRetrievalPolicy.Role.WITNESS_A,
                "BLOCKED", null, null, 0, null,
                false, false, false, false, false, false);
        assertFalse(denied.canTransfer());
        assertThrows(IllegalArgumentException.class, () ->
                new ExternalWitnessRetrievalPolicy.FetchEvidence(
                    ExternalWitnessRetrievalPolicy.Role.WITNESS_A,
                    "BOUNDED_TRANSPORT_EVIDENCE_ONLY_UNVERIFIED_NO_TRUST",
                    WITNESS_ONE, "f".repeat(64), 2,
                    new byte[]{1,2}, true, false, false, false, false, false));
        assertThrows(IllegalArgumentException.class, () ->
                new ExternalWitnessRetrievalPolicy.FetchEvidence(
                    ExternalWitnessRetrievalPolicy.Role.WITNESS_A,
                    "BOUNDED_TRANSPORT_EVIDENCE_ONLY_UNVERIFIED_NO_TRUST",
                    WITNESS_ONE, "f".repeat(64), 2,
                    new byte[]{1,2}, false, false, false, false, true, false));
    }
}
