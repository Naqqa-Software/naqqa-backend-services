package com.naqqa.sso.saml;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.dsig.Dsig;
import com.naqqa.sso.saml.dsig.XmlEncryption;
import com.naqqa.sso.saml.dsig.XmlSignatureVerifier;
import com.naqqa.sso.saml.dsig.XmlSigner;
import com.naqqa.sso.saml.xml.XmlDocument;
import com.naqqa.sso.saml.xml.XmlElement;
import com.naqqa.sso.saml.xml.XmlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlSignatureTest {
    private static final String DOC = "<r:Root xmlns:r=\"urn:r\" xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\">"
            + "<r:Item ID=\"_item1\"><saml:Issuer>idp</saml:Issuer><r:Name>alice@x.com</r:Name><r:Note k=\"v\">t</r:Note></r:Item>"
            + "<r:Other ID=\"_other\"/></r:Root>";
    private final XmlParser parser = new XmlParser();
    private final XmlSigner signer = new XmlSigner(TestKeys.IDP.key(), TestKeys.IDP.cert());
    private final XmlSignatureVerifier verifier = new XmlSignatureVerifier(List.of(TestKeys.IDP.cert()));

    private String signed() {
        return new String(signer.sign(DOC.getBytes(StandardCharsets.UTF_8), "_item1"), StandardCharsets.UTF_8);
    }

    private XmlElement verify(String xml, String id) {
        XmlDocument d = parser.parse(xml);
        XmlElement target = d.elementsWithId(id).get(0);
        return verifier.verify(d, target);
    }

    private void rejects(String code, String xml) {
        SsoException e = assertThrows(SsoException.class, () -> verify(xml, "_item1"));
        assertEquals(code, e.code());
    }

    @Test
    @DisplayName("SSO-T-saml-046 sign and verify RSA-SHA256 returns the same element")
    void roundTrip() {
        String xml = signed();
        XmlDocument d = parser.parse(xml);
        XmlElement target = d.elementsWithId("_item1").get(0);
        assertSame(target, verifier.verify(d, target));
        assertTrue(target.childElements().get(1).is(Dsig.NS, "Signature"));
    }

    @Test
    @DisplayName("SSO-T-saml-047 ECDSA-SHA256 sign and verify")
    void ecdsa() {
        XmlSigner ec = new XmlSigner(TestKeys.IDP_EC.key(), TestKeys.IDP_EC.cert());
        String xml = new String(ec.sign(DOC.getBytes(StandardCharsets.UTF_8), "_item1"), StandardCharsets.UTF_8);
        assertTrue(xml.contains(Dsig.ECDSA_SHA256));
        XmlDocument d = parser.parse(xml);
        new XmlSignatureVerifier(List.of(TestKeys.IDP_EC.cert())).verify(d, d.elementsWithId("_item1").get(0));
    }

    @Test
    @DisplayName("SSO-T-saml-048 content tamper detected by digest")
    void digestTamper() {
        rejects("DSIG_DIGEST_MISMATCH", signed().replace("alice@x.com", "admin@x.com"));
        rejects("DSIG_DIGEST_MISMATCH", signed().replace("k=\"v\"", "k=\"w\""));
    }

    @Test
    @DisplayName("SSO-T-saml-049 signature value tamper detected")
    void signatureTamper() {
        String xml = signed();
        int i = xml.indexOf("<ds:SignatureValue>") + "<ds:SignatureValue>".length();
        char c = xml.charAt(i + 5);
        String t = xml.substring(0, i + 5) + (c == 'A' ? 'B' : 'A') + xml.substring(i + 6);
        rejects("DSIG_SIGNATURE_INVALID", t);
        String digestTamper = xml.replaceFirst("<ds:DigestValue>.", "<ds:DigestValue>" + (xml.contains("<ds:DigestValue>A") ? "B" : "A"));
        rejects("DSIG_SIGNATURE_INVALID", digestTamper);
    }

    @Test
    @DisplayName("SSO-T-saml-050 algorithm downgrade rsa-sha1, hmac-sha1, sha1 digest rejected")
    void downgrade() {
        rejects("DSIG_ALGORITHM", signed().replace(Dsig.RSA_SHA256, "http://www.w3.org/2000/09/xmldsig#rsa-sha1"));
        rejects("DSIG_ALGORITHM", signed().replace(Dsig.RSA_SHA256, "http://www.w3.org/2000/09/xmldsig#hmac-sha1"));
        rejects("DSIG_ALGORITHM", signed().replace(Dsig.RSA_SHA256, "http://www.w3.org/2001/04/xmldsig-more#hmac-sha256"));
        rejects("DSIG_ALGORITHM", signed().replace(Dsig.SHA256, "http://www.w3.org/2000/09/xmldsig#sha1"));
        rejects("DSIG_ALGORITHM", signed().replace("<ds:CanonicalizationMethod Algorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"",
                "<ds:CanonicalizationMethod Algorithm=\"http://www.w3.org/TR/2001/REC-xml-c14n-20010315\""));
    }

    @Test
    @DisplayName("SSO-T-saml-051 HMACOutputLength truncation rejected")
    void hmacOutputLength() {
        rejects("DSIG_MALFORMED", signed().replace("<ds:SignatureMethod Algorithm=\"" + Dsig.RSA_SHA256 + "\"/>",
                "<ds:SignatureMethod Algorithm=\"" + Dsig.RSA_SHA256 + "\"><ds:HMACOutputLength>8</ds:HMACOutputLength></ds:SignatureMethod>"));
    }

    @Test
    @DisplayName("SSO-T-saml-052 attacker signature with attacker KeyInfo certificate rejected")
    void attackerKeyInfo() {
        XmlSigner attacker = new XmlSigner(TestKeys.ATTACKER.key(), TestKeys.ATTACKER.cert());
        String forged = new String(attacker.sign(DOC.replace("alice", "admin").getBytes(StandardCharsets.UTF_8), "_item1"),
                StandardCharsets.UTF_8);
        rejects("DSIG_SIGNATURE_INVALID", forged);
    }

    @Test
    @DisplayName("SSO-T-saml-053 KeyInfo ignored for trust: trusted signature with foreign KeyInfo still verifies")
    void keyInfoIgnored() throws Exception {
        String xml = signed();
        String idpCert = Dsig.base64(TestKeys.IDP.cert().getEncoded());
        String attackerCert = Dsig.base64(TestKeys.ATTACKER.cert().getEncoded());
        verify(xml.replace(idpCert, attackerCert), "_item1");
        verify(xml.replaceAll("<ds:KeyInfo>.*</ds:KeyInfo>", ""), "_item1");
    }

    @Test
    @DisplayName("SSO-T-saml-054 reference to another element rejected")
    void referenceOther() {
        rejects("DSIG_REFERENCE_MISMATCH", signed().replace("URI=\"#_item1\"", "URI=\"#_other\""));
        rejects("DSIG_REFERENCE_MISMATCH", signed().replace("URI=\"#_item1\"", "URI=\"\""));
        rejects("DSIG_REFERENCE_MISMATCH", signed().replace("URI=\"#_item1\"", "URI=\"#xpointer(/)\""));
    }

    @Test
    @DisplayName("SSO-T-saml-055 duplicate ID anywhere in document rejected")
    void duplicateId() {
        rejects("DSIG_DUPLICATE_ID", signed().replace("<r:Other ID=\"_other\"/>", "<r:Other ID=\"_item1\"/>"));
        rejects("DSIG_DUPLICATE_ID", signed().replace("<r:Other ID=\"_other\"/>", "<r:Other><r:Deep Id=\"_item1\"/></r:Other>"));
    }

    @Test
    @DisplayName("SSO-T-saml-056 signature must be a direct child of the trusted element")
    void signaturePlacement() {
        String xml = signed();
        int s = xml.indexOf("<ds:Signature");
        int e = xml.indexOf("</ds:Signature>") + "</ds:Signature>".length();
        String sig = xml.substring(s, e);
        String moved = xml.substring(0, s) + xml.substring(e);
        moved = moved.replace("<r:Other ID=\"_other\"/>", "<r:Other ID=\"_other\">" + sig + "</r:Other>");
        rejects("DSIG_SIGNATURE_MISSING", moved);
        String nested = xml.substring(0, s) + "<r:Wrap>" + sig + "</r:Wrap>" + xml.substring(e);
        rejects("DSIG_SIGNATURE_MISSING", nested);
        rejects("DSIG_MULTIPLE_SIGNATURES", xml.substring(0, e) + sig + xml.substring(e));
    }

    @Test
    @DisplayName("SSO-T-saml-057 multiple references and Object elements rejected")
    void structure() {
        String xml = signed();
        int rs = xml.indexOf("<ds:Reference");
        int re = xml.indexOf("</ds:Reference>") + "</ds:Reference>".length();
        rejects("DSIG_REFERENCE_COUNT", xml.substring(0, re) + xml.substring(rs, re) + xml.substring(re));
        rejects("DSIG_MALFORMED", xml.replace("</ds:Signature>", "<ds:Object><r:Item ID=\"_x\"/></ds:Object></ds:Signature>"));
        rejects("DSIG_MALFORMED", xml.replace("<ds:SignedInfo>", "<ds:SignedInfo>junk"));
    }

    @Test
    @DisplayName("SSO-T-saml-058 only enveloped-signature and exc-c14n transforms allowed")
    void transforms() {
        String xml = signed();
        rejects("DSIG_TRANSFORM", xml.replace("<ds:Transforms>",
                "<ds:Transforms><ds:Transform Algorithm=\"http://www.w3.org/TR/1999/REC-xpath-19991116\"/>"));
        rejects("DSIG_TRANSFORM", xml.replace("<ds:Transform Algorithm=\"" + "http://www.w3.org/2000/09/xmldsig#enveloped-signature" + "\"/>", ""));
        rejects("DSIG_TRANSFORM", xml.replace("<ds:Transforms>",
                "<ds:Transforms><ds:Transform Algorithm=\"http://www.w3.org/TR/1999/REC-xslt-19991116\"/>"));
    }

    @Test
    @DisplayName("SSO-T-saml-059 comment injection keeps signature valid and text is read in full")
    void commentInjection() {
        String xml = signed().replace("alice@x.com", "alice@x.com<!---->.evil.com");
        String base = new String(signer.sign(DOC.replace("alice@x.com", "alice@x.com.evil.com")
                .getBytes(StandardCharsets.UTF_8), "_item1"), StandardCharsets.UTF_8);
        String injected = base.replace("alice@x.com.evil.com", "alice@x.com<!---->.evil.com");
        XmlElement verified = verify(injected, "_item1");
        assertEquals("alice@x.com.evil.com", verified.childElements().get(2).text());
        rejects("DSIG_DIGEST_MISMATCH", xml);
    }

    @Test
    @DisplayName("SSO-T-saml-060 weak or unsupported trusted keys refused")
    void weakKeys() {
        SsoException e = assertThrows(SsoException.class,
                () -> new XmlSignatureVerifier(List.of(TestKeys.rsa("weak", 1024).cert())));
        assertEquals("DSIG_WEAK_KEY", e.code());
        e = assertThrows(SsoException.class, () -> new XmlSignatureVerifier(List.of()));
        assertEquals("DSIG_NO_TRUSTED_KEYS", e.code());
    }

    @Test
    @DisplayName("SSO-T-saml-061 element from another document refused")
    void foreignElement() {
        XmlDocument a = parser.parse(signed());
        XmlDocument b = parser.parse(signed());
        SsoException e = assertThrows(SsoException.class, () -> verifier.verify(a, b.elementsWithId("_item1").get(0)));
        assertEquals("DSIG_FOREIGN_ELEMENT", e.code());
    }

    @Test
    @DisplayName("SSO-T-saml-062 signature with multiple trusted keys selects the right one")
    void multipleTrusted() {
        XmlSignatureVerifier v = new XmlSignatureVerifier(List.of(TestKeys.ATTACKER.cert(), TestKeys.IDP_EC.cert(), TestKeys.IDP.cert()));
        XmlDocument d = parser.parse(signed());
        v.verify(d, d.elementsWithId("_item1").get(0));
    }

    @Test
    @DisplayName("SSO-T-saml-063 signing already signed element refused, signature placed after Issuer")
    void signerRules() {
        String xml = signed();
        SsoException e = assertThrows(SsoException.class, () -> signer.sign(xml.getBytes(StandardCharsets.UTF_8), "_item1"));
        assertEquals("DSIG_ALREADY_SIGNED", e.code());
        assertTrue(xml.indexOf("</saml:Issuer><ds:Signature") > 0);
        e = assertThrows(SsoException.class, () -> signer.sign(DOC.getBytes(StandardCharsets.UTF_8), "_missing"));
        assertEquals("DSIG_TARGET_NOT_FOUND", e.code());
    }

    @Test
    @DisplayName("SSO-T-saml-064 AES-256-GCM with RSA-OAEP-256 encrypt and decrypt")
    void encryption() {
        byte[] plain = "<a>secret</a>".getBytes(StandardCharsets.UTF_8);
        String enc = XmlEncryption.encrypt(plain, TestKeys.SP_ENC.cert().getPublicKey());
        XmlElement wrapper = parser.parse("<w>" + enc + "</w>").root();
        assertArrayEquals(plain, XmlEncryption.decrypt(wrapper, TestKeys.SP_ENC.key()));
        SsoException e = assertThrows(SsoException.class, () -> XmlEncryption.decrypt(wrapper, TestKeys.SP.key()));
        assertEquals("XENC_DECRYPT_FAILED", e.code());
        XmlElement cbc = parser.parse("<w>" + enc.replace(XmlEncryption.AES256_GCM, "http://www.w3.org/2001/04/xmlenc#aes256-cbc") + "</w>").root();
        e = assertThrows(SsoException.class, () -> XmlEncryption.decrypt(cbc, TestKeys.SP_ENC.key()));
        assertEquals("XENC_ALGORITHM", e.code());
        XmlElement pkcs1 = parser.parse("<w>" + enc.replace(XmlEncryption.RSA_OAEP, "http://www.w3.org/2001/04/xmlenc#rsa-1_5") + "</w>").root();
        e = assertThrows(SsoException.class, () -> XmlEncryption.decrypt(pkcs1, TestKeys.SP_ENC.key()));
        assertEquals("XENC_ALGORITHM", e.code());
    }

    @Test
    @DisplayName("SSO-T-saml-065 ciphertext tamper detected by GCM tag")
    void encryptionTamper() {
        String enc = XmlEncryption.encrypt("<a>secret</a>".getBytes(StandardCharsets.UTF_8), TestKeys.SP_ENC.cert().getPublicKey());
        int last = enc.lastIndexOf("<xenc:CipherValue>") + "<xenc:CipherValue>".length() + 20;
        String t = enc.substring(0, last) + (enc.charAt(last) == 'A' ? 'B' : 'A') + enc.substring(last + 1);
        XmlElement wrapper = parser.parse("<w>" + t + "</w>").root();
        SsoException e = assertThrows(SsoException.class, () -> XmlEncryption.decrypt(wrapper, TestKeys.SP_ENC.key()));
        assertEquals("XENC_DECRYPT_FAILED", e.code());
    }
}
