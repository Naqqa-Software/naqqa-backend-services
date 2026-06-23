package com.naqqa.auth.config;

import com.naqqa.auth.config.auth.EmailMessages;
import com.naqqa.auth.controller.AuthController;
import com.naqqa.auth.persistence.AuthSequenceListener;
import com.naqqa.auth.persistence.SequenceGenerator;
import com.naqqa.auth.exceptionhandlers.AuthExceptionHandler;
import com.naqqa.auth.exceptionhandlers.AuthoritiesExceptionHandler;
import com.naqqa.auth.exceptionhandlers.GlobalExceptionHandler;
import com.naqqa.auth.repository.RefreshTokenRepository;
import com.naqqa.auth.repository.RoleRepository;
import com.naqqa.auth.repository.UserDeviceRepository;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.repository.redis.PasswordResetRepository;
import com.naqqa.auth.repository.redis.RegisterRecordRepository;
import com.naqqa.auth.roles.RoleProvider;
import com.naqqa.auth.security.JwtService;
import com.naqqa.auth.service.auth.*;
import com.naqqa.auth.service.security.CodeGenService;
import com.naqqa.auth.service.security.DeviceSessionService;
import com.naqqa.auth.service.security.TokenService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.security.crypto.password.PasswordEncoder;

@AutoConfiguration
@EnableMongoRepositories(basePackages = "com.naqqa.auth.repository")
public class AuthAutoConfiguration {

    // ----------------------------------------
    //  MONGO SEQUENCE / ID GENERATION
    // ----------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public SequenceGenerator authSequenceGenerator(MongoOperations mongoOperations) {
        return new SequenceGenerator(mongoOperations);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthSequenceListener authSequenceListener(SequenceGenerator sequenceGenerator) {
        return new AuthSequenceListener(sequenceGenerator);
    }

    // ----------------------------------------
    //  EXCEPTION HANDLERS
    // ----------------------------------------

    @Bean
    @ConditionalOnMissingBean(AuthExceptionHandler.class)
    public AuthExceptionHandler authExceptionHandler() {
        return new AuthExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean(GlobalExceptionHandler.class)
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean(AuthoritiesExceptionHandler.class)
    public AuthoritiesExceptionHandler authoritiesExceptionHandler() {
        return new AuthoritiesExceptionHandler();
    }

    // ----------------------------------------
    //  CONTROLLER OVERRIDABLE BY MAIN APPLICATION
    // ----------------------------------------

    @Bean
    @ConditionalOnMissingBean(AuthController.class)
    public AuthController authController(
            AuthService authService, 
            AuthEmailService authEmailService, 
            EmailMessages emailMessages,
            DeviceSessionService deviceSessionService,
            UserRepository userRepository
    ) {
        return new AuthController(authService, authEmailService, emailMessages, deviceSessionService, userRepository);
    }

    // ----------------------------------------
    //  SERVICES OVERRIDABLE BY MAIN APPLICATION
    // ----------------------------------------

    @Bean
    @ConditionalOnMissingBean(AuthService.class)
    public AuthService defaultAuthService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserDeviceRepository userDeviceRepository,
            DeviceSessionService deviceSessionService,
            PasswordEncoder passwordEncoder,
            RegisterRecordRepository registerRecordRepository,
            PasswordResetRepository passwordResetRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            RoleProvider roleProvider,
            TokenService tokenService,
            CodeGenService codeGenService,
            AuthEmailService authEmailService,
            EmailMessages emailMessages
    ) {
        return new DefaultAuthService(
                userRepository,
                roleRepository,
                userDeviceRepository,
                deviceSessionService,
                passwordEncoder,
                registerRecordRepository,
                passwordResetRepository,
                refreshTokenRepository,
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
