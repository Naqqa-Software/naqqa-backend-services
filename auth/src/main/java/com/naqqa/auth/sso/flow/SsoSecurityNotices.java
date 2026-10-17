package com.naqqa.auth.sso.flow;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.service.security.CredentialChangeListener;
import com.naqqa.sso.otp.Channel;
import com.naqqa.sso.otp.Transport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class SsoSecurityNotices implements CredentialChangeListener {

    public static final String TEMPLATE = "security_notice";
    public static final String RECOVERY_CODE_USED = "recovery_code_used";
    public static final String FACTOR_ADDED = "factor_added";
    public static final String FACTOR_REMOVED = "factor_removed";
    public static final String RECOVERY_REGENERATED = "recovery_regenerated";
    public static final String FACTORS_RESET = "factors_reset";
    public static final String PASSWORD_RESET = "password_reset";
    public static final String ACCOUNT_LINKED = "account_linked";

    private final Transport transport;

    public SsoSecurityNotices(Transport transport) {
        this.transport = transport;
    }

    @Override
    public void onCredentialsChanged(UserEntity user, String reason) {
        if (PASSWORD_RESET.equals(reason)) {
            send(user, PASSWORD_RESET);
        }
    }

    public void send(UserEntity user, String event) {
        if (user == null) {
            return;
        }
        deliver(Channel.EMAIL, user.getEmail(), event);
        deliver(Channel.SMS, user.getPhoneNumber(), event);
    }

    private void deliver(Channel channel, String destination, String event) {
        if (destination == null || destination.isBlank()) {
            return;
        }
        try {
            transport.send(channel, destination, TEMPLATE, Map.of("event", event));
        } catch (RuntimeException e) {
            log.warn("security notice not delivered on channel {}", channel);
        }
    }
}
