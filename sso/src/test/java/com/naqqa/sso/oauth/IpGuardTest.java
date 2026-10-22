package com.naqqa.sso.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpGuardTest {
    private static boolean blocked(String ip) throws Exception {
        return IpGuard.isBlocked(InetAddress.getByName(ip));
    }

    @Test
    @DisplayName("SSO-T-oauth-080 loopback 127.0.0.0/8 blocked")
    void loopback() throws Exception {
        assertTrue(blocked("127.0.0.1"));
        assertTrue(blocked("127.255.255.254"));
    }

    @Test
    @DisplayName("SSO-T-oauth-081 RFC1918 ranges blocked")
    void rfc1918() throws Exception {
        assertTrue(blocked("10.0.0.1"));
        assertTrue(blocked("172.16.0.1"));
        assertTrue(blocked("172.31.255.255"));
        assertTrue(blocked("192.168.1.1"));
        assertFalse(blocked("172.32.0.1"));
        assertFalse(blocked("172.15.0.1"));
    }

    @Test
    @DisplayName("SSO-T-oauth-082 link-local and cloud metadata address blocked")
    void linkLocal() throws Exception {
        assertTrue(blocked("169.254.169.254"));
        assertTrue(blocked("169.254.0.1"));
    }

    @Test
    @DisplayName("SSO-T-oauth-083 CGNAT 100.64.0.0/10 blocked, edges allowed")
    void cgnat() throws Exception {
        assertTrue(blocked("100.64.0.1"));
        assertTrue(blocked("100.127.255.255"));
        assertFalse(blocked("100.63.255.255"));
        assertFalse(blocked("100.128.0.1"));
    }

    @Test
    @DisplayName("SSO-T-oauth-084 multicast, reserved and any-local blocked")
    void multicastReserved() throws Exception {
        assertTrue(blocked("224.0.0.1"));
        assertTrue(blocked("239.255.255.250"));
        assertTrue(blocked("240.0.0.1"));
        assertTrue(blocked("255.255.255.255"));
        assertTrue(blocked("0.0.0.0"));
    }

    @Test
    @DisplayName("SSO-T-oauth-085 documentation and benchmark ranges blocked")
    void documentation() throws Exception {
        assertTrue(blocked("192.0.2.1"));
        assertTrue(blocked("198.51.100.1"));
        assertTrue(blocked("203.0.113.1"));
        assertTrue(blocked("198.18.0.1"));
    }

    @Test
    @DisplayName("SSO-T-oauth-086 public IPv4 addresses allowed")
    void publicV4() throws Exception {
        assertFalse(blocked("8.8.8.8"));
        assertFalse(blocked("142.250.74.14"));
        assertFalse(blocked("17.253.144.10"));
    }

    @Test
    @DisplayName("SSO-T-oauth-087 IPv6 loopback and unspecified blocked")
    void v6Loopback() throws Exception {
        assertTrue(blocked("::1"));
        assertTrue(blocked("::"));
    }

    @Test
    @DisplayName("SSO-T-oauth-088 IPv6 ULA fc00::/7 and link-local fe80::/10 blocked")
    void v6UlaLinkLocal() throws Exception {
        assertTrue(blocked("fc00::1"));
        assertTrue(blocked("fd12:3456::1"));
        assertTrue(blocked("fe80::1"));
        assertTrue(blocked("fec0::1"));
    }

    @Test
    @DisplayName("SSO-T-oauth-089 IPv6 multicast blocked")
    void v6Multicast() throws Exception {
        assertTrue(blocked("ff02::1"));
    }

    @Test
    @DisplayName("SSO-T-oauth-090 IPv4-mapped IPv6 to private blocked")
    void v4Mapped() throws Exception {
        assertTrue(blocked("::ffff:10.0.0.1"));
        assertTrue(blocked("::ffff:127.0.0.1"));
        assertTrue(blocked("::ffff:169.254.169.254"));
    }

    @Test
    @DisplayName("SSO-T-oauth-091 IPv4-mapped bytes form of a public address is still blocked when kept as IPv6")
    void v4MappedRawBytes() throws Exception {
        byte[] raw = new byte[16];
        raw[10] = (byte) 0xff;
        raw[11] = (byte) 0xff;
        raw[12] = 8;
        raw[13] = 8;
        raw[14] = 8;
        raw[15] = 8;
        assertTrue(IpGuard.blockedV6(raw));
    }

    @Test
    @DisplayName("SSO-T-oauth-092 NAT64 and 6to4 embedding private IPv4 blocked")
    void embedded() throws Exception {
        assertTrue(blocked("64:ff9b::a00:1"));
        assertTrue(blocked("2002:a00:1::1"));
        assertTrue(blocked("2002:7f00:1::1"));
    }

    @Test
    @DisplayName("SSO-T-oauth-093 Teredo and documentation IPv6 blocked, global unicast allowed")
    void v6Special() throws Exception {
        assertTrue(blocked("2001:0:4136:e378:8000:63bf:3fff:fdd2"));
        assertTrue(blocked("2001:db8::1"));
        assertFalse(blocked("2606:4700:4700::1111"));
        assertFalse(blocked("2a00:1450:4001:81b::200e"));
    }
}
