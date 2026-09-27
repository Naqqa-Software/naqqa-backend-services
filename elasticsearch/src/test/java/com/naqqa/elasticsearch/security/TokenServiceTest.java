package com.naqqa.elasticsearch.security;

import com.naqqa.elasticsearch.security.authc.AuthenticationResult;
import com.naqqa.elasticsearch.security.authc.TokenService;
import com.naqqa.elasticsearch.security.authc.User;
import com.naqqa.elasticsearch.test.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class TokenServiceTest {

    @Test
    public void createAndAuthenticateWithBearerHeader() {
        TokenService service = new TokenService();
        TokenService.IssuedTokens tokens = service.createToken(new User("alice", List.of("viewer")));

        AuthenticationResult result = service.authenticate("Bearer " + tokens.accessToken());
        assertTrue(result.isAuthenticated());
        assertEquals("alice", result.user().username());
    }

    @Test
    public void refreshIssuesNewTokenAndInvalidatesOld() {
        TokenService service = new TokenService();
        TokenService.IssuedTokens tokens = service.createToken(new User("bob", List.of("editor")));

        Optional<TokenService.IssuedTokens> refreshed = service.refresh(tokens.refreshToken());
        assertTrue(refreshed.isPresent());

        AuthenticationResult oldResult = service.authenticate("Bearer " + tokens.accessToken());
        assertEquals(AuthenticationResult.Status.TERMINATE, oldResult.status());

        AuthenticationResult newResult = service.authenticate("Bearer " + refreshed.get().accessToken());
        assertTrue(newResult.isAuthenticated());
    }

    @Test
    public void invalidateBlocksFurtherAuthentication() {
        TokenService service = new TokenService();
        TokenService.IssuedTokens tokens = service.createToken(new User("carl", List.of()), Duration.ofMinutes(5));

        assertTrue(service.invalidate(tokens.accessToken()));
        AuthenticationResult result = service.authenticate("Bearer " + tokens.accessToken());
        assertEquals(AuthenticationResult.Status.TERMINATE, result.status());
    }
}
