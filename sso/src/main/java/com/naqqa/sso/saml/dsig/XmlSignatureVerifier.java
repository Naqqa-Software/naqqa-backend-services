package com.naqqa.sso.saml.dsig;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.c14n.ExcC14n;
import com.naqqa.sso.saml.xml.XmlAttribute;
import com.naqqa.sso.saml.xml.XmlDocument;
import com.naqqa.sso.saml.xml.XmlElement;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class XmlSignatureVerifier {
    private final List<X509Certificate> trusted;
    private final List<byte[]> encoded;

    public XmlSignatureVerifier(List<X509Certificate> trustedCertificates) {
        if (trustedCertificates == null || trustedCertificates.isEmpty()) {
            throw new SsoException("DSIG_NO_TRUSTED_KEYS");
        }
        this.trusted = List.copyOf(trustedCertificates);
        this.encoded = new ArrayList<>();
        for (X509Certificate c : trusted) {
            Dsig.checkKey(c.getPublicKey());
            try {
                encoded.add(c.getEncoded());
            } catch (CertificateEncodingException e) {
                throw new SsoException("DSIG_BAD_CERTIFICATE");
            }
        }
    }

    public XmlElement verify(XmlDocument document, XmlElement target) {
        if (document == null || !document.contains(target)) {
            throw new SsoException("DSIG_FOREIGN_ELEMENT");
        }
        try {
            document.requireUniqueIds();
        } catch (SsoException e) {
            throw new SsoException("DSIG_DUPLICATE_ID");
        }
        List<XmlElement> signatures = target.childElements(Dsig.NS, "Signature");
        if (signatures.isEmpty()) {
            throw new SsoException("DSIG_SIGNATURE_MISSING");
        }
        if (signatures.size() > 1) {
            throw new SsoException("DSIG_MULTIPLE_SIGNATURES");
        }
        XmlElement signature = signatures.get(0);
        Dsig.requireOnlyWhitespaceText(signature);
        List<XmlElement> parts = signature.childElements();
        if (parts.size() < 2 || parts.size() > 3 || !parts.get(0).is(Dsig.NS, "SignedInfo")
                || !parts.get(1).is(Dsig.NS, "SignatureValue")
                || (parts.size() == 3 && !parts.get(2).is(Dsig.NS, "KeyInfo"))) {
            throw new SsoException("DSIG_MALFORMED");
        }
        XmlElement signedInfo = parts.get(0);
        XmlElement signatureValue = parts.get(1);
        XmlElement keyInfo = parts.size() == 3 ? parts.get(2) : null;

        Dsig.requireOnlyWhitespaceText(signedInfo);
        List<XmlElement> si = signedInfo.childElements();
        if (si.size() < 3 || !si.get(0).is(Dsig.NS, "CanonicalizationMethod") || !si.get(1).is(Dsig.NS, "SignatureMethod")) {
            throw new SsoException("DSIG_MALFORMED");
        }
        for (int i = 2; i < si.size(); i++) {
            if (!si.get(i).is(Dsig.NS, "Reference")) {
                throw new SsoException("DSIG_MALFORMED");
            }
        }
        if (si.size() != 3) {
            throw new SsoException("DSIG_REFERENCE_COUNT");
        }
        ExcC14n signedInfoC14n = canonicalizer(si.get(0));
        XmlElement sigMethod = si.get(1);
        String sigAlg = sigMethod.attribute("Algorithm");
        if (!Dsig.RSA_SHA256.equals(sigAlg) && !Dsig.ECDSA_SHA256.equals(sigAlg)) {
            throw new SsoException("DSIG_ALGORITHM");
        }
        if (!sigMethod.childElements().isEmpty()) {
            throw new SsoException("DSIG_MALFORMED");
        }
        XmlElement reference = si.get(2);
        String uri = reference.attribute("URI");
        String targetId = target.attribute("ID");
        if (uri == null || !uri.startsWith("#") || targetId == null || targetId.isEmpty()
                || !uri.substring(1).equals(targetId)) {
            throw new SsoException("DSIG_REFERENCE_MISMATCH");
        }
        List<XmlElement> byId = document.elementsWithId(targetId);
        if (byId.size() != 1 || byId.get(0) != target) {
            throw new SsoException("DSIG_REFERENCE_MISMATCH");
        }
        for (XmlAttribute a : reference.attributes()) {
            if (!a.namespaceUri().isEmpty() || !(a.localName().equals("URI") || a.localName().equals("Id") || a.localName().equals("Type"))) {
                throw new SsoException("DSIG_MALFORMED");
            }
        }
        Dsig.requireOnlyWhitespaceText(reference);
        List<XmlElement> rp = reference.childElements();
        if (rp.size() != 3 || !rp.get(0).is(Dsig.NS, "Transforms") || !rp.get(1).is(Dsig.NS, "DigestMethod")
                || !rp.get(2).is(Dsig.NS, "DigestValue")) {
            throw new SsoException("DSIG_MALFORMED");
        }
        ExcC14n referenceC14n = transforms(rp.get(0));
        if (!Dsig.SHA256.equals(rp.get(1).attribute("Algorithm"))) {
            throw new SsoException("DSIG_ALGORITHM");
        }
        if (!rp.get(1).childElements().isEmpty()) {
            throw new SsoException("DSIG_MALFORMED");
        }
        byte[] expectedDigest = Dsig.base64(rp.get(2).simpleText(), "DSIG_MALFORMED");
        byte[] sigBytes = Dsig.base64(signatureValue.simpleText(), "DSIG_MALFORMED");
        if (expectedDigest.length != 32 || sigBytes.length == 0) {
            throw new SsoException("DSIG_MALFORMED");
        }

        byte[] canonicalSignedInfo = signedInfoC14n.canonicalize(signedInfo);
        if (!verifyWithTrusted(sigAlg, canonicalSignedInfo, sigBytes, keyInfoCertificate(keyInfo))) {
            throw new SsoException("DSIG_SIGNATURE_INVALID");
        }
        byte[] digest = Hashes.sha256(referenceC14n.canonicalize(target, signature));
        if (!Bytes.constantTimeEquals(digest, expectedDigest)) {
            throw new SsoException("DSIG_DIGEST_MISMATCH");
        }
        return target;
    }

    public boolean verifyRaw(String algorithmUri, byte[] data, byte[] signature) {
        if (!Dsig.RSA_SHA256.equals(algorithmUri) && !Dsig.ECDSA_SHA256.equals(algorithmUri)) {
            throw new SsoException("DSIG_ALGORITHM");
        }
        return verifyWithTrusted(algorithmUri, data, signature, null);
    }

    private boolean verifyWithTrusted(String alg, byte[] data, byte[] sig, byte[] hinted) {
        List<Integer> order = new ArrayList<>();
        if (hinted != null) {
            for (int i = 0; i < encoded.size(); i++) {
                if (Bytes.constantTimeEquals(encoded.get(i), hinted)) {
                    order.add(i);
                }
            }
        }
        for (int i = 0; i < trusted.size(); i++) {
            if (!order.contains(i)) {
                order.add(i);
            }
        }
        for (int i : order) {
            PublicKey key = trusted.get(i).getPublicKey();
            if (!Dsig.keyMatches(alg, key)) {
                continue;
            }
            try {
                Signature verifier = Signature.getInstance(Dsig.jcaAlgorithm(alg));
                verifier.initVerify(key);
                verifier.update(data);
                if (verifier.verify(sig)) {
                    return true;
                }
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                continue;
            }
        }
        return false;
    }

    private static byte[] keyInfoCertificate(XmlElement keyInfo) {
        if (keyInfo == null) {
            return null;
        }
        for (XmlElement data : keyInfo.childElements(Dsig.NS, "X509Data")) {
            for (XmlElement cert : data.childElements(Dsig.NS, "X509Certificate")) {
                try {
                    return Dsig.base64(cert.simpleText(), "DSIG_MALFORMED");
                } catch (SsoException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static ExcC14n canonicalizer(XmlElement method) {
        String alg = method.attribute("Algorithm");
        if (!ExcC14n.ALGORITHM.equals(alg) && !ExcC14n.ALGORITHM_WITH_COMMENTS.equals(alg)) {
            throw new SsoException("DSIG_ALGORITHM");
        }
        Dsig.requireOnlyWhitespaceText(method);
        List<XmlElement> kids = method.childElements();
        Set<String> prefixes = Set.of();
        if (kids.size() > 1) {
            throw new SsoException("DSIG_MALFORMED");
        }
        if (kids.size() == 1) {
            XmlElement inc = kids.get(0);
            if (!inc.is(ExcC14n.ALGORITHM, "InclusiveNamespaces") || !inc.childElements().isEmpty()) {
                throw new SsoException("DSIG_MALFORMED");
            }
            prefixes = ExcC14n.parsePrefixList(inc.attribute("PrefixList"));
        }
        return ExcC14n.forAlgorithm(alg, prefixes);
    }

    private static ExcC14n transforms(XmlElement transforms) {
        Dsig.requireOnlyWhitespaceText(transforms);
        List<XmlElement> list = transforms.childElements();
        for (XmlElement t : list) {
            if (!t.is(Dsig.NS, "Transform")) {
                throw new SsoException("DSIG_MALFORMED");
            }
            String alg = t.attribute("Algorithm");
            if (!ExcC14n.ENVELOPED_SIGNATURE.equals(alg) && !ExcC14n.ALGORITHM.equals(alg)
                    && !ExcC14n.ALGORITHM_WITH_COMMENTS.equals(alg)) {
                throw new SsoException("DSIG_TRANSFORM");
            }
        }
        if (list.size() != 2 || !ExcC14n.ENVELOPED_SIGNATURE.equals(list.get(0).attribute("Algorithm"))
                || !list.get(0).childElements().isEmpty()) {
            throw new SsoException("DSIG_TRANSFORM");
        }
        return canonicalizer(list.get(1)).withoutComments();
    }
}
