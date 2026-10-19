package com.naqqa.auth.config.auth;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class EmailMessages {

    // getters (you can keep @Getter if you want)
    private final String appUrl;
    private final String platformName;
    private final String primaryColor;

    public EmailMessages(
            @Value("${app.url}") String appUrl,
            @Value("${app.platform-name}") String platformName,
            @Value("${app.email.primary-color:#3ECF81}") String primaryColor
    ) {
        this.appUrl = appUrl;
        this.platformName = platformName;
        this.primaryColor = primaryColor;
    }

    public String getEmailAddressConfirmationMessage(String uuid, String code) {
        String confirmUrl = appUrl + "/auth/confirm-email?uuid=" + uuid + "&code=" + code;

        return """
                <h1 style='color: %s;'>Welcome to %s!</h1>
                <p>Hello,</p>
                <p>Please confirm your email address by clicking the button below:</p>
                <div style="margin: 30px 0;">
                    <a href='%s'
                       style="background-color: %s;
                              color: white;
                              padding: 14px 28px;
                              text-decoration: none;
                              border-radius: 6px;
                              font-weight: bold;
                              display: inline-block;">
                        Confirm Email
                    </a>
                </div>
                <p>Or use this confirmation code: <strong>%s</strong></p>
                <p>If you didn't create an account, you can safely ignore this email.</p>
                <p>Thank you,<br>The %s Team</p>
                """.formatted(primaryColor, platformName, confirmUrl, primaryColor, code, platformName);
    }

    public String getResetPasswordEmailMessage(String email, String uuid) {
        String resetButtonUrl = appUrl + "/auth/change-password?uuid=" + uuid + "&email=" + email;

        return """
                <h1 style='color: %s;'>Reset Your Password</h1>
                <p>Hello,</p>
                <p>You’ve requested to reset your password. Click the button below to proceed:</p>
                <div style="margin: 30px 0;">
                    <a href='%s' 
                       style="background-color: %s; 
                              color: white; 
                              padding: 14px 28px; 
                              text-decoration: none; 
                              border-radius: 6px; 
                              font-weight: bold;
                              display: inline-block;">
                        Reset Password
                    </a>
                </div>
                <p>If you didn't request this, you can safely ignore this email.</p>
                <p>Thank you,<br>The %s Team</p>
                """.formatted(primaryColor, resetButtonUrl, primaryColor, platformName);
    }

    public String getAlreadyRegisteredMessage() {
        String loginUrl = appUrl + "/auth/login";
        return """
                <h1 style='color: %s;'>You already have a %s account</h1>
                <p>Hello,</p>
                <p>Someone (probably you) tried to create a new account with this e-mail address, but it is already registered.</p>
                <p><a href='%s'>Sign in</a>, or use "Forgot password" on the sign-in page if you no longer remember it.</p>
                <p>If this wasn't you, you can ignore this e-mail: your account was not changed.</p>
                <p>Thank you,<br>The %s Team</p>
                """.formatted(primaryColor, platformName, loginUrl, platformName);
    }

    public String getAlreadyRegisteredSubject() {
        return "You already have an account";
    }

    public String getSubjectEmailConfirmation() {
        return "Confirm your email address";
    }

    public String getResetPasswordSubject() {
        return "Password reset link";
    }
}