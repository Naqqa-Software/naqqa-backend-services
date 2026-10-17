package com.naqqa.sso.saml.idp;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.mfa.StepUp;
import com.naqqa.sso.saml.Saml;
import com.naqqa.sso.saml.xml.XmlLimits;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;

public record SamlIdpConfig(String entityId, String ssoUrl, String sloUrl, PrivateKey signingKey,
                            X509Certificate signingCertificate, byte[] pairwiseSecret, Duration assertionLifetime,
                            Duration clockSkew, Duration requestMaxAge, Map<String, String> authnContextByAcr,
                            XmlLimits limits, boolean wantAuthnRequestsSigned) {
    public static final Map<String, String> DEFAULT_ACR = Map.of(
            "pwd", Saml.AC_PASSWORD_PROTECTED,
            "otp", Saml.AC_MFA,
            "mfa", Saml.AC_MFA,
            "hwk", Saml.AC_MFA,
            "phr", Saml.AC_MFA,
            StepUp.ACR_PASSWORD, Saml.AC_PASSWORD_PROTECTED,
            StepUp.ACR_MFA, Saml.AC_MFA,
            StepUp.ACR_PHISHING_RESISTANT, Saml.AC_MFA);

    public SamlIdpConfig {
        if (entityId == null || entityId.isEmpty() || ssoUrl == null || signingKey == null || signingCertificate == null) {
            throw new SsoException("SAML_IDP_CONFIG");
        }
        if (pairwiseSecret == null || pairwiseSecret.length < 32) {
            throw new SsoException("SAML_IDP_CONFIG");
        }
        pairwiseSecret = pairwiseSecret.clone();
        assertionLifetime = assertionLifetime == null ? Duration.ofMinutes(5) : assertionLifetime;
        clockSkew = clockSkew == null ? Duration.ofMinutes(3) : clockSkew;
        requestMaxAge = requestMaxAge == null ? Duration.ofMinutes(5) : requestMaxAge;
        authnContextByAcr = authnContextByAcr == null ? DEFAULT_ACR : Map.copyOf(authnContextByAcr);
        limits = limits == null ? XmlLimits.DEFAULT : limits;
    }

    @Override
    public byte[] pairwiseSecret() {
        return pairwiseSecret.clone();
    }

    public static SamlIdpConfig of(String entityId, String ssoUrl, String sloUrl, PrivateKey key,
                                   X509Certificate certificate, byte[] pairwiseSecret) {
        return new SamlIdpConfig(entityId, ssoUrl, sloUrl, key, certificate, pairwiseSecret, null, null, null,
                null, null, false);
    }

    @Override
    public String toString() {
        return "SamlIdpConfig[entityId=" + entityId + "]";
    }
}
