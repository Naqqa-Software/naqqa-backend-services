package com.naqqa.elasticsearch.security;

import com.naqqa.elasticsearch.security.authc.PasswordHashers;
import com.naqqa.elasticsearch.security.authc.Pbkdf2PasswordHasher;
import com.naqqa.elasticsearch.security.authc.Ssha256PasswordHasher;
import com.naqqa.elasticsearch.test.Test;

import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class PasswordHasherTest {

    @Test
    public void pbkdf2RoundTripVerifies() {
        String hash = PasswordHashers.hashWithPbkdf2("correct horse battery staple".toCharArray());
        assertTrue(hash.startsWith(Pbkdf2PasswordHasher.PREFIX));
        assertTrue(PasswordHashers.verify("correct horse battery staple".toCharArray(), hash));
        assertFalse(PasswordHashers.verify("wrong password".toCharArray(), hash));
    }

    @Test
    public void ssha256RoundTripVerifies() {
        String hash = PasswordHashers.hashWithSsha256("s3cr3t".toCharArray());
        assertTrue(hash.startsWith(Ssha256PasswordHasher.PREFIX));
        assertTrue(PasswordHashers.verify("s3cr3t".toCharArray(), hash));
        assertFalse(PasswordHashers.verify("nope".toCharArray(), hash));
    }

    @Test
    public void differentSaltsProduceDifferentHashes() {
        String h1 = PasswordHashers.hashWithPbkdf2("same".toCharArray());
        String h2 = PasswordHashers.hashWithPbkdf2("same".toCharArray());
        assertFalse(h1.equals(h2));
    }
}
