package com.naqqa.auth.config.social;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "social")
public class SocialLoginProperties {

    private Google google = new Google();
    private Facebook facebook = new Facebook();
    private Apple apple = new Apple();

    @Getter
    @Setter
    public static class Google {
        private boolean enabled;
        private String clientId;
        private String clientSecret;
    }

    @Getter
    @Setter
    public static class Facebook {
        private boolean enabled;
        private String clientId;
        private String clientSecret;
    }

    @Getter
    @Setter
    public static class Apple {
        private boolean enabled;
        private String clientId;
        private String clientSecret;
        private String keyId;
        private String teamId;
        private String privateKey;
    }
}
