package com.naqqa.sso.saml.dsig;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.c14n.ExcC14n;
import com.naqqa.sso.saml.xml.XmlDocument;
import com.naqqa.sso.saml.xml.XmlElement;
import com.naqqa.sso.saml.xml.XmlEscape;
import com.naqqa.sso.saml.xml.XmlLimits;
import com.naqqa.sso.saml.xml.XmlNode;
import com.naqqa.sso.saml.xml.XmlParser;
import com.naqqa.sso.saml.xml.XmlSerializer;
import com.naqqa.sso.util.Hashes;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.util.List;
import java.util.Set;

public final class XmlSigner {
    private static final String ISSUER_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final ExcC14n C14N = new ExcC14n(false, Set.of());

    private final PrivateKey key;
    private final X509Certificate certificate;
    private final String algorithm;
    private final XmlParser parser;

    public XmlSigner(PrivateKey key, X509Certificate certificate) {
        this(key, certificate, XmlLimits.DEFAULT.withMaxBytes(4 * 1024 * 1024));
    }

    public XmlSigner(PrivateKey key, X509Certificate certificate, XmlLimits limits) {
        if (key == null || certificate == null) {
            throw new SsoException("DSIG_NO_SIGNING_KEY");
        }
        Dsig.checkKey(certificate.getPublicKey());
        if (key instanceof RSAPrivateKey) {
            algorithm = Dsig.RSA_SHA256;
        } else if (key instanceof ECPrivateKey) {
            algorithm = Dsig.ECDSA_SHA256;
        } else {
            throw new SsoException("DSIG_UNSUPPORTED_KEY");
        }
        if (!Dsig.keyMatches(algorithm, certificate.getPublicKey())) {
            throw new SsoException("DSIG_UNSUPPORTED_KEY");
        }
        this.key = key;
        this.certificate = certificate;
        this.parser = new XmlParser(limits);
    }

    public String algorithm() {
        return algorithm;
    }

    public X509Certificate certificate() {
        return certificate;
    }

    public byte[] sign(byte[] xml, String idValue) {
        XmlDocument doc = parser.parse(xml);
        doc.requireUniqueIds();
        List<XmlElement> found = doc.elementsWithId(idValue);
        if (found.size() != 1 || !idValue.equals(found.get(0).attribute("ID"))) {
            throw new SsoException("DSIG_TARGET_NOT_FOUND");
        }
        return sign(doc, found.get(0));
    }

    public byte[] sign(XmlDocument doc, XmlElement target) {
        if (!doc.contains(target)) {
            throw new SsoException("DSIG_TARGET_NOT_FOUND");
        }
        String id = target.attribute("ID");
        if (id == null || !XmlParser.isNcName(id)) {
            throw new SsoException("DSIG_TARGET_NOT_FOUND");
        }
        if (!target.childElements(Dsig.NS, "Signature").isEmpty()) {
            throw new SsoException("DSIG_ALREADY_SIGNED");
        }
        byte[] digest = Hashes.sha256(C14N.canonicalize(target));
        String signedInfo = "<ds:SignedInfo>"
                + "<ds:CanonicalizationMethod Algorithm=\"" + ExcC14n.ALGORITHM + "\"/>"
                + "<ds:SignatureMethod Algorithm=\"" + algorithm + "\"/>"
                + "<ds:Reference URI=\"#" + XmlEscape.attribute(id) + "\">"
                + "<ds:Transforms>"
                + "<ds:Transform Algorithm=\"" + ExcC14n.ENVELOPED_SIGNATURE + "\"/>"
                + "<ds:Transform Algorithm=\"" + ExcC14n.ALGORITHM + "\"/>"
                + "</ds:Transforms>"
                + "<ds:DigestMethod Algorithm=\"" + Dsig.SHA256 + "\"/>"
                + "<ds:DigestValue>" + Dsig.base64(digest) + "</ds:DigestValue>"
                + "</ds:Reference>"
                + "</ds:SignedInfo>";
        String open = "<ds:Signature xmlns:ds=\"" + Dsig.NS + "\">";
        XmlDocument siDoc = new XmlParser().parse(open + signedInfo + "</ds:Signature>");
        byte[] canonical = C14N.canonicalize(siDoc.root().childElements().get(0));
        String value = Dsig.base64(signRaw(canonical));
        String cert;
        try {
            cert = Dsig.base64(certificate.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new SsoException("DSIG_BAD_CERTIFICATE");
        }
        String signature = open + signedInfo
                + "<ds:SignatureValue>" + value + "</ds:SignatureValue>"
                + "<ds:KeyInfo><ds:X509Data><ds:X509Certificate>" + cert
                + "</ds:X509Certificate></ds:X509Data></ds:KeyInfo>"
                + "</ds:Signature>";
        int index = 0;
        List<XmlNode> children = target.children();
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) instanceof XmlElement first) {
                if (first.is(ISSUER_NS, "Issuer")) {
                    index = i + 1;
                }
                break;
            }
        }
        String out = XmlSerializer.serialize(doc.root(), new XmlSerializer.Insertion(target.id(), index, signature));
        return out.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] signRaw(byte[] data) {
        try {
            Signature s = Signature.getInstance(Dsig.jcaAlgorithm(algorithm));
            s.initSign(key);
            s.update(data);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new SsoException("DSIG_SIGN_FAILED");
        }
    }
}
