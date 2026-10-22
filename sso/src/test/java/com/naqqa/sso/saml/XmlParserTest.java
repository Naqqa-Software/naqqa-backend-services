package com.naqqa.sso.saml;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.xml.XmlComment;
import com.naqqa.sso.saml.xml.XmlDocument;
import com.naqqa.sso.saml.xml.XmlElement;
import com.naqqa.sso.saml.xml.XmlLimits;
import com.naqqa.sso.saml.xml.XmlParser;
import com.naqqa.sso.saml.xml.XmlSerializer;
import com.naqqa.sso.saml.xml.XmlText;
import com.naqqa.sso.saml.xml.XmlWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlParserTest {
    private final XmlParser parser = new XmlParser();

    private XmlDocument parse(String s) {
        return parser.parse(s);
    }

    private void rejects(String code, String xml) {
        SsoException e = assertThrows(SsoException.class, () -> parse(xml));
        assertEquals(code, e.code());
    }

    @Test
    @DisplayName("SSO-T-saml-001 parses elements, attributes, text and namespaces")
    void basic() {
        XmlDocument d = parse("<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:a xmlns:p=\"urn:p\" x=\"1\"><p:b>hi</p:b><c/></p:a>");
        XmlElement a = d.root();
        assertEquals("urn:p", a.namespaceUri());
        assertEquals("a", a.localName());
        assertEquals("1", a.attribute("x"));
        assertEquals("hi", a.requiredChild("urn:p", "b").text());
        assertEquals("", a.childElements().get(1).namespaceUri());
        assertEquals(3, d.elementCount());
    }

    @Test
    @DisplayName("SSO-T-saml-002 prefix scoping and redeclaration")
    void scoping() {
        XmlElement a = parse("<p:a xmlns:p=\"urn:1\"><p:b xmlns:p=\"urn:2\"><p:c/></p:b><p:d/></p:a>").root();
        XmlElement b = a.childElements().get(0);
        assertEquals("urn:2", b.namespaceUri());
        assertEquals("urn:2", b.childElements().get(0).namespaceUri());
        assertEquals("urn:1", a.childElements().get(1).namespaceUri());
    }

    @Test
    @DisplayName("SSO-T-saml-003 default namespace and undeclaration")
    void defaultNs() {
        XmlElement a = parse("<a xmlns=\"urn:d\" k=\"v\"><b xmlns=\"\"><c/></b></a>").root();
        assertEquals("urn:d", a.namespaceUri());
        assertEquals("", a.attributes().get(0).namespaceUri());
        XmlElement b = a.childElements().get(0);
        assertEquals("", b.namespaceUri());
        assertEquals("", b.childElements().get(0).namespaceUri());
        assertTrue(!b.inScopeNamespaces().containsKey(""));
    }

    @Test
    @DisplayName("SSO-T-saml-004 attributes unique by expanded name")
    void expandedDuplicate() {
        rejects("XML_DUPLICATE_ATTRIBUTE", "<a xmlns:x=\"urn:u\" xmlns:y=\"urn:u\" x:k=\"1\" y:k=\"2\"/>");
        rejects("XML_DUPLICATE_ATTRIBUTE", "<a k=\"1\" k=\"2\"/>");
    }

    @Test
    @DisplayName("SSO-T-saml-005 unbound prefix rejected")
    void unbound() {
        rejects("XML_UNBOUND_PREFIX", "<p:a/>");
        rejects("XML_UNBOUND_PREFIX", "<a p:k=\"1\"/>");
    }

    @Test
    @DisplayName("SSO-T-saml-006 XXE external entity rejected at DOCTYPE")
    void xxe() {
        rejects("XML_DOCTYPE", "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>");
    }

    @Test
    @DisplayName("SSO-T-saml-007 billion laughs rejected")
    void billionLaughs() {
        StringBuilder sb = new StringBuilder("<!DOCTYPE lolz [<!ENTITY lol \"lol\">");
        for (int i = 1; i < 10; i++) {
            sb.append("<!ENTITY lol").append(i).append(" \"");
            for (int j = 0; j < 10; j++) {
                sb.append("&lol").append(i == 1 ? "" : String.valueOf(i - 1)).append(';');
            }
            sb.append("\">");
        }
        sb.append("]><lolz>&lol9;</lolz>");
        rejects("XML_DOCTYPE", sb.toString());
    }

    @Test
    @DisplayName("SSO-T-saml-008 DOCTYPE anywhere rejected")
    void doctypeAnywhere() {
        rejects("XML_DOCTYPE", "<!DOCTYPE a><a/>");
        rejects("XML_DOCTYPE", "<!-- c --><!DOCTYPE a><a/>");
        rejects("XML_DOCTYPE", "<a><!DOCTYPE a></a>");
        rejects("XML_DOCTYPE", "<a/><!DOCTYPE a>");
        rejects("XML_DTD", "<a><!ENTITY x \"y\"></a>");
        rejects("XML_DTD", "<!ELEMENT a ANY><a/>");
    }

    @Test
    @DisplayName("SSO-T-saml-009 undeclared entity references rejected")
    void unknownEntity() {
        rejects("XML_ENTITY", "<a>&foo;</a>");
        rejects("XML_ENTITY", "<a k=\"&ext;\"/>");
        rejects("XML_ENTITY", "<a>&amp</a>");
    }

    @Test
    @DisplayName("SSO-T-saml-010 predefined and numeric references")
    void references() {
        XmlElement a = parse("<a k=\"&quot;&#x41;\">&lt;&gt;&amp;&apos;&#66;&#x1F600;</a>").root();
        assertEquals("\"A", a.attribute("k"));
        assertEquals("<>&'B😀", a.text());
    }

    @Test
    @DisplayName("SSO-T-saml-011 invalid numeric char refs rejected")
    void badCharRefs() {
        rejects("XML_ENTITY", "<a>&#0;</a>");
        rejects("XML_ENTITY", "<a>&#xD800;</a>");
        rejects("XML_ENTITY", "<a>&#xFFFE;</a>");
        rejects("XML_ENTITY", "<a>&#x110000;</a>");
        rejects("XML_ENTITY", "<a>&#X41;</a>");
        rejects("XML_ENTITY", "<a>&#1;</a>");
    }

    @Test
    @DisplayName("SSO-T-saml-012 processing instructions rejected")
    void pi() {
        rejects("XML_PI", "<?xml-stylesheet href=\"x\"?><a/>");
        rejects("XML_PI", "<a><?php x ?></a>");
        rejects("XML_PI", " <?xml version=\"1.0\"?><a/>");
        rejects("XML_PI", "<a/><?x?>");
    }

    @Test
    @DisplayName("SSO-T-saml-013 non UTF-8 encodings rejected")
    void encodings() {
        rejects("XML_ENCODING", "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a/>");
        SsoException e = assertThrows(SsoException.class,
                () -> parser.parse(new byte[]{'<', 'a', '>', (byte) 0xC3, (byte) 0x28, '<', '/', 'a', '>'}));
        assertEquals("XML_ENCODING", e.code());
        e = assertThrows(SsoException.class, () -> parser.parse(new byte[]{(byte) 0xFE, (byte) 0xFF, 0, '<', 0, 'a', 0, '/', 0, '>'}));
        assertEquals("XML_ENCODING", e.code());
        e = assertThrows(SsoException.class, () -> parser.parse(new byte[]{'<', 'a', '>', (byte) 0xC0, (byte) 0x80, '<', '/', 'a', '>'}));
        assertEquals("XML_ENCODING", e.code());
        assertEquals("a", parser.parse(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '<', 'a', '/', '>'}).root().localName());
    }

    @Test
    @DisplayName("SSO-T-saml-014 invalid characters rejected")
    void invalidChars() {
        rejects("XML_INVALID_CHAR", "<a>\u0001</a>");
        rejects("XML_INVALID_CHAR", "<a>￿</a>");
    }

    @Test
    @DisplayName("SSO-T-saml-015 CDATA allowed and merged into text")
    void cdata() {
        XmlElement a = parse("<a>x<![CDATA[<b>&amp;]]>y</a>").root();
        assertEquals(1, a.children().size());
        assertEquals("x<b>&amp;y", a.text());
        rejects("XML_SYNTAX", "<a>x]]>y</a>");
        rejects("XML_SYNTAX", "<a><![CDATA[x</a>");
    }

    @Test
    @DisplayName("SSO-T-saml-016 depth limit 32")
    void depth() {
        assertEquals(32, nested(32).elementCount());
        SsoException e = assertThrows(SsoException.class, () -> nested(33));
        assertEquals("XML_TOO_DEEP", e.code());
        e = assertThrows(SsoException.class, () -> nested(100000));
        assertTrue(e.code().equals("XML_TOO_DEEP") || e.code().equals("XML_TOO_LARGE"));
    }

    private XmlDocument nested(int n) {
        return parse("<a>".repeat(n) + "</a>".repeat(n));
    }

    @Test
    @DisplayName("SSO-T-saml-017 attribute count limit")
    void attributes() {
        StringBuilder sb = new StringBuilder("<a");
        for (int i = 0; i < 65; i++) {
            sb.append(" a").append(i).append("=\"x\"");
        }
        rejects("XML_TOO_MANY_ATTRIBUTES", sb.append("/>").toString());
    }

    @Test
    @DisplayName("SSO-T-saml-018 node count limit")
    void nodes() {
        rejects("XML_TOO_MANY_NODES", "<a>" + "<b/>".repeat(10001) + "</a>");
    }

    @Test
    @DisplayName("SSO-T-saml-019 input size limit")
    void size() {
        rejects("XML_TOO_LARGE", "<a>" + "x".repeat(256 * 1024) + "</a>");
        XmlParser small = new XmlParser(XmlLimits.DEFAULT.withMaxBytes(10));
        SsoException e = assertThrows(SsoException.class, () -> small.parse("<a>12345678</a>"));
        assertEquals("XML_TOO_LARGE", e.code());
        rejects("XML_EMPTY", "");
    }

    @Test
    @DisplayName("SSO-T-saml-020 name length limit")
    void nameLength() {
        String n = "a".repeat(257);
        rejects("XML_NAME_TOO_LONG", "<" + n + "/>");
    }

    @Test
    @DisplayName("SSO-T-saml-021 structural errors")
    void structure() {
        rejects("XML_MISMATCHED_TAG", "<a></b>");
        rejects("XML_TRAILING_CONTENT", "<a/><b/>");
        rejects("XML_SYNTAX", "<a>");
        rejects("XML_SYNTAX", "<a k=1/>");
        rejects("XML_SYNTAX", "<a k=\"<\"/>");
        rejects("XML_SYNTAX", "<a k=\"1\"j=\"2\"/>");
        rejects("XML_SYNTAX", "text");
    }

    @Test
    @DisplayName("SSO-T-saml-022 comments: double hyphen rejected, comments kept as nodes")
    void comments() {
        rejects("XML_COMMENT", "<a><!-- a -- b --></a>");
        rejects("XML_COMMENT", "<a><!-- a ---></a>");
        XmlElement a = parse("<a>x<!--c-->y</a>").root();
        assertEquals(3, a.children().size());
        assertTrue(a.children().get(1) instanceof XmlComment);
        assertEquals("xy", a.text());
    }

    @Test
    @DisplayName("SSO-T-saml-023 line ending and attribute value normalization")
    void normalization() {
        XmlElement a = parse("<a k=\"x\ty\r\nz\" j=\"&#9;&#10;\">1\r\n2\r3</a>").root();
        assertEquals("x y z", a.attribute("k"));
        assertEquals("\t\n", a.attribute("j"));
        assertEquals("1\n2\n3", a.text());
    }

    @Test
    @DisplayName("SSO-T-saml-024 reserved namespace bindings")
    void reservedNs() {
        rejects("XML_NAMESPACE", "<a xmlns:p=\"\"/>");
        rejects("XML_NAMESPACE", "<a xmlns:xml=\"urn:x\"/>");
        rejects("XML_NAMESPACE", "<a xmlns:xmlns=\"urn:x\"/>");
        rejects("XML_NAMESPACE", "<a xmlns:p=\"http://www.w3.org/XML/1998/namespace\"/>");
        rejects("XML_NAMESPACE", "<xmlns:a/>");
        rejects("XML_NAMESPACE", "<a:b:c xmlns:a=\"urn:a\"/>");
        XmlElement a = parse("<a xml:lang=\"en\"/>").root();
        assertEquals("en", a.attribute(XmlParser.XML_NS, "lang"));
    }

    @Test
    @DisplayName("SSO-T-saml-025 element ids in document order and parent lookup")
    void ids() {
        XmlDocument d = parse("<a><b><c/></b><d/></a>");
        assertEquals("c", d.element(2).localName());
        assertEquals("d", d.element(3).localName());
        assertSame(d.element(1), d.parent(d.element(2)));
        assertNull(d.parent(d.root()));
        XmlDocument other = parse("<a><b><c/></b><d/></a>");
        assertTrue(!d.contains(other.element(2)));
    }

    @Test
    @DisplayName("SSO-T-saml-026 tree is immutable")
    void immutable() {
        XmlElement a = parse("<a><b/></a>").root();
        assertThrows(UnsupportedOperationException.class, () -> a.children().clear());
        assertThrows(UnsupportedOperationException.class, () -> a.attributes().clear());
        assertThrows(UnsupportedOperationException.class, () -> a.inScopeNamespaces().clear());
    }

    @Test
    @DisplayName("SSO-T-saml-027 serializer round trip preserves content")
    void serializer() {
        String in = "<p:a xmlns:p=\"urn:p\" k=\"a&quot;b&#9;\"><p:b>x &amp; &lt;y&gt;<!--c--></p:b><e/></p:a>";
        XmlDocument d = parse(in);
        String out = XmlSerializer.serialize(d.root());
        XmlDocument again = parse(out);
        assertEquals(out, XmlSerializer.serialize(again.root()));
        assertEquals("a\"b\t", again.root().attribute("k"));
        String inner = XmlSerializer.serialize(d.root().childElements().get(0));
        assertTrue(inner.startsWith("<p:b xmlns:p=\"urn:p\">"));
    }

    @Test
    @DisplayName("SSO-T-saml-028 writer escapes and rejects invalid chars")
    void writer() {
        String x = new XmlWriter().start("a").attr("k", "\"<&\n").text("<&>").end().toString();
        XmlElement a = parse(x).root();
        assertEquals("\"<&\n", a.attribute("k"));
        assertEquals("<&>", a.text());
        assertThrows(SsoException.class, () -> new XmlWriter().start("a").text("\u0000").end().toString());
    }

    @Test
    @DisplayName("SSO-T-saml-029 duplicate ids detected")
    void duplicateIds() {
        XmlDocument d = parse("<a ID=\"x\"><b Id=\"x\"/></a>");
        SsoException e = assertThrows(SsoException.class, d::requireUniqueIds);
        assertEquals("XML_DUPLICATE_ID", e.code());
        assertEquals(2, d.elementsWithId("x").size());
    }

    @Test
    @DisplayName("SSO-T-saml-030 whitespace-only text nodes preserved and XML declaration validated")
    void whitespaceAndDecl() {
        XmlElement a = parse("<a> <b/> </a>").root();
        assertEquals(3, a.children().size());
        assertTrue(((XmlText) a.children().get(0)).isWhitespace());
        rejects("XML_DECLARATION", "<?xml version=\"1.1\"?><a/>");
        rejects("XML_DECLARATION", "<?xml encoding=\"UTF-8\" version=\"1.0\"?><a/>");
        assertEquals("a", parser.parse("<?xml version='1.0' standalone='yes'?>\n<a/>\n".getBytes(StandardCharsets.UTF_8)).root().localName());
    }
}
