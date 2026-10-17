package com.naqqa.auth.support;

import com.naqqa.auth.service.auth.AuthEmailService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CapturingEmailService extends AuthEmailService {

    public record Mail(String to, String subject, String body) {
    }

    static final Pattern CONFIRMATION_CODE = Pattern.compile("confirmation code: <strong>([^<]+)</strong>");

    private final List<Mail> mails = new CopyOnWriteArrayList<>();

    @Override
    public void sendEmail(String email, String subject, String message) {
        mails.add(new Mail(email, subject, message));
    }

    @Override
    public String sendEmail(String email, String subjectEmail, String template, Map<String, String> configs) {
        mails.add(new Mail(email, subjectEmail, template));
        return "";
    }

    public List<Mail> to(String email) {
        return mails.stream().filter(m -> m.to().equalsIgnoreCase(email)).toList();
    }

    public Optional<Mail> last(String email) {
        List<Mail> list = to(email);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1));
    }

    public String confirmationCode(String email) {
        Mail mail = last(email).orElseThrow();
        Matcher m = CONFIRMATION_CODE.matcher(mail.body());
        if (!m.find()) {
            throw new IllegalStateException("no confirmation code");
        }
        return m.group(1);
    }
}
