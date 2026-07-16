package com.naqqa.outreach.service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Literal constants ported verbatim from the original email-lead-gen script. */
public final class OutreachConstants {

    private OutreachConstants() {
    }

    /** Email format for validation ('%' intentionally disallowed). */
    public static final Pattern EMAIL_FORMAT = Pattern.compile("^[a-zA-Z0-9._+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");

    /** Email scraping regex (allows '%', global). */
    public static final Pattern EMAIL_SCRAPE = Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    /** Apollo target decision-maker titles (order = person_titles). */
    public static final List<String> TARGET_TITLES = List.of(
            "founder", "co-founder", "ceo", "chief executive officer",
            "owner", "managing director", "geschäftsführer", "director",
            "head of engineering", "cto", "chief technology officer");

    public static final List<String> STYLE_VARIANTS = List.of(
            "direct", "soft", "operator", "peer-to-peer", "capacity-focused", "engineering-focused");

    public static final List<String> OPENER_VARIANTS = List.of(
            "collaboration", "company-detail-first", "team-extension", "staffing-partner", "engineering-capacity");

    public static final List<String> CTA_VARIANTS = List.of(
            "share-details", "ask-relevance", "simple-reply", "explore-fit", "continue-conversation");

    public static final Set<String> GENERIC_NAME_WORDS = Set.of(
            "inc", "llc", "ltd", "corp", "co", "group", "company", "software",
            "technologies", "technology", "solutions", "services", "systems",
            "digital", "consulting", "partners", "global", "international");

    public static final List<String> RISKY_PHRASES = List.of(
            "guarantee", "guaranteed", "i promise", "we promise", "we will deliver",
            "save money", "reduce costs", "increase revenue", "boost your",
            "best in class", "world-class", "top-tier", "industry-leading", "proven results",
            "click here", "unsubscribe here", "http://", "https://",
            "<html", "<a ", "<img", "dear sir", "dear madam", "to whom it may concern",
            "limited time", "act now", "don't miss", "free trial", "no cost",
            "revolutionary", "cutting-edge", "game-changer", "disruptive",
            "warm wishes", "potential partnership", "synergies", "seamlessly",
            "impressed by", "at your convenience", "brief chat",
            "i am excited", "i am thrilled", "reach out", "touch base",
            "circle back", "move the needle", "value proposition", "pain points",
            "game plan", "deep dive", "leverage", "bandwidth");

    public static final List<String> BOUNCE_PATTERNS = List.of(
            "mailer-daemon", "postmaster", "mail delivery", "delivery status",
            "undelivered mail", "delivery failure", "failed delivery",
            "returned mail", "undeliverable", "delivery notification",
            "mail delivery failed", "delivery has failed");

    public static final List<String> TRANSIENT_PATTERNS = List.of(
            "delay", "delayed", "delivery delay", "still trying", "will retry");

    /** Free-mail / personal domains that are never valid B2B targets. */
    public static final Set<String> PERSONAL_DOMAINS = Set.of(
            "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.uk", "yahoo.fr",
            "yahoo.de", "yahoo.es", "yahoo.it", "ymail.com", "hotmail.com",
            "hotmail.co.uk", "hotmail.fr", "hotmail.de", "hotmail.es", "hotmail.it",
            "outlook.com", "outlook.fr", "outlook.de", "live.com", "live.fr",
            "live.de", "msn.com", "icloud.com", "me.com", "mac.com",
            "aol.com", "protonmail.com", "proton.me", "zoho.com",
            "mail.com", "inbox.com", "gmx.com", "gmx.de", "gmx.net",
            "web.de", "freenet.de", "t-online.de", "orange.fr", "laposte.net",
            "sfr.fr", "free.fr", "wanadoo.fr", "libero.it", "virgilio.it",
            "tiscali.it", "fastwebnet.it", "rambler.ru", "mail.ru", "yandex.ru",
            "yandex.com", "bk.ru", "list.ru", "inbox.ru", "ukr.net",
            "bigpond.com", "bigpond.net.au", "optusnet.com.au", "cox.net",
            "comcast.net", "verizon.net", "att.net", "sbcglobal.net",
            "bellsouth.net", "earthlink.net", "charter.net");

