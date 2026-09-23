# Moodle VŠE – IntelliJ plugin

Přihlásí uživatele do [Moodle VŠE](https://moodle.vse.cz) přes školní SSO a v tool window **Moodle** zobrazí jeho jméno, uživatelské jméno a e-mail.

## Spuštění

Potřeba je JDK 21 (Gradle si ho případně stáhne přes toolchains).

```bash
./gradlew runIde        # spustí sandbox IntelliJ IDEA s pluginem
./gradlew test          # unit testy
./gradlew buildPlugin   # build/distributions/moodle-vse-<verze>.zip
```

ZIP se instaluje přes *Settings → Plugins → ⚙ → Install Plugin from Disk…*

Cílová platforma je IntelliJ IDEA **2026.1** (`platformVersion` v `gradle.properties`) a plugin nemá horní hranici verze.
IDEA 2026.2 a novější už vyžaduje Javu 25. 2026.1 je poslední řada na Javě 21, kterou zadání požaduje.
Samostatná „Community“ distribuce skončila verzí 2025.3, proto se používá sjednocená `intellijIdea(...)`.

## Použití

- **Tool window Moodle** (vpravo) nabízí *Přihlásit se*. Otevře se okno s vestavěným prohlížečem a přihlášením přes Microsoft účet VŠE.
  Po přihlášení se okno zavře a zobrazí se údaje uživatele a tlačítko *Odhlásit*.
- Když vestavěný prohlížeč (JCEF) není k dispozici, nebo přes odkaz *Vložit token ručně…*, lze vložit token z Moodle
  (*Předvolby → Bezpečnostní klíče*, služba *Moodle mobile web service*).
- *Settings → Tools → Moodle VŠE* obsahuje adresu Moodle (výchozí `https://moodle.vse.cz`).
- Po startu IDE se uložený token automaticky ověří (`core_webservice_get_site_info`). Když ho Moodle odmítne
  (`invalidtoken`, `accessexception`), token se smaže a plugin je nepřihlášený. Při výpadku sítě token zůstane
  a v tool window je tlačítko *Zkusit znovu*.

## Jak funguje přihlášení

Studenti nemají heslo do Moodle (přihlašují se přes OIDC / Microsoft Entra), takže `login/token.php` nepoužijeme.
Plugin jde stejnou cestou jako oficiální mobilní aplikace Moodle, jen s jednou úpravou pro VŠE:

1. `POST /lib/ajax/service-nologin.php` → `tool_mobile_get_public_config`: kontrola, že jsou zapnuté mobilní služby,
   a zjištění `launchurl` a poskytovatelů SSO.
2. Vygeneruje se náhodný `passport` a ve vestavěném prohlížeči (JBCefBrowser) se otevře
   `launch.php?service=moodle_mobile_app&passport=…&urlscheme=moodlemobile`.
3. **Specifikum VŠE:** web má `typeoflogin = 1` (přihlášení „v aplikaci“), takže `launch.php` hned skončí chybou
   *„Doplněk není povolen nebo nakonfigurován“*. Předtím ale uloží passport do cookie `tool_mobile_launch`.
   Plugin proto prohlížeč přesměruje na poskytovatele SSO (`/auth/oidc/…`). Po přihlášení Moodle
   (hook `after_login_completed`) podle cookie vrátí uživatele na `launch.php`. Tentokrát je v session nastavené `justloggedin`,
   takže launch projde. Na webech s `typeoflogin` 2/3 se tento krok neprovádí.
4. `launch.php` přesměruje na `moodlemobile://token=<base64>`. Plugin tuto navigaci zachytí a zruší
   (`CefRequestHandler.onBeforeBrowse`, pro jistotu také `onResourceRedirect`, `onProtocolExecution`,
   `onLoadError`, `onLoadEnd` a `onAddressChange`) a okno zavře.
5. Po dekódování vznikne `siteHash:::token[:::privateToken]`. Kontroluje se `siteHash == md5(siteUrl + passport)`
   (s lomítkem na konci i bez něj a také pro `wwwroot` z veřejné konfigurace). Pokud nesouhlasí, token se odmítne.
6. Pomocí `core_webservice_get_site_info` a `core_user_get_users_by_field` (e-mail) se token ověří
   a načtou se údaje. Teprve potom se token uloží do **PasswordSafe** (klíčenka OS nebo KeePass podle nastavení IDE).

Před přihlášením se ve vestavěném prohlížeči smažou cookies webu Moodle, protože `launch.php` vydá token jen hned po čerstvém
přihlášení. Cookies Microsoftu zůstávají, takže opakované přihlášení bývá bez zadávání hesla.

## Struktura kódu

```
cz.vse.moodle
├── api/        HTTP a Moodle API bez závislosti na UI
│   ├── MoodleClient        REST klient (site URL + token); sem patří další funkce (kurzy, soubory…)
│   ├── MoodlePublicApi     volání bez tokenu (tool_mobile_get_public_config)
│   ├── MoodleResponses     parsování JSON + detekce chybové odpovědi Moodle
│   └── MoodleException     chyba hlášená Moodlem (errorcode, isInvalidToken())
├── auth/       SSO dialog, parser/ověření launch tokenu, ruční token, PasswordSafe
├── session/    MoodleSessionService (stav přihlášení, getClient()), topic MoodleSessionListener
├── settings/   nastavení adresy webu
└── ui/         tool window
```

Nové funkce získají přihlášeného klienta přes `MoodleSessionService.getInstance().getClient()` a volají ho
v `Task.Backgroundable`. Změny přihlášení sledují přes `MoodleSessionListener.TOPIC`.

## Známá omezení

- Úprava v kroku 3 závisí na interním chování Moodle (`tool_mobile_launch` cookie + `justloggedin`) a na tom,
  že `auth_oidc` po přihlášení respektuje `wantsurl`. Ověřeno ručně v prohlížeči na moodle.vse.cz (9/2026).
  Pokud VŠE změní `typeoflogin` na 2 nebo 3, plugin bude dál fungovat standardní cestou.
- Kdo je v Moodle administrátor, nedostane `privateToken` (Moodle ho adminům nevydává). Plugin ho zatím nepotřebuje.
- *Odhlásit* token jen smaže z IDE, na serveru zůstává platný. Zneplatnit ho lze v Moodle v *Bezpečnostních klíčích*.
- E-mail se zobrazí, jen pokud ho služba mobilní aplikace smí přes `core_user_get_users_by_field` vrátit.
  Jinak se ukáže „nedostupný“.
- Token se posílá v těle POST požadavku, ne v query stringu, aby se neobjevoval v přístupových logech.
- Texty UI jsou zatím česky natvrdo (bez resource bundle).
