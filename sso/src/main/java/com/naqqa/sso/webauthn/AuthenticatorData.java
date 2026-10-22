package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;

import java.util.Arrays;
import java.util.Map;

public record AuthenticatorData(byte[] rpIdHash, int flags, long signCount, AttestedCredentialData attestedCredentialData,
                                Map<Object, Object> extensions, byte[] raw) {
    public static final int FLAG_UP = 0x01;
    public static final int FLAG_UV = 0x04;
    public static final int FLAG_BE = 0x08;
    public static final int FLAG_BS = 0x10;
    public static final int FLAG_AT = 0x40;
    public static final int FLAG_ED = 0x80;
    public static final int MAX_BYTES = 16384;
    private static final int HEADER = 37;

    public AuthenticatorData {
        rpIdHash = rpIdHash.clone();
        raw = raw.clone();
        extensions = extensions == null ? null : Map.copyOf(extensions);
    }

    @Override
    public byte[] rpIdHash() {
        return rpIdHash.clone();
    }

    @Override
    public byte[] raw() {
        return raw.clone();
    }

    public boolean userPresent() {
        return (flags & FLAG_UP) != 0;
    }

    public boolean userVerified() {
        return (flags & FLAG_UV) != 0;
    }

    public boolean backupEligible() {
        return (flags & FLAG_BE) != 0;
    }

    public boolean backupState() {
        return (flags & FLAG_BS) != 0;
    }

    public boolean hasAttestedCredentialData() {
        return (flags & FLAG_AT) != 0;
    }

    public boolean hasExtensions() {
        return (flags & FLAG_ED) != 0;
    }

    public static AuthenticatorData parse(byte[] data) {
        if (data == null || data.length < HEADER) {
            throw new SsoException("webauthn.authdata.truncated");
        }
        if (data.length > MAX_BYTES) {
            throw new SsoException("webauthn.authdata.too_large");
        }
        byte[] rpIdHash = Arrays.copyOfRange(data, 0, 32);
        int flags = data[32] & 0xFF;
        long count = ((long) (data[33] & 0xFF) << 24) | ((data[34] & 0xFF) << 16) | ((data[35] & 0xFF) << 8) | (data[36] & 0xFF);
        int pos = HEADER;
        AttestedCredentialData attested = null;
        if ((flags & FLAG_AT) != 0) {
            if (data.length < pos + 18) {
                throw new SsoException("webauthn.authdata.truncated");
            }
            byte[] aaguid = Arrays.copyOfRange(data, pos, pos + 16);
            pos += 16;
            int idLen = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            if (idLen == 0 || idLen > AttestedCredentialData.MAX_CREDENTIAL_ID) {
                throw new SsoException("webauthn.authdata.credential_id_length");
            }
            if (data.length < pos + idLen) {
                throw new SsoException("webauthn.authdata.truncated");
            }
            byte[] credId = Arrays.copyOfRange(data, pos, pos + idLen);
            pos += idLen;
            if (pos >= data.length) {
                throw new SsoException("webauthn.authdata.truncated");
            }
            Cbor.Decoded key = Cbor.decodeFirst(data, pos);
            byte[] keyBytes = Arrays.copyOfRange(data, pos, key.end());
            CoseKey coseKey = CoseKey.fromMap(Cbor.asMap(key.value(), "webauthn.cose.invalid"), keyBytes);
            pos = key.end();
            attested = new AttestedCredentialData(aaguid, credId, coseKey);
        }
        Map<Object, Object> ext = null;
        if ((flags & FLAG_ED) != 0) {
            if (pos >= data.length) {
                throw new SsoException("webauthn.authdata.truncated");
            }
            Cbor.Decoded e = Cbor.decodeFirst(data, pos);
            ext = Cbor.asMap(e.value(), "webauthn.authdata.extensions_invalid");
            pos = e.end();
        }
        if (pos != data.length) {
            throw new SsoException("webauthn.authdata.trailing");
        }
        if ((flags & FLAG_BS) != 0 && (flags & FLAG_BE) == 0) {
            throw new SsoException("webauthn.authdata.backup_state_invalid");
        }
        return new AuthenticatorData(rpIdHash, flags, count, attested, ext, data);
    }
}
