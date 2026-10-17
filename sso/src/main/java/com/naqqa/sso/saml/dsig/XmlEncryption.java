package com.naqqa.sso.saml.dsig;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.xml.XmlElement;
import com.naqqa.sso.util.Bytes;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class XmlEncryption {
    public static final String XENC_NS = "http://www.w3.org/2001/04/xmlenc#";
    public static final String XENC11_NS = "http://www.w3.org/2009/xmlenc11#";
    public static final String TYPE_ELEMENT = "http://www.w3.org/2001/04/xmlenc#Element";
    public static final String AES256_GCM = "http://www.w3.org/2009/xmlenc11#aes256-gcm";
    public static final String AES128_GCM = "http://www.w3.org/2009/xmlenc11#aes128-gcm";
    public static final String RSA_OAEP = "http://www.w3.org/2009/xmlenc11#rsa-oaep";
    public static final String RSA_OAEP_MGF1P = "http://www.w3.org/2001/04/xmlenc#rsa-oaep-mgf1p";
    public static final String MGF1_SHA1 = "http://www.w3.org/2009/xmlenc11#mgf1sha1";
    public static final String MGF1_SHA256 = "http://www.w3.org/2009/xmlenc11#mgf1sha256";

    private XmlEncryption() {
    }

    public static String encrypt(byte[] plaintext, PublicKey recipient) {
        if (!(recipient instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < Dsig.MIN_RSA_BITS) {
            throw new SsoException("XENC_UNSUPPORTED_KEY");
        }
        byte[] cek = Bytes.random(32);
        byte[] iv = Bytes.random(12);
        try {
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = aes.doFinal(plaintext);
            Cipher oaep = Cipher.getInstance("RSA/ECB/OAEPPadding");
            oaep.init(Cipher.ENCRYPT_MODE, recipient, oaepSpec(MGF1ParameterSpec.SHA256));
            byte[] wrapped = oaep.doFinal(cek);
            return "<xenc:EncryptedData xmlns:xenc=\"" + XENC_NS + "\" Type=\"" + TYPE_ELEMENT + "\">"
                    + "<xenc:EncryptionMethod Algorithm=\"" + AES256_GCM + "\"/>"
                    + "<ds:KeyInfo xmlns:ds=\"" + Dsig.NS + "\">"
                    + "<xenc:EncryptedKey>"
                    + "<xenc:EncryptionMethod Algorithm=\"" + RSA_OAEP + "\">"
                    + "<ds:DigestMethod Algorithm=\"" + Dsig.SHA256 + "\"/>"
                    + "<xenc11:MGF xmlns:xenc11=\"" + XENC11_NS + "\" Algorithm=\"" + MGF1_SHA256 + "\"/>"
                    + "</xenc:EncryptionMethod>"
                    + "<xenc:CipherData><xenc:CipherValue>" + Dsig.base64(wrapped) + "</xenc:CipherValue></xenc:CipherData>"
                    + "</xenc:EncryptedKey>"
                    + "</ds:KeyInfo>"
                    + "<xenc:CipherData><xenc:CipherValue>" + Dsig.base64(Bytes.concat(iv, ct)) + "</xenc:CipherValue></xenc:CipherData>"
                    + "</xenc:EncryptedData>";
        } catch (GeneralSecurityException e) {
            throw new SsoException("XENC_ENCRYPT_FAILED");
        } finally {
            Bytes.wipe(cek);
        }
    }

    public static byte[] decrypt(XmlElement container, PrivateKey key) {
        if (!(key instanceof RSAPrivateKey)) {
            throw new SsoException("XENC_UNSUPPORTED_KEY");
        }
        List<XmlElement> data = container.childElements(XENC_NS, "EncryptedData");
        if (data.size() != 1) {
            throw new SsoException("XENC_MALFORMED");
        }
        XmlElement encryptedData = data.get(0);
        String type = encryptedData.attribute("Type");
        if (type != null && !type.equals(TYPE_ELEMENT)) {
            throw new SsoException("XENC_MALFORMED");
        }
        XmlElement method = encryptedData.requiredChild(XENC_NS, "EncryptionMethod");
        String alg = method.attribute("Algorithm");
        int keyLength;
        if (AES256_GCM.equals(alg)) {
            keyLength = 32;
        } else if (AES128_GCM.equals(alg)) {
            keyLength = 16;
        } else {
            throw new SsoException("XENC_ALGORITHM");
        }
        List<XmlElement> keys = new ArrayList<>();
        XmlElement keyInfo = encryptedData.optionalChild(Dsig.NS, "KeyInfo");
        if (keyInfo != null) {
            keys.addAll(keyInfo.childElements(XENC_NS, "EncryptedKey"));
        }
        keys.addAll(container.childElements(XENC_NS, "EncryptedKey"));
        if (keys.isEmpty() || keys.size() > 4) {
            throw new SsoException("XENC_MALFORMED");
        }
        byte[] payload = cipherValue(encryptedData);
        if (payload.length < 12 + 16) {
            throw new SsoException("XENC_MALFORMED");
        }
        for (XmlElement ek : keys) {
            byte[] cek = unwrap(ek, key, keyLength);
            if (cek == null) {
                continue;
            }
            try {
                Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
                aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"),
                        new GCMParameterSpec(128, Arrays.copyOfRange(payload, 0, 12)));
                return aes.doFinal(payload, 12, payload.length - 12);
            } catch (GeneralSecurityException e) {
                continue;
            } finally {
                Bytes.wipe(cek);
            }
        }
        throw new SsoException("XENC_DECRYPT_FAILED");
    }

    private static byte[] unwrap(XmlElement encryptedKey, PrivateKey key, int keyLength) {
        XmlElement method = encryptedKey.requiredChild(XENC_NS, "EncryptionMethod");
        String alg = method.attribute("Algorithm");
        XmlElement digest = method.optionalChild(Dsig.NS, "DigestMethod");
        if (digest == null || !Dsig.SHA256.equals(digest.attribute("Algorithm"))) {
            throw new SsoException("XENC_ALGORITHM");
        }
        if (method.optionalChild(XENC_NS, "OAEPparams") != null) {
            throw new SsoException("XENC_ALGORITHM");
        }
        MGF1ParameterSpec mgf;
        if (RSA_OAEP.equals(alg)) {
            XmlElement m = method.optionalChild(XENC11_NS, "MGF");
            String mgfAlg = m == null ? MGF1_SHA1 : m.attribute("Algorithm");
            if (MGF1_SHA256.equals(mgfAlg)) {
                mgf = MGF1ParameterSpec.SHA256;
            } else if (MGF1_SHA1.equals(mgfAlg)) {
                mgf = MGF1ParameterSpec.SHA1;
            } else {
                throw new SsoException("XENC_ALGORITHM");
            }
        } else if (RSA_OAEP_MGF1P.equals(alg)) {
            mgf = MGF1ParameterSpec.SHA1;
        } else {
            throw new SsoException("XENC_ALGORITHM");
        }
        byte[] wrapped = cipherValue(encryptedKey);
        try {
            Cipher oaep = Cipher.getInstance("RSA/ECB/OAEPPadding");
            oaep.init(Cipher.DECRYPT_MODE, key, oaepSpec(mgf));
            byte[] cek = oaep.doFinal(wrapped);
            if (cek.length != keyLength) {
                Bytes.wipe(cek);
                return null;
            }
            return cek;
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private static OAEPParameterSpec oaepSpec(MGF1ParameterSpec mgf) {
        return new OAEPParameterSpec("SHA-256", "MGF1", mgf, PSource.PSpecified.DEFAULT);
    }

    private static byte[] cipherValue(XmlElement parent) {
        XmlElement cd = parent.requiredChild(XENC_NS, "CipherData");
        if (cd.optionalChild(XENC_NS, "CipherReference") != null) {
            throw new SsoException("XENC_MALFORMED");
        }
        return Dsig.base64(cd.requiredChild(XENC_NS, "CipherValue").simpleText(), "XENC_MALFORMED");
    }
}
