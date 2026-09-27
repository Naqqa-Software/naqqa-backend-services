package com.naqqa.elasticsearch.security;

import com.naqqa.elasticsearch.security.authc.AuthenticationResult;
import com.naqqa.elasticsearch.security.authc.BasicAuthHeader;
import com.naqqa.elasticsearch.security.authc.FileRealm;
import com.naqqa.elasticsearch.security.authc.InMemorySecurityIndexStore;
import com.naqqa.elasticsearch.security.authc.NativeRealm;
import com.naqqa.elasticsearch.security.authc.PasswordHashers;
import com.naqqa.elasticsearch.security.authc.UsernamePasswordToken;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class RealmAndBasicAuthTest {

    @Test
    public void basicAuthHeaderParsesUsernameAndPassword() {
        String header = "Basic " + java.util.Base64.getEncoder().encodeToString("alice:secret".getBytes());
        Optional<UsernamePasswordToken> token = BasicAuthHeader.parse(header);
        assertTrue(token.isPresent());
        assertEquals("alice", token.get().username());
        assertEquals("secret", new String(token.get().password()));
    }

    @Test
    public void nativeRealmAuthenticatesAgainstIndexStore() {
        InMemorySecurityIndexStore store = new InMemorySecurityIndexStore();
        store.putUser("alice", Map.of(
                "password_hash", PasswordHashers.hashWithPbkdf2("hunter2".toCharArray()),
                "roles", List.of("editor"),
                "enabled", true));
        NativeRealm realm = new NativeRealm("native", store);

        AuthenticationResult success = realm.authenticate(new UsernamePasswordToken("alice", "hunter2".toCharArray()));
        assertTrue(success.isAuthenticated());
        assertEquals(List.of("editor"), success.user().roles());

        AuthenticationResult failure = realm.authenticate(new UsernamePasswordToken("alice", "wrong".toCharArray()));
        assertEquals(AuthenticationResult.Status.UNSUCCESSFUL, failure.status());
    }

    @Test
    public void fileRealmParsesUsersAndRolesFiles() throws Exception {
        java.nio.file.Path usersFile = java.nio.file.Files.createTempFile("users", ".tmp");
        java.nio.file.Path rolesFile = java.nio.file.Files.createTempFile("users_roles", ".tmp");
        try {
            String hash = PasswordHashers.hashWithSsha256("filepass".toCharArray());
            java.nio.file.Files.writeString(usersFile, "bob:" + hash + "\n");
            java.nio.file.Files.writeString(rolesFile, "viewer:bob,carol\n");

            FileRealm realm = FileRealm.loadFromFiles("file", usersFile, rolesFile);
            AuthenticationResult result = realm.authenticate(new UsernamePasswordToken("bob", "filepass".toCharArray()));
            assertTrue(result.isAuthenticated());
            assertEquals(List.of("viewer"), result.user().roles());
        } finally {
            java.nio.file.Files.deleteIfExists(usersFile);
            java.nio.file.Files.deleteIfExists(rolesFile);
        }
    }
}
