package com.naqqa.sso.saml.sp;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.ReplayCache;
import com.naqqa.sso.saml.Saml;
import com.naqqa.sso.saml.SamlBindings;
import com.naqqa.sso.saml.dsig.Dsig;
import com.naqqa.sso.saml.dsig.XmlEncryption;
import com.naqqa.sso.saml.dsig.XmlSignatureVerifier;
import com.naqqa.sso.saml.dsig.XmlSigner;
import com.naqqa.sso.saml.xml.XmlDocument;
import com.naqqa.sso.saml.xml.XmlElement;
import com.naqqa.sso.saml.xml.XmlEscape;
import com.naqqa.sso.saml.xml.XmlNode;
import com.naqqa.sso.saml.xml.XmlParser;
import com.naqqa.sso.saml.xml.XmlText;
import com.naqqa.sso.saml.xml.XmlWriter;

import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SamlSp {
    private final SamlSpConfig config;
    private final SamlRequestStore requests;
    private final ReplayCache replayCache;
    private final Clock clock;
    private final XmlParser parser;
    private final XmlSignatureVerifier verifier;
    private final XmlSigner signer;

    public SamlSp(SamlSpConfig config, SamlRequestStore requests, ReplayCache replayCache, Clock clock) {
        this.config = config;
        this.requests = requests;
        this.replayCache = replayCache;
        this.clock = clock;
        this.parser = new XmlParser(config.limits());
        this.verifier = new XmlSignatureVerifier(config.idpCertificates());
        this.signer = config.signingKey() == null ? null : new XmlSigner(config.signingKey(), config.signingCertificate());
    }

    public OutboundAuthnRequest createAuthnRequest(String relayState) {
        if (config.idpSsoUrl() == null) {
            throw new SsoException("SAML_SP_CONFIG");
        }
        String id = Saml.newId();
        Instant now = clock.instant();
        XmlWriter w = new XmlWriter();
        w.start("samlp:AuthnRequest").attr("xmlns:samlp", Saml.PROTOCOL_NS).attr("xmlns:saml", Saml.ASSERTION_NS)
                .attr("ID", id).attr("Version", "2.0").attr("IssueInstant", Saml.time(now))
                .attr("Destination", config.idpSsoUrl())
                .attr("AssertionConsumerServiceURL", config.assertionConsumerServiceUrl())
                .attr("ProtocolBinding", Saml.BINDING_POST);
        w.element("saml:Issuer", config.entityId());
        w.start("samlp:NameIDPolicy").attr("AllowCreate", "true").end();
        w.end();
        String url = SamlBindings.encodeRedirect(config.idpSsoUrl(), "SAMLRequest",
                w.toString().getBytes(StandardCharsets.UTF_8), relayState, signer);
        requests.save(id, now.plus(config.requestLifetime()));
        return new OutboundAuthnRequest(id, url);
    }

    public SamlPrincipal verifyResponse(String samlResponse, String expectedRequestId) {
        Instant now = clock.instant();
        XmlDocument doc = parser.parse(SamlBindings.decodePost(samlResponse, config.limits().maxBytes()));
        XmlElement response = doc.root();
        if (!response.is(Saml.PROTOCOL_NS, "Response")) {
            throw new SsoException("SAML_UNEXPECTED_MESSAGE");
        }
        boolean responseSigned = false;
        if (!response.childElements(Dsig.NS, "Signature").isEmpty()) {
            response = verifier.verify(doc, response);
            responseSigned = true;
        } else if (config.requireSignedResponse()) {
            throw new SsoException("SAML_SIGNATURE_REQUIRED");
        }
        if (!"2.0".equals(response.attribute("Version"))) {
            throw new SsoException("SAML_VERSION");
        }
        Saml.requireId(response.attribute("ID"));
        Instant issued = Saml.parseTime(Saml.requiredAttribute(response, "IssueInstant"));
        if (issued.isAfter(now.plus(config.clockSkew()))) {
            throw new SsoException("SAML_NOT_YET_VALID");
        }
        if (issued.isBefore(now.minus(config.requestLifetime()).minus(config.clockSkew()))) {
            throw new SsoException("SAML_EXPIRED");
        }
        String destination = response.attribute("Destination");
        if (destination != null ? !destination.equals(config.assertionConsumerServiceUrl()) : responseSigned) {
            throw new SsoException("SAML_DESTINATION");
        }
        XmlElement responseIssuer = response.optionalChild(Saml.ASSERTION_NS, "Issuer");
        if (responseIssuer != null) {
            checkIssuer(responseIssuer);
        }
        checkInResponseTo(response.attribute("InResponseTo"), expectedRequestId);
        XmlElement statusCode = response.requiredChild(Saml.PROTOCOL_NS, "Status")
                .requiredChild(Saml.PROTOCOL_NS, "StatusCode");
        if (!Saml.STATUS_SUCCESS.equals(statusCode.attribute("Value"))) {
            throw new SsoException("SAML_STATUS_NOT_SUCCESS");
        }
        int total = 0;
        for (XmlElement e : doc.elements()) {
            if (e.is(Saml.ASSERTION_NS, "Assertion") || e.is(Saml.ASSERTION_NS, "EncryptedAssertion")) {
                total++;
            }
        }
        List<XmlElement> plain = response.childElements(Saml.ASSERTION_NS, "Assertion");
        List<XmlElement> encrypted = response.childElements(Saml.ASSERTION_NS, "EncryptedAssertion");
        if (total != 1 || plain.size() + encrypted.size() != 1) {
            throw new SsoException("SAML_ASSERTION_COUNT");
        }
        XmlDocument assertionDoc = doc;
        XmlElement assertion;
        if (!encrypted.isEmpty()) {
            if (config.decryptionKey() == null) {
                throw new SsoException("SAML_DECRYPT_UNAVAILABLE");
            }
            assertionDoc = decrypt(encrypted.get(0));
            assertion = assertionDoc.root().childElements().get(0);
        } else {
            if (config.requireEncryptedAssertion()) {
                throw new SsoException("SAML_ENCRYPTION_REQUIRED");
            }
            assertion = plain.get(0);
        }
        if (!assertion.childElements(Dsig.NS, "Signature").isEmpty()) {
            assertion = verifier.verify(assertionDoc, assertion);
        } else if (config.requireSignedAssertion() || !responseSigned) {
            throw new SsoException("SAML_SIGNATURE_REQUIRED");
        }
        return consumeAssertion(assertion, expectedRequestId, now);
    }

    private XmlDocument decrypt(XmlElement encryptedAssertion) {
        byte[] plaintext;
        try {
            plaintext = XmlEncryption.decrypt(encryptedAssertion, config.decryptionKey());
        } catch (SsoException e) {
            throw new SsoException("SAML_DECRYPT_FAILED");
        }
        StringBuilder sb = new StringBuilder(plaintext.length + 256);
        sb.append("<naqqa-decrypted");
        for (Map.Entry<String, String> ns : new TreeMap<>(encryptedAssertion.inScopeNamespaces()).entrySet()) {
            sb.append(ns.getKey().isEmpty() ? " xmlns" : " xmlns:" + ns.getKey()).append("=\"")
                    .append(XmlEscape.attribute(ns.getValue())).append('"');
        }
        sb.append('>').append(new String(plaintext, StandardCharsets.UTF_8)).append("</naqqa-decrypted>");
        XmlDocument d = parser.parse(sb.toString().getBytes(StandardCharsets.UTF_8));
        List<XmlElement> kids = d.root().childElements();
        for (XmlNode n : d.root().children()) {
            if (n instanceof XmlText t && !t.isWhitespace()) {
                throw new SsoException("SAML_DECRYPT_FAILED");
            }
        }
        if (kids.size() != 1 || !kids.get(0).is(Saml.ASSERTION_NS, "Assertion")) {
            throw new SsoException("SAML_DECRYPT_FAILED");
        }
        int assertions = 0;
        for (XmlElement e : d.elements()) {
            if (e.is(Saml.ASSERTION_NS, "Assertion") || e.is(Saml.ASSERTION_NS, "EncryptedAssertion")) {
                assertions++;
            }
        }
        if (assertions != 1) {
            throw new SsoException("SAML_ASSERTION_COUNT");
        }
        return d;
    }

    private void checkIssuer(XmlElement issuer) {
        String format = issuer.attribute("Format");
        if (format != null && !format.equals(Saml.NAMEID_ENTITY)) {
            throw new SsoException("SAML_ISSUER");
        }
        if (!config.idpEntityId().equals(Saml.trimmedText(issuer))) {
            throw new SsoException("SAML_ISSUER");
        }
    }

    private void checkInResponseTo(String actual, String expected) {
        if (expected == null) {
            if (!config.allowUnsolicited()) {
                throw new SsoException("SAML_UNSOLICITED");
            }
            if (actual != null) {
                throw new SsoException("SAML_IN_RESPONSE_TO");
            }
        } else if (actual != null && !actual.equals(expected)) {
            throw new SsoException("SAML_IN_RESPONSE_TO");
        }
    }

    private SamlPrincipal consumeAssertion(XmlElement assertion, String expectedRequestId, Instant now) {
        if (!"2.0".equals(assertion.attribute("Version"))) {
            throw new SsoException("SAML_VERSION");
        }
        String assertionId = Saml.requireId(assertion.attribute("ID"));
        Instant issued = Saml.parseTime(Saml.requiredAttribute(assertion, "IssueInstant"));
        if (issued.isAfter(now.plus(config.clockSkew()))) {
            throw new SsoException("SAML_NOT_YET_VALID");
        }
        checkIssuer(assertion.requiredChild(Saml.ASSERTION_NS, "Issuer"));
        XmlElement subject = assertion.requiredChild(Saml.ASSERTION_NS, "Subject");
        if (subject.optionalChild(Saml.ASSERTION_NS, "EncryptedID") != null) {
            throw new SsoException("SAML_UNSUPPORTED");
        }
        XmlElement nameIdElement = subject.requiredChild(Saml.ASSERTION_NS, "NameID");
        if (!nameIdElement.childElements().isEmpty()) {
            throw new SsoException("SAML_MALFORMED");
        }
        String nameId = Saml.trimmedText(nameIdElement);
        if (nameId.isEmpty()) {
            throw new SsoException("SAML_MALFORMED");
        }
        Instant confirmationExpiry = bearerConfirmation(subject, expectedRequestId, now);

        XmlElement conditions = assertion.requiredChild(Saml.ASSERTION_NS, "Conditions");
        Instant notBefore = Saml.optionalTime(conditions, "NotBefore");
        Instant notOnOrAfter = Saml.optionalTime(conditions, "NotOnOrAfter");
        if (notBefore != null && notBefore.isAfter(now.plus(config.clockSkew()))) {
            throw new SsoException("SAML_NOT_YET_VALID");
        }
        if (notOnOrAfter != null && !now.minus(config.clockSkew()).isBefore(notOnOrAfter)) {
            throw new SsoException("SAML_EXPIRED");
        }
        boolean audienceSeen = false;
        for (XmlElement c : conditions.childElements()) {
            if (c.is(Saml.ASSERTION_NS, "AudienceRestriction")) {
                boolean match = false;
                for (XmlElement aud : c.childElements(Saml.ASSERTION_NS, "Audience")) {
                    if (config.entityId().equals(Saml.trimmedText(aud))) {
                        match = true;
                    }
                }
                if (!match) {
                    throw new SsoException("SAML_AUDIENCE");
                }
                audienceSeen = true;
            } else if (!c.is(Saml.ASSERTION_NS, "OneTimeUse") && !c.is(Saml.ASSERTION_NS, "ProxyRestriction")) {
                throw new SsoException("SAML_UNKNOWN_CONDITION");
            }
        }
        if (!audienceSeen) {
            throw new SsoException("SAML_AUDIENCE");
        }

        List<XmlElement> authnStatements = assertion.childElements(Saml.ASSERTION_NS, "AuthnStatement");
        if (authnStatements.isEmpty()) {
            throw new SsoException("SAML_NO_AUTHN_STATEMENT");
        }
        XmlElement authn = authnStatements.get(0);
        Instant authnInstant = Saml.parseTime(Saml.requiredAttribute(authn, "AuthnInstant"));
        if (authnInstant.isAfter(now.plus(config.clockSkew()))) {
            throw new SsoException("SAML_NOT_YET_VALID");
        }
        Instant sessionNotOnOrAfter = Saml.optionalTime(authn, "SessionNotOnOrAfter");
        if (sessionNotOnOrAfter != null && !now.minus(config.clockSkew()).isBefore(sessionNotOnOrAfter)) {
            throw new SsoException("SAML_EXPIRED");
        }
        String classRef = null;
        XmlElement ctx = authn.optionalChild(Saml.ASSERTION_NS, "AuthnContext");
        if (ctx != null) {
            XmlElement ref = ctx.optionalChild(Saml.ASSERTION_NS, "AuthnContextClassRef");
            classRef = ref == null ? null : Saml.trimmedText(ref);
        }

        Map<String, List<String>> attributes = new LinkedHashMap<>();
        for (XmlElement st : assertion.childElements(Saml.ASSERTION_NS, "AttributeStatement")) {
            for (XmlElement attr : st.childElements(Saml.ASSERTION_NS, "Attribute")) {
                String name = Saml.requiredAttribute(attr, "Name");
                List<String> values = attributes.computeIfAbsent(name, k -> new ArrayList<>());
                for (XmlElement v : attr.childElements(Saml.ASSERTION_NS, "AttributeValue")) {
                    if (!v.childElements().isEmpty()) {
                        throw new SsoException("SAML_MALFORMED");
                    }
                    values.add(v.text());
                }
            }
        }

        Instant expiresAt = notOnOrAfter == null ? confirmationExpiry
                : (notOnOrAfter.isBefore(confirmationExpiry) ? notOnOrAfter : confirmationExpiry);
        Instant replayUntil = (notOnOrAfter != null && notOnOrAfter.isAfter(confirmationExpiry) ? notOnOrAfter
                : confirmationExpiry).plus(config.clockSkew());
        if (!replayCache.markIfAbsent("assertion|" + config.idpEntityId() + "|" + assertionId, replayUntil)) {
            throw new SsoException("SAML_REPLAY");
        }
        if (expectedRequestId != null && !requests.consume(expectedRequestId)) {
            throw new SsoException("SAML_UNKNOWN_REQUEST");
        }
        return new SamlPrincipal(config.idpEntityId(), nameId, nameIdElement.attribute("Format"),
                authn.attribute("SessionIndex"), classRef, authnInstant, attributes, assertionId, expectedRequestId,
                expiresAt);
    }

    private Instant bearerConfirmation(XmlElement subject, String expectedRequestId, Instant now) {
        String failure = "SAML_SUBJECT_CONFIRMATION";
        for (XmlElement sc : subject.childElements(Saml.ASSERTION_NS, "SubjectConfirmation")) {
            if (!Saml.CM_BEARER.equals(sc.attribute("Method"))) {
                continue;
            }
            XmlElement data = sc.optionalChild(Saml.ASSERTION_NS, "SubjectConfirmationData");
            if (data == null) {
                continue;
            }
            if (!config.assertionConsumerServiceUrl().equals(data.attribute("Recipient"))) {
                failure = "SAML_RECIPIENT";
                continue;
            }
            Instant notOnOrAfter = Saml.optionalTime(data, "NotOnOrAfter");
            if (notOnOrAfter == null || !now.minus(config.clockSkew()).isBefore(notOnOrAfter)) {
                failure = "SAML_EXPIRED";
                continue;
            }
            Instant notBefore = Saml.optionalTime(data, "NotBefore");
            if (notBefore != null && notBefore.isAfter(now.plus(config.clockSkew()))) {
                failure = "SAML_NOT_YET_VALID";
                continue;
            }
            String irt = data.attribute("InResponseTo");
            if (expectedRequestId == null ? irt != null : !expectedRequestId.equals(irt)) {
                failure = "SAML_IN_RESPONSE_TO";
                continue;
            }
            return notOnOrAfter;
        }
        throw new SsoException(failure);
    }

    public String metadata(X509Certificate encryptionCertificate) {
        XmlWriter w = new XmlWriter();
        w.start("md:EntityDescriptor").attr("xmlns:md", Saml.METADATA_NS).attr("xmlns:ds", Dsig.NS)
                .attr("entityID", config.entityId());
        w.start("md:SPSSODescriptor").attr("AuthnRequestsSigned", Boolean.toString(signer != null))
                .attr("WantAssertionsSigned", Boolean.toString(config.requireSignedAssertion()))
                .attr("protocolSupportEnumeration", Saml.PROTOCOL_NS);
        if (signer != null) {
            keyDescriptor(w, "signing", signer.certificate());
        }
        if (encryptionCertificate != null) {
            keyDescriptor(w, "encryption", encryptionCertificate);
        }
        w.start("md:AssertionConsumerService").attr("Binding", Saml.BINDING_POST)
                .attr("Location", config.assertionConsumerServiceUrl()).attr("index", "0").end();
        w.end().end();
        return w.toString();
    }

    private static void keyDescriptor(XmlWriter w, String use, X509Certificate cert) {
        String encoded;
        try {
            encoded = Dsig.base64(cert.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new SsoException("DSIG_BAD_CERTIFICATE");
        }
        w.start("md:KeyDescriptor").attr("use", use).start("ds:KeyInfo").start("ds:X509Data")
                .element("ds:X509Certificate", encoded).end().end().end();
    }
}
