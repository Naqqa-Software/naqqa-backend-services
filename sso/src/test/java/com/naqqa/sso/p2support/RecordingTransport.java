package com.naqqa.sso.p2support;

import com.naqqa.sso.otp.Channel;
import com.naqqa.sso.otp.Transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RecordingTransport implements Transport {
    public record Sent(Channel channel, String destination, String templateId, Map<String, String> vars) {
    }

    public final List<Sent> sent = new ArrayList<>();

    @Override
    public void send(Channel channel, String destination, String templateId, Map<String, String> vars) {
        sent.add(new Sent(channel, destination, templateId, vars));
    }

    public Sent last() {
        return sent.get(sent.size() - 1);
    }
}
