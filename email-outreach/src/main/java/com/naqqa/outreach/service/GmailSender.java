package com.naqqa.outreach.service;

import com.naqqa.outreach.entity.OutreachProfileEntity;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.util.Properties;
import java.util.UUID;

/**
 * Sends plain-text outreach mail through a profile's warmed Gmail account over SMTP (465/SSL),
 * with the same to/subject/body normalization + signature as the script. Follow-ups thread via
 * In-Reply-To / References. Returns the generated Message-ID.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GmailSender {

    private final SecretCipher cipher;
    private final com.naqqa.outreach.config.OutreachProperties props;

    public record SendResult(String messageId) {
    }

    public SendResult send(OutreachProfileEntity profile, String toEmail, String subject, String body,
                           String inReplyToMessageId) throws Exception {
        String to = toEmail.trim().replaceAll("[\\r\\n\\t\\s]+", "");
        String subj = subject.trim().replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ");
        String normBody = body.trim()
                .replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n")
                .replaceAll("\\t", "  ").replaceAll("[^\\S\\n]{2,}", " ");
        String sig = profile.getSignature() == null ? "" : profile.getSignature();
        String footer = props.getUnsubscribeFooter() == null ? "" : props.getUnsubscribeFooter();
        String fullBody = normBody + sig + footer;

        Session session = session(profile);
        MimeMessage msg = new MimeMessage(session);
        String domain = profile.getFromEmail().substring(profile.getFromEmail().indexOf('@') + 1);
        String messageId = "<" + UUID.randomUUID() + "@" + domain + ">";
        msg.setHeader("Message-ID", messageId);

        msg.setFrom(from(profile));
        msg.setReplyTo(new Address[]{from(profile)});
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        if (inReplyToMessageId != null && !inReplyToMessageId.isBlank()) {
            msg.setHeader("In-Reply-To", inReplyToMessageId);
            msg.setHeader("References", inReplyToMessageId);
            msg.setSubject(subj.toLowerCase().startsWith("re:") ? subj : "Re: " + subj, "UTF-8");
        } else {
            msg.setSubject(subj, "UTF-8");
        }
        msg.setText(fullBody, "UTF-8");

        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(profile.getSmtpHost(), profile.getSmtpPort(),
                    profile.getFromEmail(), cipher.decrypt(profile.getAppPassword()));
            transport.sendMessage(msg, msg.getAllRecipients());
        }
        return new SendResult(messageId);
    }

    private InternetAddress from(OutreachProfileEntity p) throws UnsupportedEncodingException {
        return new InternetAddress(p.getFromEmail(), p.getFromName() == null ? p.getFromEmail() : p.getFromName(), "UTF-8");
    }

    private Session session(OutreachProfileEntity p) {
        Properties props = new Properties();
        props.put("mail.smtp.host", p.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(p.getSmtpPort()));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.ssl.protocols", "TLSv1.2");
        props.put("mail.smtp.connectiontimeout", "20000");
        props.put("mail.smtp.timeout", "20000");
        return Session.getInstance(props);
    }
}
