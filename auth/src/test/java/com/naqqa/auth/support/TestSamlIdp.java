package com.naqqa.auth.support;

import com.naqqa.auth.sso.saml.SelfSignedCertificate;
import com.naqqa.sso.crypto.Pem;
import com.naqqa.sso.saml.Saml;
import com.naqqa.sso.saml.SamlBindings;
import com.naqqa.sso.saml.dsig.XmlSigner;
import com.naqqa.sso.saml.xml.XmlEscape;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TestSamlIdp {

    public final String entityId;
    public final KeyPair keys;
    public final X509Certificate certificate;

    public TestSamlIdp(String entityId) {
        this.entityId = entityId;
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            this.keys = g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        this.certificate = SelfSignedCertificate.rsa("test-idp", keys.getPublic(), keys.getPrivate());
    }

    public String pem() {
        try {
            return Pem.encode("CERTIFICATE", certificate.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class Spec {
        public String inResponseTo;
        public String destination;
        public String recipient;
        public String audience;
        public String issuer;
        public String nameId;
        public String nameIdFormat = Saml.NAMEID_PERSISTENT;
        public String authnContext = Saml.AC_PASSWORD_PROTECTED;
        public Map<String, List<String>> attributes = new LinkedHashMap<>();
        public boolean signAssertion = true;
        public boolean signResponse = false;
        public Instant now;
        public Duration lifetime = Duration.ofMinutes(5);
        public String assertionId = Saml.newId();
        public String responseId = Saml.newId();

        public Spec attribute(String name, String... values) {
            attributes.put(name, List.of(values));
            return this;
        }
    }

    public Spec spec(String spEntityId, String acs, String inResponseTo, String nameId, String email, Instant now) {
        Spec s = new Spec();
        s.inResponseTo = inResponseTo;
        s.destination = acs;
        s.recipient = acs;
        s.audience = spEntityId;
        s.issuer = entityId;
        s.nameId = nameId;
        s.now = now;
        if (email != null) {
            s.attribute("email", email);
        }
        return s;
    }

    static String esc(String v) {
        return XmlEscape.attribute(v);
    }

    public String assertionXml(Spec s) {
        Instant expires = s.now.plus(s.lifetime);
        StringBuilder a = new StringBuilder();
        a.append("<saml:Assertion xmlns:saml=\"").append(Saml.ASSERTION_NS).append("\" ID=\"").append(s.assertionId)
                .append("\" IssueInstant=\"").append(Saml.time(s.now)).append("\" Version=\"2.0\">");
        a.append("<saml:Issuer>").append(esc(s.issuer)).append("</saml:Issuer>");
        a.append("<saml:Subject><saml:NameID Format=\"").append(esc(s.nameIdFormat)).append("\">").append(esc(s.nameId))
                .append("</saml:NameID>");
        a.append("<saml:SubjectConfirmation Method=\"").append(Saml.CM_BEARER).append("\"><saml:SubjectConfirmationData");
        if (s.inResponseTo != null) {
            a.append(" InResponseTo=\"").append(esc(s.inResponseTo)).append("\"");
        }
        a.append(" NotOnOrAfter=\"").append(Saml.time(expires)).append("\" Recipient=\"").append(esc(s.recipient)).append("\"/>");
        a.append("</saml:SubjectConfirmation></saml:Subject>");
        a.append("<saml:Conditions NotBefore=\"").append(Saml.time(s.now.minusSeconds(5))).append("\" NotOnOrAfter=\"")
                .append(Saml.time(expires)).append("\"><saml:AudienceRestriction><saml:Audience>").append(esc(s.audience))
                .append("</saml:Audience></saml:AudienceRestriction></saml:Conditions>");
        a.append("<saml:AuthnStatement AuthnInstant=\"").append(Saml.time(s.now)).append("\" SessionIndex=\"s-1\">")
                .append("<saml:AuthnContext><saml:AuthnContextClassRef>").append(esc(s.authnContext))
                .append("</saml:AuthnContextClassRef></saml:AuthnContext></saml:AuthnStatement>");
        if (!s.attributes.isEmpty()) {
            a.append("<saml:AttributeStatement>");
            s.attributes.forEach((k, vs) -> {
                a.append("<saml:Attribute Name=\"").append(esc(k)).append("\">");
                for (String v : vs) {
                    a.append("<saml:AttributeValue>").append(esc(v)).append("</saml:AttributeValue>");
                }
                a.append("</saml:Attribute>");
            });
            a.append("</saml:AttributeStatement>");
        }
        a.append("</saml:Assertion>");
        String xml = a.toString();
        if (s.signAssertion) {
            xml = new String(new XmlSigner(keys.getPrivate(), certificate).sign(xml.getBytes(StandardCharsets.UTF_8), s.assertionId),
                    StandardCharsets.UTF_8);
        }
        return xml;
    }

    public String responseXml(Spec s, String assertion) {
        StringBuilder r = new StringBuilder();
        r.append("<samlp:Response xmlns:samlp=\"").append(Saml.PROTOCOL_NS).append("\" xmlns:saml=\"").append(Saml.ASSERTION_NS)
                .append("\" ID=\"").append(s.responseId).append("\" Version=\"2.0\" IssueInstant=\"").append(Saml.time(s.now)).append("\"");
        if (s.inResponseTo != null) {
            r.append(" InResponseTo=\"").append(esc(s.inResponseTo)).append("\"");
        }
        if (s.destination != null) {
            r.append(" Destination=\"").append(esc(s.destination)).append("\"");
        }
        r.append("><saml:Issuer>").append(esc(s.issuer)).append("</saml:Issuer>");
        r.append("<samlp:Status><samlp:StatusCode Value=\"").append(Saml.STATUS_SUCCESS).append("\"/></samlp:Status>");
        r.append(assertion).append("</samlp:Response>");
        String xml = r.toString();
        if (s.signResponse) {
            xml = new String(new XmlSigner(keys.getPrivate(), certificate).sign(xml.getBytes(StandardCharsets.UTF_8), s.responseId),
                    StandardCharsets.UTF_8);
        }
        return xml;
    }

    public String response(Spec s) {
        return encode(responseXml(s, assertionXml(s)));
    }

    public static String encode(String xml) {
        return SamlBindings.encodePost(xml.getBytes(StandardCharsets.UTF_8));
    }
}
