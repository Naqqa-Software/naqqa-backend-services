package com.naqqa.sso.saml.idp;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.ReplayCache;
import com.naqqa.sso.saml.Saml;
import com.naqqa.sso.saml.SamlBindings;
import com.naqqa.sso.saml.SamlPostMessage;
import com.naqqa.sso.saml.dsig.Dsig;
import com.naqqa.sso.saml.dsig.XmlEncryption;
import com.naqqa.sso.saml.dsig.XmlSignatureVerifier;
import com.naqqa.sso.saml.dsig.XmlSigner;
import com.naqqa.sso.saml.xml.XmlDocument;
import com.naqqa.sso.saml.xml.XmlElement;
import com.naqqa.sso.saml.xml.XmlParser;
import com.naqqa.sso.saml.xml.XmlWriter;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateEncodingException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SamlIdp {
    private final SamlIdpConfig config;
    private final ServiceProviderRegistry registry;
    private final ReplayCache replayCache;
    private final Clock clock;
    private final XmlParser parser;
    private final XmlSigner signer;

    public SamlIdp(SamlIdpConfig config, ServiceProviderRegistry registry, ReplayCache replayCache, Clock clock) {
        this.config = config;
        this.registry = registry;
        this.replayCache = replayCache;
        this.clock = clock;
        this.parser = new XmlParser(config.limits());
        this.signer = new XmlSigner(config.signingKey(), config.signingCertificate());
    }

    private record Incoming(XmlDocument document, XmlElement root, ServiceProvider sp, String relayState, boolean signed) {
    }

    public AuthnRequestInfo parseAuthnRequestRedirect(String rawQuery) {
        return authnRequest(readRedirect(rawQuery, "SAMLRequest", false));
    }

    public AuthnRequestInfo parseAuthnRequestPost(String samlRequest, String relayState) {
        return authnRequest(readPost(samlRequest, relayState, false));
    }

    public LogoutRequestInfo parseLogoutRequestRedirect(String rawQuery) {
        return logoutRequest(readRedirect(rawQuery, "SAMLRequest", true));
    }

    public LogoutRequestInfo parseLogoutRequestPost(String samlRequest, String relayState) {
        return logoutRequest(readPost(samlRequest, relayState, true));
    }

    public LogoutResponseInfo parseLogoutResponseRedirect(String rawQuery, String expectedRequestId, String expectedSpEntityId) {
        return logoutResponse(readRedirect(rawQuery, "SAMLResponse", true), expectedRequestId, expectedSpEntityId);
    }

    public LogoutResponseInfo parseLogoutResponsePost(String samlResponse, String relayState, String expectedRequestId,
                                                      String expectedSpEntityId) {
        return logoutResponse(readPost(samlResponse, relayState, true), expectedRequestId, expectedSpEntityId);
    }

    private Incoming readRedirect(String rawQuery, String param, boolean signatureRequired) {
        SamlBindings.RedirectMessage m = SamlBindings.decodeRedirect(rawQuery, param, config.limits().maxBytes());
        XmlDocument doc = parser.parse(m.xml());
        XmlElement root = doc.root();
        ServiceProvider sp = issuerSp(root);
        if (!root.childElements(Dsig.NS, "Signature").isEmpty()) {
            throw new SsoException("SAML_BINDING");
        }
        boolean required = signatureRequired || sp.requireSignedRequests() || config.wantAuthnRequestsSigned();
        if (m.signed()) {
            SamlBindings.verifyRedirectSignature(m, verifierFor(sp));
        } else if (required) {
            throw new SsoException("SAML_SIGNATURE_REQUIRED");
        }
        return new Incoming(doc, root, sp, m.relayState(), m.signed());
    }

    private Incoming readPost(String value, String relayState, boolean signatureRequired) {
        SamlBindings.checkRelayState(relayState);
        XmlDocument doc = parser.parse(SamlBindings.decodePost(value, config.limits().maxBytes()));
        XmlElement root = doc.root();
        ServiceProvider sp = issuerSp(root);
        boolean required = signatureRequired || sp.requireSignedRequests() || config.wantAuthnRequestsSigned();
        boolean hasSignature = !root.childElements(Dsig.NS, "Signature").isEmpty();
        if (hasSignature) {
            XmlElement verified = verifierFor(sp).verify(doc, root);
            return new Incoming(doc, verified, sp, relayState, true);
        }
        if (required) {
            throw new SsoException("SAML_SIGNATURE_REQUIRED");
        }
        return new Incoming(doc, root, sp, relayState, false);
    }

    private XmlSignatureVerifier verifierFor(ServiceProvider sp) {
        if (sp.signingCertificates().isEmpty()) {
            throw new SsoException("SAML_SP_NO_SIGNING_KEY");
        }
        return new XmlSignatureVerifier(sp.signingCertificates());
    }

    private ServiceProvider issuerSp(XmlElement root) {
        List<XmlElement> issuers = root.childElements(Saml.ASSERTION_NS, "Issuer");
        if (issuers.size() != 1) {
            throw new SsoException("SAML_ISSUER");
        }
        XmlElement issuer = issuers.get(0);
        String format = issuer.attribute("Format");
        if (format != null && !format.equals(Saml.NAMEID_ENTITY)) {
            throw new SsoException("SAML_ISSUER");
        }
        return registry.find(Saml.trimmedText(issuer)).orElseThrow(() -> new SsoException("SAML_UNKNOWN_SP"));
    }

    private Instant checkCommon(XmlElement root, String expectedDestination, boolean signed) {
        if (!"2.0".equals(root.attribute("Version"))) {
            throw new SsoException("SAML_VERSION");
        }
        Saml.requireId(root.attribute("ID"));
        Instant issued = Saml.parseTime(Saml.requiredAttribute(root, "IssueInstant"));
        Instant now = clock.instant();
        if (issued.isAfter(now.plus(config.clockSkew()))) {
            throw new SsoException("SAML_NOT_YET_VALID");
        }
        if (issued.isBefore(now.minus(config.requestMaxAge()).minus(config.clockSkew()))) {
            throw new SsoException("SAML_EXPIRED");
        }
        String destination = root.attribute("Destination");
        if (signed && destination == null) {
            throw new SsoException("SAML_DESTINATION");
        }
        if (destination != null && !destination.equals(expectedDestination)) {
            throw new SsoException("SAML_DESTINATION");
        }
        return issued;
    }

    private void replay(String kind, ServiceProvider sp, String id, Instant issued) {
        Instant expires = issued.plus(config.requestMaxAge()).plus(config.clockSkew()).plus(config.clockSkew());
        if (!replayCache.markIfAbsent(kind + "|" + sp.entityId() + "|" + id, expires)) {
            throw new SsoException("SAML_REPLAY");
        }
    }

    private AuthnRequestInfo authnRequest(Incoming in) {
        XmlElement root = in.root();
        if (!root.is(Saml.PROTOCOL_NS, "AuthnRequest")) {
            throw new SsoException("SAML_UNEXPECTED_MESSAGE");
        }
        Instant issued = checkCommon(root, config.ssoUrl(), in.signed());
        ServiceProvider sp = in.sp();
        String acsUrl = root.attribute("AssertionConsumerServiceURL");
        String acsIndex = root.attribute("AssertionConsumerServiceIndex");
        if (acsUrl != null && acsIndex != null) {
            throw new SsoException("SAML_ACS_MISMATCH");
        }
        if (acsUrl != null) {
            if (!sp.assertionConsumerServiceUrls().contains(acsUrl)) {
                throw new SsoException("SAML_ACS_MISMATCH");
            }
        } else if (acsIndex != null) {
            if (!acsIndex.matches("[0-9]{1,4}")) {
                throw new SsoException("SAML_ACS_MISMATCH");
            }
            int idx = Integer.parseInt(acsIndex);
            if (idx < 0 || idx >= sp.assertionConsumerServiceUrls().size()) {
                throw new SsoException("SAML_ACS_MISMATCH");
            }
            acsUrl = sp.assertionConsumerServiceUrls().get(idx);
        } else {
            acsUrl = sp.assertionConsumerServiceUrls().get(0);
        }
        String binding = root.attribute("ProtocolBinding");
        if (binding != null && !binding.equals(Saml.BINDING_POST)) {
            throw new SsoException("SAML_UNSUPPORTED_BINDING");
        }
        String format = null;
        XmlElement policy = root.optionalChild(Saml.PROTOCOL_NS, "NameIDPolicy");
        if (policy != null) {
            String f = policy.attribute("Format");
            if (f != null && !f.equals(Saml.NAMEID_UNSPECIFIED)) {
                if (!sp.allowedNameIdFormats().contains(f)) {
                    throw new SsoException("SAML_INVALID_NAMEID_POLICY");
                }
                format = f;
            }
        }
        List<String> contexts = new ArrayList<>();
        XmlElement requested = root.optionalChild(Saml.PROTOCOL_NS, "RequestedAuthnContext");
        if (requested != null) {
            for (XmlElement ref : requested.childElements(Saml.ASSERTION_NS, "AuthnContextClassRef")) {
                contexts.add(Saml.trimmedText(ref));
            }
        }
        String id = root.attribute("ID");
        replay("authn", sp, id, issued);
        return new AuthnRequestInfo(id, sp.entityId(), acsUrl, in.relayState(),
                format == null ? sp.defaultNameIdFormat() : format,
                bool(root.attribute("ForceAuthn")), bool(root.attribute("IsPassive")), contexts, issued, in.signed());
    }

    private static boolean bool(String v) {
        if (v == null || v.equals("false") || v.equals("0")) {
            return false;
        }
        if (v.equals("true") || v.equals("1")) {
            return true;
        }
        throw new SsoException("SAML_MALFORMED");
    }

    private LogoutRequestInfo logoutRequest(Incoming in) {
        XmlElement root = in.root();
        if (!root.is(Saml.PROTOCOL_NS, "LogoutRequest")) {
            throw new SsoException("SAML_UNEXPECTED_MESSAGE");
        }
        if (config.sloUrl() == null) {
            throw new SsoException("SAML_SLO_DISABLED");
        }
        Instant issued = checkCommon(root, config.sloUrl(), in.signed());
        Instant notOnOrAfter = Saml.optionalTime(root, "NotOnOrAfter");
        if (notOnOrAfter != null && !clock.instant().minus(config.clockSkew()).isBefore(notOnOrAfter)) {
            throw new SsoException("SAML_EXPIRED");
        }
        if (root.optionalChild(Saml.ASSERTION_NS, "EncryptedID") != null) {
            throw new SsoException("SAML_UNSUPPORTED");
        }
        XmlElement nameId = root.requiredChild(Saml.ASSERTION_NS, "NameID");
        List<String> sessions = new ArrayList<>();
        for (XmlElement si : root.childElements(Saml.PROTOCOL_NS, "SessionIndex")) {
            sessions.add(Saml.trimmedText(si));
        }
        String id = root.attribute("ID");
        replay("logout", in.sp(), id, issued);
        return new LogoutRequestInfo(id, in.sp().entityId(), Saml.trimmedText(nameId), nameId.attribute("Format"),
                sessions, in.relayState());
    }

    private LogoutResponseInfo logoutResponse(Incoming in, String expectedRequestId, String expectedSpEntityId) {
        XmlElement root = in.root();
        if (!root.is(Saml.PROTOCOL_NS, "LogoutResponse")) {
            throw new SsoException("SAML_UNEXPECTED_MESSAGE");
        }
        if (config.sloUrl() == null) {
            throw new SsoException("SAML_SLO_DISABLED");
        }
        Instant issued = checkCommon(root, config.sloUrl(), in.signed());
        String inResponseTo = Saml.requiredAttribute(root, "InResponseTo");
        if (expectedRequestId == null || expectedSpEntityId == null || !expectedRequestId.equals(inResponseTo)
                || !expectedSpEntityId.equals(in.sp().entityId())) {
            throw new SsoException("SAML_IN_RESPONSE_TO_MISMATCH");
        }
        String id = root.attribute("ID");
        replay("logoutresp", in.sp(), id, issued);
        XmlElement code = root.requiredChild(Saml.PROTOCOL_NS, "Status").requiredChild(Saml.PROTOCOL_NS, "StatusCode");
        return new LogoutResponseInfo(id, inResponseTo, in.sp().entityId(), Saml.requiredAttribute(code, "Value"),
                in.relayState());
    }

    public SamlPostMessage issueResponse(AuthnRequestInfo request, SamlSubject subject) {
        ServiceProvider sp = registry.find(request.spEntityId()).orElseThrow(() -> new SsoException("SAML_UNKNOWN_SP"));
        if (!sp.assertionConsumerServiceUrls().contains(request.assertionConsumerServiceUrl())) {
            throw new SsoException("SAML_ACS_MISMATCH");
        }
        Instant now = clock.instant();
        String format = request.nameIdFormat() == null || request.nameIdFormat().equals(Saml.NAMEID_UNSPECIFIED)
                ? sp.defaultNameIdFormat() : request.nameIdFormat();
        if (!sp.allowedNameIdFormats().contains(format)) {
            throw new SsoException("SAML_INVALID_NAMEID_POLICY");
        }
        String nameId = nameIdValue(format, sp, subject);
        String assertionId = Saml.newId();
        String responseId = Saml.newId();
        Instant expires = now.plus(config.assertionLifetime());
        XmlWriter a = new XmlWriter();
        a.start("saml:Assertion").attr("xmlns:saml", Saml.ASSERTION_NS).attr("ID", assertionId)
                .attr("IssueInstant", Saml.time(now)).attr("Version", "2.0");
        a.element("saml:Issuer", config.entityId());
        a.start("saml:Subject");
        a.start("saml:NameID").attr("Format", format);
        if (!format.equals(Saml.NAMEID_EMAIL)) {
            a.attr("NameQualifier", config.entityId()).attr("SPNameQualifier", sp.entityId());
        }
        a.text(nameId).end();
        a.start("saml:SubjectConfirmation").attr("Method", Saml.CM_BEARER);
        a.start("saml:SubjectConfirmationData").attr("InResponseTo", request.id())
                .attr("NotOnOrAfter", Saml.time(expires)).attr("Recipient", request.assertionConsumerServiceUrl()).end();
        a.end().end();
        a.start("saml:Conditions").attr("NotBefore", Saml.time(now)).attr("NotOnOrAfter", Saml.time(expires));
        a.start("saml:AudienceRestriction").element("saml:Audience", sp.entityId()).end();
        a.end();
        a.start("saml:AuthnStatement").attr("AuthnInstant", Saml.time(subject.authnInstant()))
                .attr("SessionIndex", subject.sessionIndex());
        a.start("saml:AuthnContext").element("saml:AuthnContextClassRef", authnContext(subject.acr())).end();
        a.end();
        if (!subject.attributes().isEmpty()) {
            a.start("saml:AttributeStatement");
            for (Map.Entry<String, List<String>> attr : subject.attributes().entrySet()) {
                a.start("saml:Attribute").attr("Name", attr.getKey()).attr("NameFormat", Saml.ATTRNAME_BASIC);
                for (String v : attr.getValue()) {
                    a.element("saml:AttributeValue", v);
                }
                a.end();
            }
            a.end();
        }
        a.end();
        byte[] signedAssertion = signer.sign(a.toString().getBytes(StandardCharsets.UTF_8), assertionId);
        String assertionPart;
        if (sp.encryptAssertion()) {
            assertionPart = "<saml:EncryptedAssertion>"
                    + XmlEncryption.encrypt(signedAssertion, sp.encryptionCertificate().getPublicKey())
                    + "</saml:EncryptedAssertion>";
        } else {
            assertionPart = new String(signedAssertion, StandardCharsets.UTF_8);
        }
        String response = response(responseId, now, request.assertionConsumerServiceUrl(), request.id(),
                Saml.STATUS_SUCCESS, null, assertionPart);
        byte[] out = response.getBytes(StandardCharsets.UTF_8);
        if (sp.signResponse()) {
            out = signer.sign(out, responseId);
        }
        return new SamlPostMessage(request.assertionConsumerServiceUrl(), "SAMLResponse", SamlBindings.encodePost(out),
                request.relayState(), responseId);
    }

    public SamlPostMessage errorResponse(AuthnRequestInfo request, String statusCode) {
        ServiceProvider sp = registry.find(request.spEntityId()).orElseThrow(() -> new SsoException("SAML_UNKNOWN_SP"));
        if (!sp.assertionConsumerServiceUrls().contains(request.assertionConsumerServiceUrl())) {
            throw new SsoException("SAML_ACS_MISMATCH");
        }
        if (Saml.STATUS_SUCCESS.equals(statusCode)) {
            throw new SsoException("SAML_STATUS");
        }
        boolean topLevel = statusCode.equals(Saml.STATUS_REQUESTER) || statusCode.equals(Saml.STATUS_RESPONDER);
        String responseId = Saml.newId();
        String xml = response(responseId, clock.instant(), request.assertionConsumerServiceUrl(), request.id(),
                topLevel ? statusCode : Saml.STATUS_RESPONDER, topLevel ? null : statusCode, "");
        byte[] out = signer.sign(xml.getBytes(StandardCharsets.UTF_8), responseId);
        return new SamlPostMessage(request.assertionConsumerServiceUrl(), "SAMLResponse", SamlBindings.encodePost(out),
                request.relayState(), responseId);
    }

    private String response(String id, Instant now, String destination, String inResponseTo, String status,
                            String subStatus, String assertionPart) {
        XmlWriter w = new XmlWriter();
        w.start("samlp:Response").attr("xmlns:samlp", Saml.PROTOCOL_NS).attr("xmlns:saml", Saml.ASSERTION_NS)
                .attr("ID", id).attr("Version", "2.0").attr("IssueInstant", Saml.time(now))
                .attr("Destination", destination).attr("InResponseTo", inResponseTo);
        w.element("saml:Issuer", config.entityId());
        status(w, status, subStatus);
        w.raw(assertionPart);
        w.end();
        return w.toString();
    }

    private static void status(XmlWriter w, String status, String subStatus) {
        w.start("samlp:Status").start("samlp:StatusCode").attr("Value", status);
        if (subStatus != null) {
            w.start("samlp:StatusCode").attr("Value", subStatus).end();
        }
        w.end().end();
    }

    private String nameIdValue(String format, ServiceProvider sp, SamlSubject subject) {
        if (format.equals(Saml.NAMEID_EMAIL)) {
            if (subject.email() == null || subject.email().isEmpty()) {
                throw new SsoException("SAML_NAMEID_UNAVAILABLE");
            }
            return subject.email();
        }
        if (format.equals(Saml.NAMEID_PERSISTENT)) {
            return persistentId(sp.entityId(), subject.userId());
        }
        if (format.equals(Saml.NAMEID_TRANSIENT)) {
            return Saml.newId();
        }
        throw new SsoException("SAML_INVALID_NAMEID_POLICY");
    }

    public String persistentId(String spEntityId, String userId) {
        byte[] secret = config.pairwiseSecret();
        try {
            return Bytes.b64url(Hashes.hmacSha256(secret,
                    (spEntityId + "\u0000" + userId).getBytes(StandardCharsets.UTF_8)));
        } finally {
            Bytes.wipe(secret);
        }
    }

    private String authnContext(String acr) {
        if (acr == null) {
            return Saml.AC_UNSPECIFIED;
        }
        String mapped = config.authnContextByAcr().get(acr);
        if (mapped == null) {
            mapped = SamlIdpConfig.DEFAULT_ACR.get(acr);
        }
        if (mapped != null) {
            return mapped;
        }
        if (acr.startsWith("urn:") || acr.startsWith("https://")) {
            return acr;
        }
        return Saml.AC_UNSPECIFIED;
    }

    public SamlPostMessage logoutResponse(LogoutRequestInfo request, boolean success) {
        ServiceProvider sp = registry.find(request.spEntityId()).orElseThrow(() -> new SsoException("SAML_UNKNOWN_SP"));
        String slo = requireSlo(sp);
        String id = Saml.newId();
        XmlWriter w = new XmlWriter();
        w.start("samlp:LogoutResponse").attr("xmlns:samlp", Saml.PROTOCOL_NS).attr("xmlns:saml", Saml.ASSERTION_NS)
                .attr("ID", id).attr("Version", "2.0").attr("IssueInstant", Saml.time(clock.instant()))
                .attr("Destination", slo).attr("InResponseTo", request.id());
        w.element("saml:Issuer", config.entityId());
        status(w, success ? Saml.STATUS_SUCCESS : Saml.STATUS_RESPONDER, success ? null : Saml.STATUS_PARTIAL_LOGOUT);
        w.end();
        byte[] out = signer.sign(w.toString().getBytes(StandardCharsets.UTF_8), id);
        return new SamlPostMessage(slo, "SAMLResponse", SamlBindings.encodePost(out), request.relayState(), id);
    }

    public SamlPostMessage logoutRequest(String spEntityId, String nameId, String nameIdFormat, String sessionIndex,
                                         String relayState) {
        ServiceProvider sp = registry.find(spEntityId).orElseThrow(() -> new SsoException("SAML_UNKNOWN_SP"));
        String slo = requireSlo(sp);
        String id = Saml.newId();
        Instant now = clock.instant();
        XmlWriter w = new XmlWriter();
        w.start("samlp:LogoutRequest").attr("xmlns:samlp", Saml.PROTOCOL_NS).attr("xmlns:saml", Saml.ASSERTION_NS)
                .attr("ID", id).attr("Version", "2.0").attr("IssueInstant", Saml.time(now))
                .attr("Destination", slo).attr("NotOnOrAfter", Saml.time(now.plus(config.assertionLifetime())));
        w.element("saml:Issuer", config.entityId());
        w.start("saml:NameID").attr("Format", nameIdFormat).text(nameId).end();
        if (sessionIndex != null) {
            w.element("samlp:SessionIndex", sessionIndex);
        }
        w.end();
        byte[] out = signer.sign(w.toString().getBytes(StandardCharsets.UTF_8), id);
        return new SamlPostMessage(slo, "SAMLRequest", SamlBindings.encodePost(out), relayState, id);
    }

    private static String requireSlo(ServiceProvider sp) {
        if (sp.singleLogoutServiceUrl() == null) {
            throw new SsoException("SAML_SP_NO_SLO");
        }
        return sp.singleLogoutServiceUrl();
    }

    public String metadata() {
        String cert;
        try {
            cert = Dsig.base64(config.signingCertificate().getEncoded());
        } catch (CertificateEncodingException e) {
            throw new SsoException("DSIG_BAD_CERTIFICATE");
        }
        XmlWriter w = new XmlWriter();
        w.start("md:EntityDescriptor").attr("xmlns:md", Saml.METADATA_NS).attr("xmlns:ds", Dsig.NS)
                .attr("entityID", config.entityId());
        w.start("md:IDPSSODescriptor").attr("WantAuthnRequestsSigned", Boolean.toString(config.wantAuthnRequestsSigned()))
                .attr("protocolSupportEnumeration", Saml.PROTOCOL_NS);
        w.start("md:KeyDescriptor").attr("use", "signing").start("ds:KeyInfo").start("ds:X509Data")
                .element("ds:X509Certificate", cert).end().end().end();
        if (config.sloUrl() != null) {
            w.start("md:SingleLogoutService").attr("Binding", Saml.BINDING_REDIRECT).attr("Location", config.sloUrl()).end();
            w.start("md:SingleLogoutService").attr("Binding", Saml.BINDING_POST).attr("Location", config.sloUrl()).end();
        }
        w.element("md:NameIDFormat", Saml.NAMEID_EMAIL);
        w.element("md:NameIDFormat", Saml.NAMEID_PERSISTENT);
        w.element("md:NameIDFormat", Saml.NAMEID_TRANSIENT);
        w.start("md:SingleSignOnService").attr("Binding", Saml.BINDING_REDIRECT).attr("Location", config.ssoUrl()).end();
        w.start("md:SingleSignOnService").attr("Binding", Saml.BINDING_POST).attr("Location", config.ssoUrl()).end();
        w.end().end();
        return w.toString();
    }
}
