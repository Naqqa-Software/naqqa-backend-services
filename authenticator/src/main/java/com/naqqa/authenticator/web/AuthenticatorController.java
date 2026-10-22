package com.naqqa.authenticator.web;

import com.naqqa.authenticator.domain.TotpEnrollmentStart;
import com.naqqa.authenticator.domain.VerifiedChallenge;
import com.naqqa.authenticator.dto.AuthenticatorDtos.ChallengeRequest;
import com.naqqa.authenticator.dto.AuthenticatorDtos.FactorResponse;
import com.naqqa.authenticator.dto.AuthenticatorDtos.LoginOkResponse;
import com.naqqa.authenticator.dto.AuthenticatorDtos.RecoveryCodesResponse;
import com.naqqa.authenticator.dto.AuthenticatorDtos.StatusResponse;
import com.naqqa.authenticator.dto.AuthenticatorDtos.TotpConfirmRequest;
import com.naqqa.authenticator.dto.AuthenticatorDtos.TotpEnrollmentResponse;
import com.naqqa.authenticator.dto.AuthenticatorDtos.VerifyRequest;
import com.naqqa.authenticator.service.AuthenticatorErrors;
import com.naqqa.authenticator.service.AuthenticatorService;
import com.naqqa.authenticator.spi.AuthenticatorUserResolver;
import com.naqqa.authenticator.spi.MfaLoginCompleter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("${naqqa.authenticator.path:/api/auth/mfa}")
public class AuthenticatorController {

    private final AuthenticatorService service;
    private final AuthenticatorUserResolver users;
    private final ObjectProvider<MfaLoginCompleter> completer;

    public AuthenticatorController(AuthenticatorService service, AuthenticatorUserResolver users,
                                   ObjectProvider<MfaLoginCompleter> completer) {
        this.service = service;
        this.users = users;
        this.completer = completer;
    }

    @PostMapping("/totp/enroll")
    public ResponseEntity<TotpEnrollmentResponse> enroll() {
        String userId = currentUser();
        TotpEnrollmentStart start = service.beginTotpEnrollment(userId, users.accountName(userId));
        return noStore(new TotpEnrollmentResponse(start.enrollmentId(), start.otpauthUri(), start.secret(), start.expiresAt()));
    }

    @PostMapping("/totp/confirm")
    public ResponseEntity<RecoveryCodesResponse> confirm(@RequestBody(required = false) TotpConfirmRequest request) {
        String userId = currentUser();
        if (request == null) {
            throw AuthenticatorErrors.invalidRequest("Missing body.");
        }
        return noStore(new RecoveryCodesResponse(service.confirmTotpEnrollment(userId, request.enrollmentId(), request.code())));
    }

    @GetMapping("/factors")
    public ResponseEntity<List<FactorResponse>> factors() {
        String userId = currentUser();
        return noStore(service.listFactors(userId).stream().map(FactorResponse::from).toList());
    }

    @DeleteMapping("/factors/{factorId}")
    public StatusResponse removeFactor(@PathVariable String factorId) {
        service.removeFactor(currentUser(), factorId);
        return StatusResponse.OK;
    }

    @PostMapping("/recovery/regenerate")
    public ResponseEntity<RecoveryCodesResponse> regenerate() {
        return noStore(new RecoveryCodesResponse(service.regenerateRecoveryCodes(currentUser())));
    }

    @PostMapping("/challenge")
    public StatusResponse challenge(@RequestBody(required = false) ChallengeRequest request) {
        if (request == null) {
            throw AuthenticatorErrors.invalidRequest("Missing body.");
        }
        service.prepareChallenge(request.mfaToken(), request.method());
        return StatusResponse.SENT;
    }

    @PostMapping("/verify")
    public ResponseEntity<LoginOkResponse> verify(@RequestBody(required = false) VerifyRequest request,
                                                  HttpServletRequest httpRequest,
                                                  HttpServletResponse httpResponse) {
        if (request == null) {
            throw AuthenticatorErrors.invalidRequest("Missing body.");
        }
        MfaLoginCompleter loginCompleter = completer.getIfAvailable();
        if (loginCompleter == null) {
            throw AuthenticatorErrors.notConfigured();
        }
        VerifiedChallenge verified = service.verify(request.mfaToken(), request.method(), request.code());
        Object user = loginCompleter.complete(verified, httpRequest, httpResponse);
        return noStore(LoginOkResponse.of(user));
    }

    private String currentUser() {
        return users.currentUserId().orElseThrow(AuthenticatorErrors::unauthorized);
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
