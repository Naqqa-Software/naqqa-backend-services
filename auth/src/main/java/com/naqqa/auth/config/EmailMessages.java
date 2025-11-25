package com.naqqa.auth.config;

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

    public String getEmailAddressConfirmationMessage(String code) {
        return "<h1 style='color: " + primaryColor + ";'>Welcome to " + platformName + "!</h1>" +
                "<p>Hello,</p>" +
                "<p>This is the code needed to confirm your email address: <strong>" + code + "</strong></p>";
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

    public String getSubjectEmailConfirmation() {
        return "Confirm your email address";
    }

    public String getResetPasswordSubject() {
        return "Password reset link";
    }
}