# Naqqa Chatbot (`com.naqqa:naqqa-chatbot`)

Bibliotecă Spring Boot reutilizabilă pentru un asistent de chat multi-limbă: API pentru widget-ul vizitatorului, inbox pentru operatori (preluare, SSE, export, statistici, analiză răspunsuri), motor de răspuns fără API-uri externe (reguli + căutare în conținutul proiectului), LLM local opțional (Ollama), dictare vocală opțională (whisper.cpp + ffmpeg), protecții (PII, injecții, criză, abuz) și pachete de limbă (`ro`, `ru`, `en`). Proiectul gazdă livrează doar un adaptor mic (SPI) cu conținutul său.

Primul consumator: promotion-server (OMY), pachetul `com.naqqa.omy.chatbot.omy`.

## Cuprins
1. [Instalare](#1-instalare)
2. [Configurare minimă](#2-configurare-minimă)
3. [SPI – ce implementează proiectul](#3-spi--ce-implementează-proiectul)
4. [Pachete de limbă, texte și intenții](#4-pachete-de-limbă-texte-și-intenții)
5. [Securitate și integrare](#5-securitate-și-integrare)
6. [Rutarea răspunsurilor, LLM local, STT](#6-rutarea-răspunsurilor-llm-local-stt)
7. [Siguranță: criză și abuz](#7-siguranță-criză-și-abuz)
8. [Analiza răspunsurilor](#8-analiza-răspunsurilor)
9. [Date (Mongo) și joburi](#9-date-mongo-și-joburi)
10. [Proprietăți](#10-proprietăți)
11. [Teste și publicare](#11-teste-și-publicare)
12. [English summary](#12-english-summary)

## 1. Instalare

Modulul este în `naqqa-backend-services/chatbot-ai` (pom propriu, fără agregator, ca celelalte module). Java 21, Spring Boot 3.4.x.

```bash
cd naqqa-backend-services/chatbot-ai
./mvnw -q test       # teste unitare, fără DB
./mvnw -q install    # instalează în ~/.m2 local
```

În proiect:

```xml
<properties>
    <naqqa-chatbot.version>0.0.1</naqqa-chatbot.version>
</properties>

<dependency>
    <groupId>com.naqqa</groupId>
    <artifactId>naqqa-chatbot</artifactId>
    <version>${naqqa-chatbot.version}</version>
</dependency>
```

Dependențe așteptate în gazdă: `spring-boot-starter-web`, `spring-boot-starter-data-mongodb` (bean `MongoTemplate`), `spring-boot-starter-security`, `spring-security-oauth2-jose`. Redis (`StringRedisTemplate`) este opțional: dacă lipsește, rate-limit-ul și cache-ul de căutare funcționează în memorie.

## 2. Configurare minimă

Auto-configurarea (`NaqqaChatbotAutoConfiguration`, înregistrată în `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`) pornește dacă există un `MongoTemplate` și aplicația e servlet. Se dezactivează cu `naqqa.chatbot.enabled=false`. Biblioteca nu scanează pachetele gazdei și nu folosește repository-uri Spring Data (doar `MongoTemplate`), deci nu interferează cu `@EnableMongoRepositories` din proiect. Joburile rulează pe un scheduler propriu (`naqqaChatbotScheduler`), nu e nevoie de `@EnableScheduling`.

```properties
naqqa.chatbot.languages=ro,ru,en
naqqa.chatbot.brand=Magazinul Meu
naqqa.chatbot.bot-name=Asistent
naqqa.chatbot.contact.email=contact@exemplu.md
naqqa.chatbot.contact.phone=+373 22 000 000
naqqa.chatbot.allowed-domains=exemplu.md,www.exemplu.md
naqqa.chatbot.internal-path-prefixes=/produse,/pages/,/contact,/search
naqqa.chatbot.visitor-token.secret=${CHAT_TOKEN_SECRET}
```

Un proiect poate porni doar cu dependența + un `ChatContentProvider`:

```java
@Component
public class ShopContentProvider implements ChatContentProvider {

    @Override
    public List<ChatItemType> itemTypes() {
        return List.of(new ChatItemType("PRODUCT", 1.0, 0, true), new ChatItemType("BLOG", 0.8, 1, false));
    }

    @Override
    public List<Candidate> retrieve(RetrievalPlan plan) {
        List<Candidate> out = new ArrayList<>();
        for (Product p : products.search(plan.query(), plan.priceMin(), plan.priceMax(), plan.perType())) {
            out.add(new Candidate("PRODUCT", p.getId(), p.getSlug(), Map.of("ro", p.getNameRo(), "ru", p.getNameRu()),
                    p.getImageId(), p.getPrice(), p.getOldPrice(), p.getDiscount(), p.getStoreId(), p.getValidTo(),
                    p.getCreatedAtMillis(), p.getScore(), p.getSponsored(), p.getSponsorWeight(), p.getSponsorFrom(),
                    p.getSponsorTo(), "/produse/" + p.getSlug()));
        }
        return out;
    }
}
```

## 3. SPI – ce implementează proiectul

Toate interfețele sunt în `com.naqqa.chatbot.spi`; fiecare are o implementare implicită (goală/no-op) dacă proiectul nu definește un bean.

| SPI | Rol | Implicit |
|---|---|---|
| `ChatContentProvider` | tipurile de itemi (`ChatItemType`: cheie, prioritate, ordine, cu preț), căutarea pentru un `RetrievalPlan` (intenție, termeni, magazin, categorie, regiune, preț min/max, sortare după reducere), `isAvailable(type)`, `cacheToken(plan)`, `companyCandidate`, `imageUrl` | fără tipuri |
| `ChatEntityResolver` | listele de magazine, categorii (cu taxonomie) și locuri (regiuni/localități) folosite la detectare (cache în proiect) | liste goale |
| `ChatKnowledgeSource` | documente pentru baza de cunoștințe (pagini statice, FAQ) | `ClasspathKnowledgeSource` pe `naqqa.chatbot.knowledge.location` |
| `ChatKnowledgeSearcher` | căutare full-text în fragmente (ex. Elasticsearch) | căutare în memorie (stem + suprapunere) |
| `ChatOperatorResolver` | operatorul curent (id, nume) și accesul din `Authentication` | `DefaultChatOperatorResolver` (id = `authentication.getName()`, autorități = permisiunile configurate) |
| `ChatUserResolver` | utilizatorul logat la crearea conversației, nume și avatar pentru admin | nimic |
| `ChatSponsorProvider` | lista/actualizarea itemilor sponsorizați din admin | endpoint-urile răspund 404 |
| `ChatSearchLinkBuilder` | linkul „Vezi toate rezultatele” și linkurile de categorie | fără linkuri |
| `ChatFileStorage` | încărcarea avatarului botului (+ validare) | 503 |
| `ChatHumanVerifier` | verificare captcha în bibliotecă (`start`/`message`/`voice`) | lipsă (gazda poate folosi propriul filtru) |
| `ChatTokenCodec` | `JwtEncoder`/`JwtDecoder` pentru tokenul vizitatorului | HMAC cu `naqqa.chatbot.visitor-token.secret`; altfel bean-urile `JwtEncoder`+`JwtDecoder` ale gazdei; altfel cheie aleatoare (avertisment) |
| `ChatQueryExpander` | variante ale textului (transliterare, lexicon RO/RU/EN) pentru detectarea entităților | lipsă |
| `ChatSettingsMigration` | migrări ale setărilor stocate la citire | lipsă |
| `ChatMessageSearch` | indexare/căutare în mesajele conversațiilor (vizitator + admin), ștergere GDPR/retenție | `MongoChatMessageSearch` (regex pe text mascat) |

`LlmProvider` (implicit `OllamaLlmProvider`) și `ChatAiEngine` (implicit `DefaultChatAiEngine`) pot fi și ele înlocuite.

## 4. Pachete de limbă, texte și intenții

Resursele sunt pe classpath sub `naqqa-chatbot/`:

```
naqqa-chatbot/intents.json                   definiția intențiilor (independentă de limbă)
naqqa-chatbot/lang/{lang}/pack.json          reguli: cuvinte/fraze pe intenție, stopwords, escaladare, injecții,
                                             preț, locuri, pagini, detectare limbă, stemming, criză, abuz, rutare
naqqa-chatbot/lang/{lang}/templates.json     toate textele botului (cheie → text)
naqqa-chatbot/lang/{lang}/system-prompt.md   promptul pentru LLM
```

- **Suprascriere**: proiectul pune un fișier cu aceeași cale în `src/main/resources`. Obiectele JSON se îmbină recursiv, cheile proiectului câștigă; listele și valorile simple se înlocuiesc. Biblioteca își recunoaște resursele după `naqqa-chatbot/library.properties`.
- **Limbă nouă**: se adaugă `naqqa-chatbot/lang/de/` (pack + templates + prompt) și `de` în `naqqa.chatbot.languages`.
- **Placeholder-e** în texte și fraze: `{brand}`, `{botName}`, `{contactEmail}`, `{contactPhone}` (din proprietăți) și orice `naqqa.chatbot.placeholders.*`. Texte de sistem: `{name}`, `{schedule}`; criză: `{emergency}`, `{helplines}`.
- **Intenții**: `intents.json` declară pentru fiecare intenție `role` (GREETING, SEARCH, CATALOG, COMPANY, CATEGORY, CATEGORY_LIST, LOCATION, PAGE, KNOWLEDGE, PARTNER, CONTACT, OFF_TOPIC, INJECTION), `code` (valoarea stocată în mesaje/statistici), tipurile de itemi (`types.default/withQuery/withPrice/withDiscountSort/withCompany/byTaxonomy`), șabloanele și quick reply-urile. Proiectul poate schimba `code` fără a rescrie regulile (OMY păstrează `salut`, `promotii` …).
- **Detectarea limbii**: script (chirilic ≥ 30% → limba cu `script: cyrillic`), diacritice specifice, apoi cuvinte-marker din fiecare pachet; textul fără marcaje păstrează limba conversației. Răspunsul, cardurile, mesajele de sistem și promptul LLM folosesc limba mesajului; conversația își schimbă limba când vizitatorul schimbă limba.
- **Detectarea intenției** folosește regulile tuturor limbilor configurate simultan.

## 5. Securitate și integrare

Căi (configurabile): `naqqa.chatbot.public-path` (implicit `/api/public/chat`) și `naqqa.chatbot.admin-path` (implicit `/api/admin/chat`). Contractul complet este în `promotion-server/docs/omy-bot/OPENAPI.md`.

În `SecurityFilterChain` al gazdei:

```java
auth.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll();
auth.requestMatchers("/api/public/chat/**").permitAll();
auth.requestMatchers("/api/admin/chat/**").authenticated();
```

- CSRF: exceptați prefixul public (vizitatorul se autentifică prin header `X-Chat-Token`, nu cookie).
- Decoderul JWT al utilizatorilor trebuie să respingă tokenurile cu `typ=chat_visitor`.
- Permisiunile admin sunt verificate în controller (nu depind de `@EnableMethodSecurity`): `chat:read_all`, `chat:read_assigned`, `chat:takeover`, `chat:export`, `chat:delete`, `chat:settings`, `chat:stats` — redenumibile prin `naqqa.chatbot.permissions.*`. Lipsa permisiunii aruncă `AccessDeniedException` (tratată de gazdă).
- CORS: `naqqa.chatbot.allowed-origins` poate fi citit de configurația CORS a gazdei pentru prefixul public.
- reCAPTCHA / rate limit pe IP rămân în filtrele gazdei; numele acțiunilor sunt expuse în `/config` (`naqqa.chatbot.recaptcha-actions.*`). Limita per conversație (20/min) e în bibliotecă.
- UI: widget-ul `@naqqa/bot-widget` și pagina admin din `naqqa-ui` folosesc exact acest API.

## 6. Rutarea răspunsurilor, LLM local, STT

Fiecare mesaj primește o rută (stocată pe mesaj, vizibilă în admin și în statistici):

| Rută | Când | LLM |
|---|---|---|
| `GUARD` | criză, abuz, injecție | niciodată |
| `TEMPLATE` | salut, mulțumesc, contact, cum funcționează, cont, aplicație, parteneri, operator, quick reply-uri | nu |
| `SEARCH` | intenție clară + rezultate (text din șablon + carduri) | nu |
| `KNOWLEDGE` | un fragment dominant din baza de cunoștințe (extras + link) | nu |
| `LLM` | încredere sub prag (`llmConfidenceThreshold` din setări, implicit 0.5), întrebări comparative/multi-parte, continuări fără context recuperabil, sinteză din mai multe fragmente, 0 rezultate (clarificare), ciorna operatorului | da |

Continuările („și la Kaufland?”, „а в Максимум?”) se rezolvă întâi prin reluarea contextului ultimei întrebări. Motivele permise: `naqqa.chatbot.llm.routes` (`LOW_CONFIDENCE, COMPARATIVE, FOLLOW_UP, KNOWLEDGE_SYNTHESIS, NO_RESULTS`). Fără LLM (dezactivat, oprit, ocupat, timeout) aceeași rută revine la cel mai bun răspuns fără LLM, iar mesajul primește semnalul `LLM_FALLBACK`.

Cardurile: sponsorizatele relevante (max N) primele, apoi celelalte după reducere descrescător (fără reducere la final, egalitate după relevanță); „Cea mai mare reducere” = primul card nesponsorizat.

**Ollama** (doar local): `naqqa.chatbot.llm.provider=ollama`, `url`, `model` (implicit `qwen2.5:3b-instruct`), `timeout-ms`, `max-concurrent`, `queue-wait-ms`. Instalare: vezi `promotion-server/docs/omy-bot/LOCAL_AI.md`.

**STT** (doar dictare, nimic stocat): `naqqa.chatbot.stt.provider=whisper`, `whisper-url`, `ffmpeg-path`, `timeout-ms`. ffmpeg rulează cu `-protocol_whitelist file` și demuxer fixat; audio ≤ 2 MB, ≤ 60 s, tip verificat prin magic bytes.

## 7. Siguranță: criză și abuz

Rulează **înaintea** oricărui alt pas (injecție, intenție, căutare, LLM), pe textul cu PII mascat; listele vin din toate pachetele de limbă de pe classpath (nu doar din limbile configurate).

- **Criză** (`crisis.selfHarm`, `crisis.danger` în pack.json): răspuns calm, fără carduri, în limba vizitatorului, cu `naqqa.chatbot.crisis.emergency` (implicit `112`) și `naqqa.chatbot.crisis.helplines.{lang}`; quick reply unic `talk_to_operator`; mesajul este marcat, conversația escaladată cu `escalationReason=CRISIS` (sau `DANGER`). Răspunsul se trimite și dacă un operator a preluat conversația.
- **Abuz** (`abuse.sexual/profanity/hate/minors/allow/patterns`): normalizare (litere mici, fără diacritice, leetspeak, omoglife latin/chirilic, litere separate, `*`), liste de cuvinte exacte/rădăcini și listă de excepții. Profanitate/sexual → limită politicoasă; ură → limită fermă + escaladare `ABUSE`; conținut sexual cu minori → refuz, escaladare `ABUSE`, severitate HIGH, audit `SAFETY_FLAG`. A 3-a abatere în 10 minute → conversația e pusă pe pauză 15 minute (`429 CHAT_MUTED` cu `retryAfter`). Parametri: `naqqa.chatbot.safety.*`.
- **Linkuri externe și subiecte restricționate** (`topics.externalLink`, `topics.restricted` în pack.json): cererile de linkuri externe primesc șablonul `external_link` (sau linkul intern al magazinului, dacă există), iar jocurile de noroc, drogurile, armele, hackingul și sfaturile medicale/juridice/financiare primesc `restricted` — fără căutare și fără LLM. Cuvintele de cerere (`topics.meta`: „link”, „site”, „dă-mi”…) nu intră în termenii de căutare; în căutarea liberă cardurile fără niciun termen relevant în titlu sunt eliminate.
- Rezultatele căutării cu titluri pentru adulți sunt filtrate (`naqqa.chatbot.safety.filter-adult-results`), iar textele „fără rezultate” nu repetă textul vizitatorului.

## 8. Analiza răspunsurilor

- Feedback: `POST {public}/conversations/{id}/messages/{messageId}/feedback` `{value: 1|0|-1, reason?}` (tokenul conversației; `0` șterge votul).
- Semnale automate pe fiecare răspuns (`qualityFlags`, `needsReview`): `NO_RESULTS`, `LOW_CONFIDENCE`, `REPHRASED` (vizitatorul reformulează în 60 s), `OPERATOR_REQUESTED` (cere operator imediat după), `THUMBS_DOWN`, `LLM_FALLBACK`, `OUTPUT_GUARD_MODIFIED`.
- Admin: `GET {admin}/review`, `POST {admin}/review/{messageId}/resolve`, `GET {admin}/review/suggestions` (agregare nocturnă: interogări fără rezultat, sinonime de intenții, goluri în baza de cunoștințe). Statistici: distribuția rutelor, cota LLM, 👍/👎, top interogări fără răspuns, top intenții cu 👎.

## 8b. Căutare în conversații

`GET {public}/conversations/{id}/search?q=&limit=` (tokenul conversației, 30/min) și `GET {admin}/search?q=&from=&to=&lang=&status=&page=&size=` (vizibilitate după permisiuni). Răspunsul conține `snippet` (text cu PII mascat) și `ranges` de evidențiere. Implementarea implicită folosește Mongo; un proiect poate oferi `ChatMessageSearch` (ex. Elasticsearch) — biblioteca îl apelează la fiecare salvare de mesaj, la ștergerea conversației și la retenție.

## 9. Date (Mongo) și joburi

Colecții (nume configurabile prin `naqqa.chatbot.collections.*`): `chat_conversation`, `chat_message`, `chat_recommendation_event`, `chat_settings`, `chat_audit_log`, `chat_knowledge_chunk`, `chat_secret`, `chat_review_suggestion`. Câmpurile au nume snake_case explicite (`@Field`), deci formatul nu depinde de strategia de denumire a gazdei. Indexurile sunt create la pornire (`naqqa.chatbot.collections.create-indexes`). Setările multi-limbă (`welcome`, etichete quick reply, răspunsuri predefinite) sunt `Map<limbă, text>` (JSON compatibil cu `{ro, ru}`).

Joburi (scheduler propriu): ping SSE la 25 s, închiderea conversațiilor inactive (30 min), retenție (`naqqa.chatbot.jobs.retention-cron`), reindexarea bazei de cunoștințe (`naqqa.chatbot.knowledge.reindex-cron` + zona), sugestiile de review.

## 10. Proprietăți

Toate sub `naqqa.chatbot.*` (vezi `NaqqaChatbotProperties`): `enabled`, `languages`, `public-path`, `admin-path`, `brand`, `bot-name`, `timezone`, `privacy-path`, `allowed-origins`, `allowed-domains`, `internal-path-prefixes`, `retention-days`, `hash-salt`, `contact.*`, `visitor-token.{ttl-hours,issuer,secret}`, `collections.*`, `permissions.*`, `stt.*`, `tts.provider`, `llm.{provider,url,model,timeout-ms,max-concurrent,queue-wait-ms,routes}`, `knowledge.{location,reindex-on-startup,reindex-cron,reindex-zone,classpath-enabled,replacements}`, `jobs.*`, `cache.{prefix,ttl-minutes}`, `rate-limit.*`, `recaptcha-actions.*`, `crisis.{emergency,helplines.*}`, `safety.*`, `placeholders.*`.

## 11. Teste și publicare

`./mvnw -q test` rulează doar teste unitare (fără DB, fără rețea; un singur test de context cu `MongoTemplate` simulat), inclusiv evaluarea deterministă (`src/test/resources/eval/questions.json`, raport în `target/chat-ai-eval.txt`). Clasa `com.naqqa.chatbot.eval.ChatEvalRunner` poate fi folosită și de proiecte pentru propriul set de întrebări.

Publicarea (GitHub Packages, `distributionManagement` id `github`) nu se face automat: `./mvnw deploy` cu un token cu `write:packages` în `~/.m2/settings.xml` (server id `github`).

## 12. English summary

`naqqa-chatbot` is a reusable Spring Boot auto-configured chat assistant: visitor API (`/api/public/chat` by default), operator inbox API (`/api/admin/chat`), SSE, takeover state machine with optimistic locking, escalation, schedule, export, stats, retention, answer-quality review, local LLM (Ollama, optional) and local STT (whisper.cpp, optional), PII masking, prompt-injection guard, crisis/abuse safety and language packs (`ro`, `ru`, `en`).

- Add the dependency, provide a `MongoTemplate`, set `naqqa.chatbot.*` properties and implement at least `ChatContentProvider`. Everything else (`ChatEntityResolver`, `ChatKnowledgeSource`, `ChatKnowledgeSearcher`, `ChatOperatorResolver`, `ChatUserResolver`, `ChatSponsorProvider`, `ChatSearchLinkBuilder`, `ChatFileStorage`, `ChatHumanVerifier`, `ChatTokenCodec`, `ChatQueryExpander`, `ChatSettingsMigration`) is optional.
- Texts, keywords, safety lists and prompts live in `naqqa-chatbot/lang/{lang}/`; override any file by putting the same path in your resources (JSON objects are deep-merged, your keys win) and add a language by adding a folder and listing it in `naqqa.chatbot.languages`.
- Secure the public path as `permitAll` (CSRF-exempt), the admin path as `authenticated`; permissions (`chat:*`, configurable) are checked inside the controllers. Make your user JWT decoder reject `typ=chat_visitor` tokens.
- Replies are routed (`GUARD`, `TEMPLATE`, `SEARCH`, `KNOWLEDGE`, `LLM`); the LLM is used only for low-confidence, comparative, follow-up, multi-chunk or no-result cases and always degrades to a non-LLM answer.
- `./mvnw -q test` is DB-free; `./mvnw -q install` installs locally. Publishing to GitHub Packages is a manual `./mvnw deploy`.
