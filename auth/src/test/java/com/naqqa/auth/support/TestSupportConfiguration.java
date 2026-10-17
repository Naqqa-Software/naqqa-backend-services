package com.naqqa.auth.support;

import com.naqqa.auth.roles.RoleProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;


@TestConfiguration
public class TestSupportConfiguration {

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    public CapturingTransport testTransport() {
        return new CapturingTransport();
    }

    @Bean
    @Primary
    public CapturingEmailService testEmailService() {
        return new CapturingEmailService();
    }

    @Bean
    @Primary
    public RoleProvider testRoleProvider() {
        return new TestRoleProvider();
    }

}