    /** Generic / role mailbox local-parts (info@, sales@, hr@, …) across many languages. */
    public static final Set<String> GENERIC_PREFIXES = Set.of(
            "info", "information", "contact", "contacts", "contactus", "office", "main", "general", "mail", "email",
            "inbox", "mailbox", "hello", "hi", "hey", "team", "company", "corporate", "reception", "receptionist",
            "frontdesk", "frontoffice", "backoffice", "welcome",
            "noreply", "donotreply", "noresponse", "notification", "notifications", "notify", "mailer", "maildaemon",
            "mailerdaemon", "automated", "automatic", "robot", "bot", "system",
            "postmaster", "hostmaster", "webmaster", "root", "admin", "admins", "administrator", "administration",
            "sysadmin", "it", "ict", "tech", "technical", "technology", "support", "help", "helpdesk", "servicedesk",
            "service", "services", "clientservice", "customerservice", "customer", "care", "customercare", "abuse",
            "security", "secure", "privacy", "compliance", "gdpr", "dpo", "dataprotection", "legal", "law", "dns", "dnsadmin",
            "domain", "domains", "hosting", "server", "servers", "network", "networks", "noc", "soc",
            "sales", "sale", "sell", "selling", "commercial", "comercial", "commerce", "business", "businessdevelopment",
            "bizdev", "bd", "bdr", "sdr", "leads", "lead", "leadgen", "growth", "partnership", "partnerships", "partner",
            "partners", "alliances", "affiliate", "affiliates", "reseller", "resellers", "distribution", "distributor",
            "distributors",
            "marketing", "market", "media", "press", "pr", "publicrelations", "communications", "communication", "comms",
            "newsletter", "news", "events", "event", "promo", "promotions", "advertising", "ads", "brand", "branding",
            "social", "socialmedia",
            "hr", "humanresources", "people", "talent", "careers", "career", "jobs", "job", "recruitment", "recruiting",
            "recruiter", "hiring", "vacancies", "vacancy", "cv", "resume", "applications", "application",
            "accounts", "accounting", "account", "finance", "financial", "billing", "bill", "bills", "invoice", "invoices",
            "invoicing", "payment", "payments", "payable", "payables", "ap", "ar", "receivables", "bookkeeping", "payroll",
            "tax", "taxes",
            "procurement", "purchase", "purchasing", "buyer", "buyers", "supplier", "suppliers", "vendor", "vendors",
            "orders", "order", "ordering", "sourcing", "tenders", "tender", "rfp", "rfq",
            "booking", "bookings", "reservation", "reservations", "appointments", "appointment", "schedule", "scheduling",
            "dispatch", "logistics", "shipping", "delivery", "returns", "claims", "complaints", "feedback",
            "management", "manager", "director", "directors", "board", "ceo", "cto", "cfo", "coo", "founder", "owner",
            "contacte", "secretariat", "secretara", "receptie", "birou", "oficiu", "vanzari", "vanzare", "comenzi", "comanda",
            "clienti", "suport", "ajutor", "contabilitate", "contabil", "facturi", "facturare", "plati", "resurseumane",
            "angajari", "cariere", "juridic", "presa",
            "otdel", "zakaz", "zakazi", "zakazy", "prodazha", "prodazhi", "klient", "klienti", "podderzhka", "buh", "buhgalter",
            "buhgalteria", "reklama", "kadry", "rabota", "vakansii", "urist",
            "contacto", "contato", "informacion", "informacao", "ventas", "vendas", "venta", "venda", "clientes", "cliente",
            "atendimento", "suporte", "compras", "compra", "pedidos", "pedido", "facturacion", "faturamento", "pagos",
            "pagamentos", "contabilidad", "contabilidade", "rh", "recursoshumanos", "juridico",
            "contatti", "contatto", "commerciale", "vendite", "vendita", "supporto", "assistenza", "amministrazione",
            "segreteria", "ordini", "ordine", "acquisti", "fatture", "fatturazione", "pagamenti", "contabilita",
            "risorseumane", "lavoro", "legale", "stampa",
            "accueil", "bonjour", "contacteznous", "vente", "ventes", "clients", "client", "assistance", "serviceclient",
            "commandes", "commande", "achats", "achat", "factures", "facturation", "paiement", "paiements", "comptabilite",
            "compta", "recrutement", "emploi", "juridique", "presse",
            "kontakt", "anfrage", "anfragen", "zentrale", "empfang", "buero", "verkauf", "vertrieb", "kundenservice",
            "kundendienst", "hilfe", "bestellung", "bestellungen", "einkauf", "rechnung", "rechnungen", "buchhaltung",
            "finanzen", "zahlung", "zahlungen", "personal", "karriere", "bewerbung", "bewerbungen", "recht",
            "kantoor", "verkoop", "commercieel", "klantenservice", "bestelling", "bestellingen", "inkoop", "factuur",
            "facturen", "facturatie", "boekhouding", "financien", "betaling", "betalingen", "personeel", "werkenbij",
            "vacatures", "juridisch", "pers",
            "biuro", "sprzedaz", "obchod", "podpora", "pomoc", "zamowienia", "objednavky", "faktury", "fakturacia",
            "ksiegowosc", "ucetnictvi", "platby", "kariera", "praca", "pravne", "pravni");
}
