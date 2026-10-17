package com.naqqa.sso.saml;

import com.naqqa.sso.saml.c14n.ExcC14n;
import com.naqqa.sso.saml.dsig.XmlSignatureVerifier;
import com.naqqa.sso.saml.dsig.XmlSigner;
import com.naqqa.sso.saml.idp.LogoutResponseInfo;
import com.naqqa.sso.saml.xml.XmlDocument;
import com.naqqa.sso.saml.xml.XmlElement;
import com.naqqa.sso.saml.xml.XmlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamlCoreRound2Test extends SamlTestBase {
    private static final String DOC = "<r:Root xmlns:r=\"urn:r\"><r:Item ID=\"_item1\"><r:Name>alice@x.com<!--note--></r:Name>"
            + "<!--outer--></r:Item></r:Root>";
    private final XmlParser parser = new XmlParser();
    private final XmlSigner signer = new XmlSigner(TestKeys.IDP.key(), TestKeys.IDP.cert());
    private final XmlSigner spSigner = new XmlSigner(TestKeys.SP.key(), TestKeys.SP.cert());
    private final XmlSignatureVerifier verifier = new XmlSignatureVerifier(List.of(TestKeys.IDP.cert()));

    private String withCommentsTransform(String signed, String newText) throws Exception {
        String xml = signed.replace("<ds:Transform Algorithm=\"" + ExcC14n.ALGORITHM + "\"/>",
                "<ds:Transform Algorithm=\"" + ExcC14n.ALGORITHM_WITH_COMMENTS + "\"/>");
        if (newText != null) {
            xml = xml.replace("alice@x.com<!--note-->", newText);
        }
        XmlDocument d = parser.parse(xml);
        XmlElement signedInfo = d.elementsWithId("_item1").get(0).childElements(com.naqqa.sso.saml.dsig.Dsig.NS, "Signature").get(0).childElements().get(0);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(TestKeys.IDP.key());
        s.update(new ExcC14n(false, Set.of()).canonicalize(signedInfo));
        String value = Base64.getEncoder().encodeToString(s.sign());
        int a = xml.indexOf("<ds:SignatureValue>") + "<ds:SignatureValue>".length();
        int b = xml.indexOf("</ds:SignatureValue>");
        return xml.substring(0, a) + value + xml.substring(b);
    }

    @Test
    @DisplayName("SSO-T-saml-134 same-document #id reference drops comments before a WithComments transform (XML-DSig 4.4.3.3)")
    void sameDocumentReferenceDropsComments() throws Exception {
        String signed = new String(signer.sign(DOC.getBytes(StandardCharsets.UTF_8), "_item1"), StandardCharsets.UTF_8);
        String xml = withCommentsTransform(signed, null);
        XmlDocument d = parser.parse(xml);
        XmlElement target = d.elementsWithId("_item1").get(0);
        assertEquals(target, verifier.verify(d, target));
        String tampered = withCommentsTransform(signed, "alice@x.com<!---->.evil");
        XmlDocument t = parser.parse(tampered);
        assertCode("DSIG_DIGEST_MISMATCH", () -> verifier.verify(t, t.elementsWithId("_item1").get(0)));
    }

    private byte[] logoutResponse(String inResponseTo) {
        String rid = Saml.newId();
        return spSigner.sign(logoutResponseXml(rid, inResponseTo).getBytes(StandardCharsets.UTF_8), rid);
    }

    private String logoutResponseXml(String rid, String inResponseTo) {
        String resp = "<samlp:LogoutResponse xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" "
                + "xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\" ID=\"" + rid + "\" Version=\"2.0\" IssueInstant=\""
                + Saml.time(T0) + "\" Destination=\"" + IDP_SLO + "\" InResponseTo=\"" + inResponseTo + "\">"
                + "<saml:Issuer>" + SP_ENTITY + "</saml:Issuer><samlp:Status><samlp:StatusCode Value=\""
                + Saml.STATUS_SUCCESS + "\"/></samlp:Status></samlp:LogoutResponse>";
        return resp;
    }

    @Test
    @DisplayName("SSO-T-saml-135 LogoutResponse must answer the LogoutRequest the IdP sent to that SP")
    void logoutResponseInResponseTo() {
        SamlPostMessage req = idp.logoutRequest(SP_ENTITY, "alice@x.com", Saml.NAMEID_EMAIL, "sess-1", null);
        assertCode("SAML_IN_RESPONSE_TO_MISMATCH",
                () -> idp.parseLogoutResponsePost(encodeBytes(logoutResponse("_forged")), null, req.messageId(), SP_ENTITY));
        assertCode("SAML_IN_RESPONSE_TO_MISMATCH",
                () -> idp.parseLogoutResponsePost(encodeBytes(logoutResponse(req.messageId())), null, req.messageId(),
                        "https://other-sp.example.com"));
        assertCode("SAML_IN_RESPONSE_TO_MISMATCH",
                () -> idp.parseLogoutResponsePost(encodeBytes(logoutResponse(req.messageId())), null, null, SP_ENTITY));
        String q = query(SamlBindings.encodeRedirect(IDP_SLO, "SAMLResponse",
                logoutResponseXml(Saml.newId(), "_forged").getBytes(StandardCharsets.UTF_8), null, spSigner));
        assertCode("SAML_IN_RESPONSE_TO_MISMATCH", () -> idp.parseLogoutResponseRedirect(q, req.messageId(), SP_ENTITY));
        LogoutResponseInfo ok = idp.parseLogoutResponsePost(encodeBytes(logoutResponse(req.messageId())), null,
                req.messageId(), SP_ENTITY);
        assertTrue(ok.success());
        assertEquals(req.messageId(), ok.inResponseTo());
    }
}
