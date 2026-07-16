package com.naqqa.outreach.service;

import com.naqqa.outreach.entity.OutreachProfileEntity;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/** Reads recent INBOX messages over IMAP for bounce + reply detection. Fails soft (returns empty). */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImapReader {

    /** A recent inbound message. {@code inReplyTo} holds any In-Reply-To / References message-ids. */
    public record Inbound(long uid, String fromEmail, String subject, List<String> inReplyTo, String bodyPreview) {
    }

    public List<Inbound> readRecent(OutreachProfileEntity profile, long sinceMillis) {
        List<Inbound> out = new ArrayList<>();
        Properties p = new Properties();
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.ssl.enable", "true");
        p.put("mail.imaps.connectiontimeout", "20000");
        p.put("mail.imaps.timeout", "20000");
        Session session = Session.getInstance(p);
        Store store = null;
        Folder inbox = null;
        try {
            store = session.getStore("imaps");
            store.connect(profile.getImapHost(), profile.getImapPort(),
                    profile.getFromEmail(), profile.getAppPassword());
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            Message[] msgs = inbox.search(new ReceivedDateTerm(ComparisonTerm.GE, new Date(sinceMillis)));
            for (Message m : msgs) {
                try {
                    String from = m.getFrom() != null && m.getFrom().length > 0
                            ? ((InternetAddress) m.getFrom()[0]).getAddress() : "";
                    String subject = m.getSubject() == null ? "" : m.getSubject();
                    List<String> refs = new ArrayList<>();
                    addHeader(refs, m.getHeader("In-Reply-To"));
                    addHeader(refs, m.getHeader("References"));
                    out.add(new Inbound(idOf(inbox, m), from.toLowerCase(), subject, refs, preview(m)));
                } catch (Exception perMsg) {
                    log.debug("skip message: {}", perMsg.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("IMAP read failed for {}: {}", profile.getKey(), e.getMessage());
        } finally {
            try { if (inbox != null && inbox.isOpen()) inbox.close(false); } catch (Exception ignored) { }
            try { if (store != null) store.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    private long idOf(Folder folder, Message m) {
        try {
            if (folder instanceof jakarta.mail.UIDFolder uf) {
                return uf.getUID(m);
            }
        } catch (Exception ignored) {
        }
        return m.getMessageNumber();
    }

    private void addHeader(List<String> refs, String[] values) {
        if (values == null) {
            return;
        }
        for (String v : values) {
            if (v == null) {
                continue;
            }
            for (String id : v.split("\\s+")) {
                if (id.startsWith("<") && id.endsWith(">")) {
                    refs.add(id);
                }
            }
        }
    }

    private String preview(Message m) {
        try {
            Object content = m.getContent();
            String text = extractText(content);
            if (text == null) {
                return "";
            }
            text = text.replaceAll("\\s+", " ").trim();
            return text.length() > 500 ? text.substring(0, 500) : text;
        } catch (Exception e) {
            return "";
        }
    }

    private String extractText(Object content) throws Exception {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof Multipart mp) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                Part part = mp.getBodyPart(i);
                if (part.isMimeType("text/plain") || part.isMimeType("text/html")) {
                    Object c = part.getContent();
                    if (c instanceof String s) {
                        sb.append(s).append(' ');
                    }
                }
            }
            return sb.toString();
        }
        return null;
    }
}
