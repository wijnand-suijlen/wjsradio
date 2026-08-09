# Radio

Een op maat gemaakte radio- en podcast-app voor Android 15, met publieke en gerenommeerde zenders uit Nederland, België, Duitsland, Frankrijk en het Verenigd Koninkrijk — plus de podcasts van diezelfde omroepen en de mogelijkheid om eigen RSS-feeds toe te voegen.

## Installeren

Sluit je telefoon aan met USB-debugging aan en draai:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installDebug
```

## Zenders aanpassen

Bewerk de lijst in `app/src/main/java/nl/wijnand/radio/Stations.kt`; de podcastfeeds staan in `Podcasts.kt`. Eigen podcastfeeds kun je ook in de app zelf toevoegen (Podcasts → "RSS-feed toevoegen").

## Podcast-feed-URL's vinden

Van makkelijk naar hardnekkig:

1. **De website van de omroep.** Zoek op de podcastpagina naar een RSS-icoon of een link "RSS" / "Abonneren". NPO zet feeds op `podcast.npo.nl`, Radio France heeft op elke programmapagina een RSS-knop onder "S'abonner", en bij BBC-programma's staat de feed onderaan de pagina bij "Podcast Feed".

2. **De Apple Podcasts-catalogus (werkt bijna altijd).** Vrijwel elke podcast staat bij Apple geregistreerd mét de originele feed-URL:

   ```bash
   curl -s "https://itunes.apple.com/search?media=podcast&term=met+het+oog+op+morgen" | grep -o '"feedUrl":"[^"]*"'
   ```

   Het veld `feedUrl` is precies wat je nodig hebt.

3. **De broncode van de podcastpagina.** Veel pagina's linken de feed onzichtbaar in de HTML-kop; zoek in de paginabron naar:

   ```html
   <link rel="alternate" type="application/rss+xml" href="...">
   ```

4. **Podcast Index.** [podcastindex.org](https://podcastindex.org) is een open catalogus; de feed-URL staat direct bij het zoekresultaat.

**Controleren of het echt een feed is:**

```bash
curl -s --max-time 8 -r 0-2048 -L "<URL>" | head -20
```

Je wilt XML zien dat met `<rss` of `<feed` begint. De app valideert een toegevoegde feed zelf ook nog: hij haalt hem op en weigert hem als er geen afleveringen in staan.

Let op: Spotify toont geen RSS-URL's (Spotify-exclusieve podcasts hebben vaak helemaal geen open feed), en de meeste VRT-programma's zitten alleen nog in de eigen VRT-app — de ingebouwde VRT-feeds zijn hun officiële open RSS-endpoints.
