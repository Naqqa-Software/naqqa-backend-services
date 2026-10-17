package com.naqqa.sso.saml.xml;

import com.naqqa.sso.SsoException;

import java.util.ArrayDeque;
import java.util.Deque;

public final class XmlWriter {
    private final StringBuilder sb = new StringBuilder(1024);
    private final Deque<String> open = new ArrayDeque<>();
    private boolean inStartTag;

    public XmlWriter start(String qname) {
        closeStart();
        sb.append('<').append(qname);
        open.push(qname);
        inStartTag = true;
        return this;
    }

    public XmlWriter attr(String qname, String value) {
        if (!inStartTag) {
            throw new IllegalStateException("attribute outside start tag");
        }
        if (value == null) {
            return this;
        }
        sb.append(' ').append(qname).append("=\"");
        XmlEscape.attribute(sb, value);
        sb.append('"');
        return this;
    }

    public XmlWriter text(String value) {
        closeStart();
        XmlEscape.text(sb, value);
        return this;
    }

    public XmlWriter raw(String xml) {
        closeStart();
        sb.append(xml);
        return this;
    }

    public XmlWriter element(String qname, String text) {
        return start(qname).text(text).end();
    }

    public XmlWriter end() {
        if (open.isEmpty()) {
            throw new SsoException("XML_WRITER_STATE");
        }
        String q = open.pop();
        if (inStartTag) {
            sb.append("/>");
            inStartTag = false;
        } else {
            sb.append("</").append(q).append('>');
        }
        return this;
    }

    private void closeStart() {
        if (inStartTag) {
            sb.append('>');
            inStartTag = false;
        }
    }

    @Override
    public String toString() {
        if (!open.isEmpty()) {
            throw new SsoException("XML_WRITER_STATE");
        }
        return sb.toString();
    }
}
