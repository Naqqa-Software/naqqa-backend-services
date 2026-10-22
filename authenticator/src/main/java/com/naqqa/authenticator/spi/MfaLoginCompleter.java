package com.naqqa.authenticator.spi;

import com.naqqa.authenticator.domain.VerifiedChallenge;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface MfaLoginCompleter {

    Object complete(VerifiedChallenge verified, HttpServletRequest request, HttpServletResponse response);
}
