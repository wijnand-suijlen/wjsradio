# Radio France Open API — kernpunten CGU (versie 06-06-2019)

Samenvatting van de gebruiksvoorwaarden (developers.radiofrance.fr, gelezen 09-08-2026).
Deze punten mogen we niet schenden bij het uitbreiden van de app.

## Harde grenzen voor de app

- **Max. 1000 requests per dag.** Daarboven wordt de toegang geblokkeerd. Cache de
  programmagids dus agressief; nooit pollen. Requestaantal verbergen of vervalsen is
  reden voor directe accountsluiting (art. 9.1).
- **Audio alleen via directe streaming/hyperlinks.** Geen enkele vorm van opslaan,
  cachen of herhosten van hun uitzendingen of podcasts (art. 5.3, 8.4). Een
  "download voor offline"-functie is voor Radio France-content dus **verboden**
  (voor andere omroepen apart te beoordelen).
- **Data niet wijzigen** (geen inkortingen, toevoegingen of vervangingen in hun
  programmagegevens) en niet naast content plaatsen die de betekenis verdraait
  (art. 5.3, 8.6).
- **Niet commercieel gebruiken** en geen aggregatie-/doorzoekdienst voor het publiek
  bouwen op hun data (preambule, art. 5.3). Privégebruik is prima.
- **API-sleutel is strikt persoonlijk en niet overdraagbaar** (art. 4.1.4, 10.1.3).
  Dus: sleutel alleen in `local.properties` (gitignored), **nooit** in de repo, en
  **geen APK's delen waarin de sleutel is meegebakken** — maak dan een build zonder
  sleutel.

## Verplichtingen

- **Creditregel tonen**: "données fournies par l'Open API Radio France" (art. 5.4.1)
  — staat al onderaan het gids-scherm van de Franse zenders.
- Per programma tonen: programmanaam, titel van de uitzending, producent, zendernaam
  (art. 5.4.2). Geen Radio France-logo's/merken gebruiken alsof zij de app uitgeven.
- Beveiligingsincidenten binnen 24 uur melden aan support.openapi@radiofrance.com;
  Radio France mag de beveiliging auditen en aanbevelingen opleggen (10 dagen om op
  te volgen) (art. 5.1).

## Goed om te weten

- De dienst is "experimenteel": Radio France kan de voorwaarden wijzigen en de hele
  dienst met 30 dagen aankondiging stoppen, zonder compensatie (art. 7.2, 9.3).
  De Franse gids kan dus ooit wegvallen — de app moet daar netjes mee omgaan
  (foutmelding, geen crash; dat doet hij al).
- Vrijwaring: bij schending draait de gebruiker op voor claims tegen Radio France
  (art. 8.1). Frans recht, rechtbank Parijs (art. 16).
- Accountgegevens worden 3 jaar na opzegging bewaard (art. 4.2.4). Opzeggen kan via
  support.openapi@radiofrance.com.
- Het PDF-origineel: https://developers.radiofrance.fr/DN_API_CGU_vdaj06062019_fr.pdf
  — controleer bij twijfel of er inmiddels een nieuwere versie geldt.
