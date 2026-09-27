# Moodle VŠE – IntelliJ plugin

Přihlásí uživatele do [Moodle VŠE](https://moodle.vse.cz) přes školní SSO a v tool window **Moodle** zobrazí jeho jméno, uživatelské jméno a e-mail.
Z kurzu stáhne otevřené úlohy **VPL** (Virtual Programming Lab). Student úlohu otevře jako projekt v IntelliJ,
naprogramuje ji, ověří (vyhodnocení ve VPL) a odevzdá do Moodle.

## Instalace (pro studenty)

Potřeba je **IntelliJ IDEA 2026.1 nebo novější** (Plugin Verifier: kompatibilní s 2026.1.5 i 2026.2.3) a pro úlohy v Javě nějaké JDK nastavené v IDE.
Přihlášení používá vestavěný prohlížeč; od 2026.2 je to samostatný bundled plugin *Web Browser (JCEF)*, který musí zůstat zapnutý.

1. Stáhněte `moodle-vse-<verze>.zip` (soubor **nerozbalujte**).
2. V IntelliJ: *Settings → Plugins → ⚙ → Install Plugin from Disk…* a vyberte ZIP. Potvrďte restart IDE.
3. Vpravo otevřete okno **Moodle** → karta *Student* → *Přihlásit se* (školní Microsoft účet).
4. Na kartě *Úlohy* vyberte úlohu a klikněte na *Otevřít v IntelliJ*.

Novou verzi pluginu nainstalujete stejně, přes starou.

## Spuštění

Potřeba je JDK 21 (Gradle si ho případně stáhne přes toolchains).

```bash
./gradlew runIde        # spustí sandbox IntelliJ IDEA s pluginem
./gradlew test          # unit testy
./gradlew buildPlugin   # build/distributions/moodle-vse-<verze>.zip
./gradlew verifyPlugin  # Plugin Verifier proti 2026.1.5 a 2026.2.3
./gradlew runIde262     # sandbox s IntelliJ 2026.2.3 (JCEF je tam samostatný plugin)
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
- *Settings → Tools → Moodle VŠE* obsahuje adresu Moodle (výchozí `https://moodle.vse.cz`), seznam kurzů s úlohami VPL
  (výchozí `23982`; stačí vložit i adresu kurzu), název sekce s tréninkovými úlohami (výchozí `Trénink`)
  a složku pro projekty úloh (výchozí `~/MoodleVSE`).
- Po startu IDE se uložený token automaticky ověří (`core_webservice_get_site_info`). Když ho Moodle odmítne
  (`invalidtoken`, `accessexception`), token se smaže a plugin je nepřihlášený. Při výpadku sítě token zůstane
  a v tool window je tlačítko *Zkusit znovu*.

### Úlohy VPL

Tool window **Moodle** má karty **Úlohy**, **Trénink**, **Student** a v projektu úlohy navíc kartu **Úloha**.

1. **Úlohy**: výběr kurzu a seznam jeho úloh VPL ze cvičení s termínem odevzdání (méně než 24 h zbývá → červeně).
   Tréninkové úlohy (viz níže) tu nejsou.
   Ve výchozím stavu jsou vidět jen otevřené úlohy (dostupné a v termínu), přepínač *Jen otevřené* ukáže i ostatní.
2. **Otevřít v IntelliJ** (nebo dvojklik) stáhne zadané soubory a poslední odevzdání do
   `~/MoodleVSE/<kurz>/<úloha>/` a otevře složku jako projekt. U úloh v Javě se vytvoří i `.idea/` s modulem
   (zdrojová složka `src/`, pokud ji úloha používá, jinak kořen) a nejnovějším JDK z *Project Structure*.
   Už stažená úloha se jen otevře, místní změny zůstanou.
3. Na kartě **Úloha** (a v *Tools → Moodle VPL*):
   - **Ověřit**: uloží editory, odevzdá soubory a spustí vyhodnocení VPL. Průběh se ukazuje v progress baru a
     výsledek (překlad, hodnocení, navrhovaná známka) na kartě Úloha. VPL vždy vyhodnocuje poslední odevzdání,
     proto ověření vždy znamená i odevzdání (stejně jako tlačítko *Vyhodnotit* ve webovém editoru VPL). Pokud další
     vyhodnocení snižuje známku (`reductionbyevaluation`), plugin se předem zeptá.
   - U tréninkové úlohy je pod názvem štítek *Trénink* (s tématem).
   - **Odevzdat**: odevzdá soubory bez vyhodnocení.
   - **Stáhnout z Moodle**: přepíše místní soubory posledním odevzdáním.
   - *Zadání v Moodle*: otevře stránku úlohy v prohlížeči.

Odevzdávají se všechny soubory ve složce úlohy kromě `.idea/`, `*.iml`, `out/`, `build/`, `target/`, `bin/`, `.git/`
a `.moodle-vpl.json` (vazba projektu na úlohu, bez tajných údajů). Soubory nad 1 MB plugin odmítne.
Na konec každého zdrojového souboru v odevzdané kopii plugin připojí komentář s e-mailem přihlášeného studenta
(např. `// Odevzdano pres IntelliJ (Moodle VSE): jan.novak@vse.cz`, jen ASCII, protože server VPL překládá v US-ASCII; bez e-mailu uživatelské jméno). Lokální soubory
se nemění, datové a binární soubory (`.txt`, `.csv`…) zůstanou bez komentáře a při stažení z Moodle se komentář odstraní.
Na kartě **Student** lze vyplnit *Ostatní členové týmu* (oddělené čárkou, jen ASCII). Každý člen se připíše na další řádek
(`// Clen tymu (Moodle VSE): xnovj01@vse.cz`). Hodnota se ukládá v nastavení IDE a platí pro všechny úlohy. Když mezitím v Moodle
vzniklo novější odevzdání (např. z webového editoru), VPL se zeptá, jestli ho přepsat.

### Trénink

Tréninkové úlohy na domácí procvičování jsou ve stejném kurzu jako úlohy ze cvičení, v sekci **Trénink**
(název jde změnit v nastavení). Témata jsou podsekce (Moodle 4.5, `mod_subsection`), úlohy můžou být i přímo v sekci.

Karta **Trénink** ukazuje strom *téma → úlohy* s hledáním, filtrem obtížnosti a přepínačem *Skrýt vyřešené*.
U tématu je postup (např. *Kolekce 4/9*), u úlohy stav (nová / rozpracovaná / vyřešená) a nejlepší dosažená známka.
Stav se ukládá lokálně (`moodle-vse-training.xml` v nastavení IDE) pokaždé, když plugin dostane výsledek VPL tréninkové
úlohy: po *Ověřit*, po otevření úlohy a po *Stáhnout z Moodle*. Karta proto nevolá VPL pro každou úlohu zvlášť; úlohy
ověřené jen ve webovém editoru se ukážou až po otevření v IntelliJ. Úloha je vyřešená, když navrhovaná známka dosáhne
maxima (u úloh bez hodnocení, když projdou všechny testy podle souhrnu `N tests run/ N tests passed`).

*Další úloha k procvičení* otevře nejlehčí nevyřešenou úlohu vybraného tématu (bez výběru ze všech témat).
Otevírání a ověřování funguje stejně jako u ostatních úloh.

Doporučené nastavení tréninkových úloh VPL v Moodle:

- bez termínu odevzdání,
- `reductionbyevaluation = 0` (neomezené ověřování bez srážek),
- hodnocení s maximem (např. 10 bodů) a vahou 0 v hodnocení kurzu, aby VPL vracel navrhovanou známku; úplně vypnuté
  hodnocení funguje taky, ale „vyřešeno“ se pak pozná jen ze souhrnu testů,
- společné testovací skripty a prostředí přes *Založeno na* (based on),
- obtížnost jako hvězdičky na začátku názvu, např. `★★ Seřazení seznamu` (lze psát i `**`).

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

## Jak funguje práce s VPL

VPL má vlastní web service (`mod_vpl_info/open/save/evaluate/get_result`), ta ale patří jen do služby `mod_vpl_edit`,
ne do `moodle_mobile_app`. Token ze SSO ji tedy volat nemůže a služba `mod_vpl_edit` musí být zapnutá administrátorem.
Plugin proto používá endpoint webového editoru VPL `mod/vpl/forms/edit.json.php`, kterému stačí přihlášená webová session:

1. Seznam úloh: `core_course_get_contents` (`excludecontents`) přes mobilní token. U modulů je pole
   `dates` (začátek a termín) a `uservisible`. Filtr `modname=vpl` se neposílá, protože by vyřadil i moduly
   `subsection`: podsekce je v odpovědi samostatná sekce (`component = mod_subsection`, `itemid` = instance) a v nadřazené
   sekci ji zastupuje modul `modname = subsection` se stejným `instance`. Úlohy z podsekce se tak přiřadí k nadřazené
   sekci (podle ní se pozná trénink) a podsekce je jejich téma.
2. Webová session: `tool_mobile_get_autologin_key` (s `privateToken` ze SSO přihlášení, User-Agent obsahuje
   `MoodleMobile`, jinak Moodle klíč nevydá) → `admin/tool/mobile/autologin.php?userid=…&key=…` → cookie `MoodleSession`.
   Stejně oficiální aplikace otevírá stránky v prohlížeči. Moodle vydá klíč jednou za 6 minut, proto se session
   sdílí (`VplService`) a obnovuje se, až když `core_session_time_remaining` ohlásí, že vypršela.
3. `edit.json.php?id=<cmid>&action=…` s JSON tělem: `resetfiles` (zadané soubory), `load` (poslední odevzdání
   + výsledek), `save` (`{files:[{name,contents,encoding}],comments,version}`; binární soubory Base64 s `encoding=1`),
   `evaluate` (vrátí jail server a `monitorPath`), `retrieve` (výsledek), `cancel`.
4. Vyhodnocení: WebSocket `wss://<jail>:<securePort>/<monitorPath>` posílá `message:<stav>` a nakonec `retrieve:`,
   potom plugin zavolá `retrieve`.

Ověřeno proti zdrojům mod_vpl 4.5 a proti moodle.vse.cz (Moodle 4.5) bez přihlášení: endpointy existují a bez session vracejí
očekávané chyby.

## Struktura kódu

```
cz.vse.moodle
├── api/        HTTP a Moodle API bez závislosti na UI
│   ├── MoodleClient        REST klient (site URL + token): uživatel, obsah kurzu, autologin klíč
│   ├── MoodlePublicApi     volání bez tokenu (tool_mobile_get_public_config)
│   ├── MoodleResponses     parsování JSON + detekce chybové odpovědi Moodle
│   └── MoodleException     chyba hlášená Moodlem (errorcode, isInvalidToken())
├── auth/       SSO dialog, parser/ověření launch tokenu, ruční token, PasswordSafe
├── session/    MoodleSessionService (stav přihlášení, getClient()), topic MoodleSessionListener
├── settings/   nastavení (adresa webu, kurzy, sekce tréninku, složka úloh)
├── training/   tréninkové úlohy: TrainingTasks (sekce, obtížnost, skóre, další úloha), TrainingProgress (lokální stav)
├── ui/         tool window: karty Student (MoodleAccountPanel), Úlohy (VplAssignmentsPanel), Trénink (TrainingPanel),
│               Úloha (VplTaskPanel); výběr kurzu sdílí CourseSelector
└── vpl/        úlohy VPL
    ├── api/            VplWebSession (autologin + cookies), VplApi (load/save/evaluate/retrieve), VplMonitor (WebSocket)
    ├── VplService      sdílená webová session pro všechny projekty
    ├── VplTaskOpener   stažení úlohy a otevření projektu
    ├── VplTaskService  odevzdání/ověření/stažení v projektu úlohy (.moodle-vpl.json)
    └── VplProjectFiles bezpečný zápis souborů z Moodle, výběr souborů k odevzdání, .idea pro Javu
```

Nové funkce získají přihlášeného klienta přes `MoodleSessionService.getInstance().getClient()` a volají ho
v `Task.Backgroundable`. Změny přihlášení sledují přes `MoodleSessionListener.TOPIC`.

## Známá omezení
- Texty UI jsou zatím česky natvrdo (bez resource bundle).
