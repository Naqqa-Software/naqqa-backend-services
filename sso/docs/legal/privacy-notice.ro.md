# Notă de confidențialitate - autentificare și securitatea contului

Stare: șablon pentru clienți. Owner/counsel: verify. Înlocuiți [spațiile rezervate].

Această notă explică cum folosește [DENUMIRE COMPANIE] ("noi") datele dumneavoastră la autentificare. Serviciul de identitate este operat de naqqa, în calitate de persoană împuternicită a noastră.

## Ce păstrăm

- Date de cont: adresa de email, numele și, dacă le oferiți, numărul de telefon și data nașterii.
- Date de autentificare: o formă protejată a parolei (nu păstrăm niciodată parola în sine) și datele pentru securitatea suplimentară pe care o activați.
- Date de sesiune: când și de pe ce dispozitiv și adresă IP v-ați autentificat, un identificator de dispozitiv și o amprentă a textului user-agent al browserului, ca să vă puteți vedea și închide sesiunile.
- Jurnal de securitate: o evidență a evenimentelor importante (autentificări, eșecuri, modificări) cu adresa IP, păstrată pentru protecția contului.

## Metode de autentificare

- Parolă, sau cod ori link de unică folosință trimis pe email sau telefon. Codurile și linkurile funcționează o singură dată și expiră în câteva minute.
- Aplicație de autentificare (TOTP) și coduri de recuperare. Secretul aplicației este păstrat criptat, iar codurile de recuperare doar ca valori hash protejate.
- Passkey, amprentă și Face ID. Când folosiți amprenta sau fața, verificarea o face telefonul sau calculatorul dumneavoastră. Amprenta sau fața nu părăsesc niciodată dispozitivul și noi nu le primim și nu le stocăm. Păstrăm doar o cheie publică din care nu se pot reconstitui datele biometrice, numele pe care îl dați passkey-ului și data ultimei utilizări.
- Chei de securitate, aprobare prin notificare și autentificare cu Google, Apple, Microsoft, GitHub sau Facebook. La autentificarea socială furnizorul ne comunică identificatorul și emailul verificat; furnizorul are propria notă de confidențialitate.

## De ce prelucrăm și temeiul legal

Pentru a vă permite autentificarea și utilizarea serviciului (executarea contractului), pentru securitatea conturilor, detectarea fraudei și prevenirea abuzurilor (interes legitim), pentru a vă reține dispozitivul dacă o cereți (consimțământ) și pentru obligații legale.

## Cookie-uri

Folosim doar cookie-uri strict necesare autentificării. Ele nu vă urmăresc și nu necesită banner de consimțământ.

| Cookie | Scop | Durată |
|--------|------|--------|
| access_token | Dovedește că sunteți autentificat | Scurtă (circa 15 minute) |
| refresh_token | Vă menține autentificat fără reintroducerea datelor | Până la 30 de zile |
| sso_session | Sesiunea dumneavoastră de autentificare unică | Până la 30 de zile, inactivitate 8 ore |
| XSRF-TOKEN | Protecție împotriva cererilor falsificate | Ca sesiunea |
| sso_device | Recunoaște dispozitivul pentru securitate | Până la 400 de zile |
| sso_ml_bind, sso_wa_bind | Leagă un link magic sau un pas passkey de browserul dumneavoastră | Minute |
| sso_mfa_rd | Rețin dispozitivul pentru verificarea în doi pași, doar dacă bifați "ține minte acest dispozitiv" | 30 de zile |

## Cât timp păstrăm datele

Coduri și linkuri: minute (înregistrările se curăță în aproximativ o oră). Sesiuni: până expiră sau vă deconectați (până la 30 de zile). Date de cont: cât timp există contul. Jurnal de securitate: [PERIOADĂ, ex. 12 luni]. După ștergerea contului eliminăm datele de autentificare, sub rezerva obligațiilor legale și a rotației copiilor de rezervă.

## Destinatari

Furnizorul de găzduire și furnizorul de email, SMS sau notificări ales de noi pentru livrarea codurilor: [FURNIZORI]. Datele pot fi prelucrate în [ȚĂRI]; transferurile în afara SEE / Republicii Moldova se fac cu [GARANȚII].

## Drepturile dumneavoastră

Puteți solicita accesul, rectificarea, ștergerea, restricționarea, o copie a datelor (portabilitate) și vă puteți opune prelucrării. În pagina de securitate a contului puteți vedea și închide sesiuni, elimina aplicații de autentificare și passkey-uri și deconecta conturi sociale. Pentru alte drepturi scrieți la [CONTACT CONFIDENȚIALITATE]. Puteți depune plângere la autoritatea de supraveghere: în Republica Moldova Centrul Național pentru Protecția Datelor cu Caracter Personal (CNPDCP), în UE autoritatea națională.

## Contact

[DENUMIRE COMPANIE], [ADRESĂ], [EMAIL CONFIDENȚIALITATE], [RESPONSABIL PROTECȚIA DATELOR, DACĂ EXISTĂ].
