package com.naqqa.sso.otp;

import java.util.Map;

public final class NoopTransport implements Transport {
    @Override
    public void send(Channel channel, String destination, String templateId, Map<String, String> vars) {
    }
}
