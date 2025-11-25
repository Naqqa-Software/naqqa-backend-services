package com.naqqa.auth.service;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Attachments;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthEmailService {
    @Value("${spring.sendgrid.api-key}")
    private String sendGridApiKey;

    @Value("${spring.sendgrid.from-email}")
    private String sendGridFromEmail;

    private final JdbcTemplate jdbcTemplate;

    public void sendEmail(String email, String subject, String message) {
        try {
            sendEmail(email, subject, message, new HashMap<>());
        } catch (IOException e) {
            log.info("Failed to send email address confirmation!");
        }
    }

    public String sendEmail(String email, String subjectEmail, String template, Map<String, String> configs) throws IOException {

        Email from = new Email(sendGridFromEmail);
        Email to = new Email(email);
        String finalTemplate = replaceVariables(template, configs);

        Content content = new Content("text/html", finalTemplate);
        Mail mail = new Mail(from, subjectEmail, to, content);

        SendGrid sg = new SendGrid(sendGridApiKey);
        Request request = new Request();

        try {
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            Response response = sg.api(request);

            log.info("SendGrid response status: {}", response.getStatusCode());
            log.info("SendGrid response body: {}", response.getBody());
            log.info("SendGrid response headers: {}", response.getHeaders());

            return response.getBody();
        } catch (IOException ex) {
            log.error("SendGrid API error", ex);
            throw ex;
        }
    }


    public String sendEmailWithAttachment(String email, String subjectEmail, String template, Map<String, String> configs, byte[] attachment, String attachmentName) throws IOException {
        Email from = new Email(sendGridFromEmail);
        Email to = new Email(email);
        String finalTemplate = replaceVariables(template, configs);

        Content content = new Content("text/html", finalTemplate);
        Mail mail = new Mail(from, subjectEmail, to, content);

        // Convert PDF to Base64 using java.util.Base64
        Attachments attachments = new Attachments();
        attachments.setContent(Base64.getEncoder().encodeToString(attachment));
        attachments.setType("application/pdf");
        attachments.setFilename(attachmentName);
        attachments.setDisposition("attachment");

        mail.addAttachments(attachments);

        SendGrid sg = new SendGrid(sendGridApiKey);
        Request request = new Request();
        try {
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            Response response = sg.api(request);
            return response.getBody();
        } catch (IOException ex) {
            throw ex;
        }
    }

    private String replaceVariables(String template, Map<String, String> configs) {
        if (configs != null && !configs.isEmpty()) {
            for (Map.Entry<String, String> entry : configs.entrySet()) {
                String variable = "${" + entry.getKey() + "}";
                String value = entry.getValue();
                template = template.replace(variable, value);
            }
        }

        return template;
    }
}