package com.naqqa.elasticsearch.security;

import com.naqqa.elasticsearch.security.authc.ApiKeyService;
import com.naqqa.elasticsearch.security.authc.AuthenticationResult;
import com.naqqa.elasticsearch.security.authc.User;
import com.naqqa.elasticsearch.test.Test;

import java.time.Duration;
import java.util.List;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class ApiKeyServiceTest {

    @Test
    public void createAndAuthenticateSucceeds() {
        ApiKeyService service = new ApiKeyService();
        User owner = new User("alice", List.of("editor"));
        ApiKeyService.CreatedApiKey created = service.createApiKey(owner, "ci-key", List.of(), null);

        AuthenticationResult result = service.authenticate(created.credentials());
        assertTrue(result.isAuthenticated());
        assertEquals("alice", result.user().username());
    }

    @Test
    public void invalidatedKeyIsRejected() {
        ApiKeyService service = new ApiKeyService();
        User owner = new User("bob", List.of("viewer"));
        ApiKeyService.CreatedApiKey created = service.createApiKey(owner, "temp-key", List.of(), null);

        assertTrue(service.invalidateApiKey(created.id()));
        AuthenticationResult result = service.authenticate(created.credentials());
        assertEquals(AuthenticationResult.Status.TERMINATE, result.status());
    }

    @Test
    public void expiredKeyIsRejected() {
        ApiKeyService service = new ApiKeyService();
        User owner = new User("carl", List.of("viewer"));
        ApiKeyService.CreatedApiKey created = service.createApiKey(owner, "short-key", List.of(), Duration.ofSeconds(-5));

        AuthenticationResult result = service.authenticate(created.credentials());
        assertEquals(AuthenticationResult.Status.TERMINATE, result.status());
    }

    @Test
    public void wrongSecretFails() {
        ApiKeyService service = new ApiKeyService();
        User owner = new User("dave", List.of("viewer"));
        ApiKeyService.CreatedApiKey created = service.createApiKey(owner, "key", List.of(), null);

        String credentials = created.credentials();
        int mid = credentials.length() / 2;
        char flipped = credentials.charAt(mid) == 'A' ? 'B' : 'A';
        String tampered = credentials.substring(0, mid) + flipped + credentials.substring(mid + 1);
        AuthenticationResult result = service.authenticate(tampered);
        assertTrue(result.status() != AuthenticationResult.Status.SUCCESS);
    }
}
