package com.naqqa.sso.otp;

import java.util.Map;

public interface Transport {
    void send(Channel channel, String destination, String templateId, Map<String, String> vars);
}
