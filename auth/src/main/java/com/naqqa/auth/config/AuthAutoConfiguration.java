package com.naqqa.auth.config;

import com.naqqa.auth.controller.AuthController;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.repository.redis.PasswordResetRepository;
import com.naqqa.auth.repository.redis.RegisterRecordRepository;
import com.naqqa.auth.roles.RoleProvider;
import com.naqqa.auth.service.*;
import com.naqqa.auth.security.JwtService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;

@AutoConfiguration
public class AuthAutoConfiguration {

    // ----------------------------------------
    //  CONTROLLER OVERRIDABLE BY MAIN APPLICATION
    // ----------------------------------------

    @Bean
    @ConditionalOnMissingBean(AuthController.class)
    public AuthController authController(AuthService authService, AuthEmailService authEmailService, EmailMessages emailMessages) {
        return new AuthController(authService, authEmailService, emailMessages);
    }

    // ----------------------------------------
    //  SERVICES OVERRIDABLE BY MAIN APPLICATION
    // ----------------------------------------

    @Bean
    @ConditionalOnMissingBean(AuthService.class)
    public AuthService defaultAuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RegisterRecordRepository registerRecordRepository,
            PasswordResetRepository passwordResetRepository,
            JwtService jwtService,
            RoleProvider roleProvider,
            TokenService tokenService,
            CodeGenService codeGenService,
            AuthEmailService authEmailService,
            EmailMessages emailMessages
    ) {
        return new DefaultAuthService(
                userRepository,
                passwordEncoder,
                registerRecordRepository,
                passwordResetRepository,
                jwtService,
                roleProvider,
                tokenService,
                codeGenService,
                authEmailService,
                emailMessages
        );
    }

//    @Bean
//    @ConditionalOnMissingBean(TokenService.class)
//    public TokenService defaultTokenService(JwtService jwtService) {
//        return new DefaultTokenService(jwtService);
//    }
//
//    @Bean
//    @ConditionalOnMissingBean(CodeGenService.class)
//    public CodeGenService defaultCodeGenService() {
//        return new DefaultCodeGenService();
//    }
//
//    @Bean
//    @ConditionalOnMissingBean(AuthEmailService.class)
//    public AuthEmailService defaultAuthEmailService() {
//        return new DefaultAuthEmailService();
//    }
//
//    @Bean
//    @ConditionalOnMissingBean(RoleProvider.class)
//    public RoleProvider defaultRoleProvider() {
//        return new DefaultRoleProvider();
//    }
}
