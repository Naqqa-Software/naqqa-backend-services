package com.naqqa.sso.saml;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.c14n.ExcC14n;
import com.naqqa.sso.saml.xml.XmlDocument;
import com.naqqa.sso.saml.xml.XmlElement;
import com.naqqa.sso.saml.xml.XmlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExcC14nTest {
    private static final ExcC14n EXC = new ExcC14n(false, Set.of());
    private final XmlParser parser = new XmlParser();

    private String c14n(ExcC14n c, XmlElement e) {
        return new String(c.canonicalize(e), StandardCharsets.UTF_8);
    }

    private XmlElement first(XmlElement e) {
        return e.childElements().get(0);
    }

    @Test
    @DisplayName("SSO-T-saml-031 exc-c14n spec example: subtree independent of ancestor context")
    void specExample() {
        XmlDocument d1 = parser.parse("<n0:local xmlns:n0=\"foo:bar\" xmlns:n3=\"ftp://example.org\">"
                + "<n1:elem2 xmlns:n1=\"http://example.net\" xml:lang=\"en\">"
                + "<n3:stuff xmlns:n3=\"ftp://example.org\"/></n1:elem2></n0:local>");
        XmlDocument d2 = parser.parse("<n2:pdu xmlns:n1=\"http://example.net\" xmlns:n2=\"http://foo.example\" "
                + "xml:lang=\"fr\" xml:space=\"retain\">"
                + "<n1:elem2 xmlns:n1=\"http://example.net\" xml:lang=\"en\">"
                + "<n3:stuff xmlns:n3=\"ftp://example.org\"/></n1:elem2></n2:pdu>");
        String expected = "<n1:elem2 xmlns:n1=\"http://example.net\" xml:lang=\"en\">"
                + "<n3:stuff xmlns:n3=\"ftp://example.org\"></n3:stuff></n1:elem2>";
        assertEquals(expected, c14n(EXC, first(d1.root())));
        assertEquals(expected, c14n(EXC, first(d2.root())));
    }

    @Test
    @DisplayName("SSO-T-saml-032 unused namespace declarations dropped")
    void unused() {
        XmlElement e = parser.parse("<e xmlns:u=\"urn:u\" xmlns=\"urn:d\"><u:x/></e>").root();
        assertEquals("<e xmlns=\"urn:d\"><u:x xmlns:u=\"urn:u\"></u:x></e>", c14n(EXC, e));
    }

    @Test
    @DisplayName("SSO-T-saml-033 InclusiveNamespaces PrefixList renders listed prefixes")
    void inclusive() {
        XmlElement e = parser.parse("<p:e xmlns:p=\"urn:p\" xmlns:xs=\"urn:xs\" xmlns:u=\"urn:u\"><p:c xmlns:xs=\"urn:xs\"/></p:e>").root();
        ExcC14n inc = new ExcC14n(false, ExcC14n.parsePrefixList(" xs  zz "));
        assertEquals("<p:e xmlns:p=\"urn:p\" xmlns:xs=\"urn:xs\"><p:c></p:c></p:e>", c14n(inc, e));
    }

    @Test
    @DisplayName("SSO-T-saml-034 #default token renders default namespace")
    void defaultToken() {
        XmlElement e = parser.parse("<a xmlns=\"urn:d\"><p:b xmlns:p=\"urn:p\"/></a>").root();
        XmlElement b = first(e);
        assertEquals("<p:b xmlns:p=\"urn:p\"></p:b>", c14n(EXC, b));
        assertEquals("<p:b xmlns=\"urn:d\" xmlns:p=\"urn:p\"></p:b>", c14n(new ExcC14n(false, Set.of("#default")), b));
    }

    @Test
    @DisplayName("SSO-T-saml-035 attribute ordering by namespace URI then local name")
    void attributeOrder() {
        XmlElement e = parser.parse("<e xmlns:b=\"urn:a\" xmlns:a=\"urn:b\" a:x=\"1\" b:y=\"2\" z=\"3\" c=\"4\"/>").root();
        assertEquals("<e xmlns:a=\"urn:b\" xmlns:b=\"urn:a\" c=\"4\" z=\"3\" b:y=\"2\" a:x=\"1\"></e>", c14n(EXC, e));
    }

    @Test
    @DisplayName("SSO-T-saml-036 xmlns empty emitted only when an output ancestor rendered a default")
    void emptyDefault() {
        XmlElement a = parser.parse("<a xmlns=\"urn:a\"><b xmlns=\"\"><c/></b></a>").root();
        assertEquals("<a xmlns=\"urn:a\"><b xmlns=\"\"><c></c></b></a>", c14n(EXC, a));
        assertEquals("<b><c></c></b>", c14n(EXC, first(a)));
    }

    @Test
    @DisplayName("SSO-T-saml-037 text and attribute escaping")
    void escaping() {
        XmlElement a = parser.parse("<a k=\"&quot;&lt;&amp;&#9;&#10;&#13;'&gt;\">&lt;&amp;&gt;&#13;\"'</a>").root();
        assertEquals("<a k=\"&quot;&lt;&amp;&#x9;&#xA;&#xD;'>\">&lt;&amp;&gt;&#xD;\"'</a>", c14n(EXC, a));
    }

    @Test
    @DisplayName("SSO-T-saml-038 comments removed or kept by algorithm")
    void comments() {
        XmlElement a = parser.parse("<a>x<!--c-->y</a>").root();
        assertEquals("<a>xy</a>", c14n(EXC, a));
        assertEquals("<a>x<!--c-->y</a>", c14n(ExcC14n.forAlgorithm(ExcC14n.ALGORITHM_WITH_COMMENTS, Set.of()), a));
    }

    @Test
    @DisplayName("SSO-T-saml-039 empty elements become start-end pairs, CDATA becomes text")
    void emptyAndCdata() {
        XmlElement a = parser.parse("<a><b/><c><![CDATA[<x>]]></c></a>").root();
        assertEquals("<a><b></b><c>&lt;x&gt;</c></a>", c14n(EXC, a));
    }

    @Test
    @DisplayName("SSO-T-saml-040 enveloped exclusion removes exactly the signature element")
    void enveloped() {
        XmlElement a = parser.parse("<a>1<s><t/></s>2<s/></a>").root();
        XmlElement s = first(a);
        assertEquals("<a>12<s></s></a>", new String(EXC.canonicalize(a, s), StandardCharsets.UTF_8));
        assertThrows(SsoException.class, () -> EXC.canonicalize(a, a));
    }

    @Test
    @DisplayName("SSO-T-saml-041 redundant redeclaration not rendered, changed binding rendered")
    void redeclaration() {
        XmlElement a = parser.parse("<p:a xmlns:p=\"urn:1\"><p:b xmlns:p=\"urn:1\"><p:c xmlns:p=\"urn:2\"/></p:b></p:a>").root();
        assertEquals("<p:a xmlns:p=\"urn:1\"><p:b><p:c xmlns:p=\"urn:2\"></p:c></p:b></p:a>", c14n(EXC, a));
    }

    @Test
    @DisplayName("SSO-T-saml-042 namespace declarations sorted with default first")
    void nsSorting() {
        XmlElement a = parser.parse("<z:a xmlns:z=\"urn:z\" xmlns:b=\"urn:b\" xmlns=\"urn:d\" b:k=\"1\"><c/></z:a>").root();
        assertEquals("<z:a xmlns:b=\"urn:b\" xmlns:z=\"urn:z\" b:k=\"1\"><c xmlns=\"urn:d\"></c></z:a>", c14n(EXC, a));
    }

    @Test
    @DisplayName("SSO-T-saml-043 visibly utilized attribute prefix rendered on the element")
    void attributePrefix() {
        XmlElement a = parser.parse("<a xmlns:x=\"urn:x\"><b x:k=\"v\"/></a>").root();
        assertEquals("<a><b xmlns:x=\"urn:x\" x:k=\"v\"></b></a>", c14n(EXC, a));
    }

    @Test
    @DisplayName("SSO-T-saml-044 unsupported canonicalization algorithms rejected")
    void unsupported() {
        SsoException e = assertThrows(SsoException.class,
                () -> ExcC14n.forAlgorithm("http://www.w3.org/TR/2001/REC-xml-c14n-20010315", Set.of()));
        assertEquals("C14N_UNSUPPORTED_ALGORITHM", e.code());
    }

    @Test
    @DisplayName("SSO-T-saml-045 whitespace and non-ASCII preserved as UTF-8")
    void unicode() {
        XmlElement a = parser.parse("<a>\n  é😀 </a>").root();
        assertEquals("<a>\n  é😀 </a>", c14n(EXC, a));
    }
}
