package com.naqqa.auth.support;

import com.naqqa.sso.otp.Channel;
import com.naqqa.sso.otp.Transport;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public class CapturingTransport implements Transport {

    public record Message(Channel channel, String destination, String template, Map<String, String> vars) {
    }

    private final List<Message> messages = new CopyOnWriteArrayList<>();

    @Override
    public void send(Channel channel, String destination, String templateId, Map<String, String> vars) {
        messages.add(new Message(channel, destination, templateId, Map.copyOf(vars)));
    }

    public List<Message> to(String destination) {
        return messages.stream().filter(m -> m.destination().equalsIgnoreCase(destination)).toList();
    }

    public Optional<Message> last(String destination) {
        List<Message> list = to(destination);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1));
    }

    public List<Message> all() {
        return List.copyOf(messages);
    }
}
