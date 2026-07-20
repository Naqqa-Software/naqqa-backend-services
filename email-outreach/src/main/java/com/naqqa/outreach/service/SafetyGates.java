package com.naqqa.outreach.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre- and post-generation safety gates — faithful port of validateLeadQuality(),
 * computeCompanyNameConfidence(), getDistinctiveWords() and validateGeneratedEmail().
 */
@Service
public class SafetyGates {

    public record Gate(boolean valid, String reason) {
    }

    /**
     * Pre-AI: needs a company name and an email. Company info is OPTIONAL — leads whose site can't be
     * scraped (JS-heavy sites jsoup can't render) still get a GENERIC email (the AI is told not to
     * invent details when info is empty). Only the name + email are hard requirements.
     */
    public Gate validateLeadQuality(String companyName, String email, List<String> companyInfo) {
        if (companyName == null || companyName.trim().length() < 2) {
            return new Gate(false, "no_company_name");
        }
        if (email == null || email.isBlank()) {
            return new Gate(false, "no_email");
        }
        return new Gate(true, "ok");
    }

    /** Distinctive (non-generic, ≥4-char) words of a company name. */
    public List<String> getDistinctiveWords(String companyName) {
        List<String> out = new ArrayList<>();
        if (companyName == null) {
            return out;
        }
        String cleaned = companyName.toLowerCase().replaceAll("[.,/#!$%^&*;:{}=\\-_`~()]", " ");
        for (String w : cleaned.split("\\s+")) {
            if (w.length() >= 4 && !OutreachConstants.GENERIC_NAME_WORDS.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    /** high / medium / low, from how many distinctive company words appear in the scraped snippets. */
    public String computeCompanyNameConfidence(String companyName, List<String> snippets) {
        if (companyName == null || snippets == null || snippets.isEmpty()) {
            return "low";
        }
        List<String> words = getDistinctiveWords(companyName);
        if (words.isEmpty()) {
            return "low";
        }
        String allText = String.join(" ", snippets).toLowerCase();
        long matches = words.stream().filter(allText::contains).count();
        if (matches >= 2) {
            return "high";
        }
        if (matches == 1) {
            return "medium";
        }
        return "low";
    }

    /** Post-AI: subject/body length, no our/recipient company name in subject, ≤1 name in body,
     *  no risky phrase, must start with "hello". Thresholds match the code (subject ≤60, body ≤1200). */
    public Gate validateGeneratedEmail(String subject, String emailBody, String companyName) {
        if (subject == null || subject.trim().length() < 3) {
            return new Gate(false, "subject_too_short");
        }
        if (subject.length() > 60) {
            return new Gate(false, "subject_too_long");
        }
        if (emailBody == null || emailBody.trim().length() < 50) {
            return new Gate(false, "body_too_short");
        }
        if (emailBody.length() > 1200) {
            return new Gate(false, "body_too_long");
        }
        String subjectLower = subject.toLowerCase();
        if (subjectLower.contains("naqqa")) {
            return new Gate(false, "our_company_name_in_subject");
        }
        if (companyName != null) {
            for (String w : getDistinctiveWords(companyName)) {
                if (subjectLower.contains(w)) {
                    return new Gate(false, "recipient_company_name_in_subject: " + w);
                }
            }
            String bodyLower = emailBody.toLowerCase();
            for (String w : getDistinctiveWords(companyName)) {
                int count = bodyLower.split(java.util.regex.Pattern.quote(w), -1).length - 1;
                if (count > 1) {
                    return new Gate(false, "company_name_repeated_in_body: " + w + " (" + count + "x)");
                }
            }
        }
        String lower = (subject + " " + emailBody).toLowerCase();
        for (String phrase : OutreachConstants.RISKY_PHRASES) {
            if (lower.contains(phrase)) {
                return new Gate(false, "risky_phrase: " + phrase);
            }
        }
        if (!emailBody.trim().toLowerCase().startsWith("hello")) {
            return new Gate(false, "missing_greeting");
        }
        return new Gate(true, "ok");
    }
}
