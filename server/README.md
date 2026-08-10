# Radiogids-proxy

Klein cachend doorgeefluik voor de Radio France Open API, bedoeld voor een
gratis GCP e2-micro (of elk ander kaal Linux-doosje). Hiermee hoeft de
persoonlijke API-sleutel niet in gedeelde APK's: de app praat met deze proxy
en meldt zich met een gedeeld token. Antwoorden worden een uur gecachet en
ongewijzigd doorgegeven; de proxy laat uitsluitend de grid-query van de app
door en houdt een dagteller bij die ruim onder de CGU-limiet van 1000
requests/dag blijft.

Geen dependencies — alleen Python 3-standaardbibliotheek.

## Installatie (Debian/Ubuntu)

```bash
sudo mkdir -p /opt/radiogids
sudo curl -fsSL -o /opt/radiogids/radiogids.py \
  https://raw.githubusercontent.com/<user>/<repo>/main/server/radiogids.py

# Secrets — NIET in git; token bijv. genereren met: openssl rand -hex 24
sudo tee /etc/radiogids.env >/dev/null <<'EOF'
RADIOFRANCE_API_KEY=<jouw Open API-sleutel>
RADIOGIDS_TOKEN=<zelfgekozen random token>
PORT=8080
EOF
sudo chmod 600 /etc/radiogids.env

sudo curl -fsSL -o /etc/systemd/system/radiogids.service \
  https://raw.githubusercontent.com/<user>/<repo>/main/server/radiogids.service
sudo systemctl daemon-reload
sudo systemctl enable --now radiogids
```

GCP-firewall: poort 8080 openzetten voor de VM, bijv.

```bash
gcloud compute firewall-rules create radiogids \
  --allow tcp:8080 --direction INGRESS --target-tags radiogids
gcloud compute instances add-tags <instance-naam> --tags radiogids --zone <zone>
```

## Testen

```bash
curl http://<extern-ip>:8080/health
curl -s http://<extern-ip>:8080/graphql \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"query":"{ grid(start: 1754776800, end: 1754863200, station: FRANCEINTER) { ... on DiffusionStep { start end diffusion { title } } } }"}'
```

Zonder (of met fout) token hoort dezelfde call een 401 te geven.

## App-kant

In `local.properties` van de app:

```properties
radiogids.url=http://<extern-ip>:8080/graphql
radiogids.token=<zelfde token>
```

Met deze twee gezet gebruikt de app de proxy (en is `radiofrance.api.key`
alleen nog nodig als fallback op je eigen telefoon); de APK is dan veilig te
delen. Let op: het token zit dan wél in de APK — dat is de opzet (het bewaakt
alleen jouw proxy, niet de Radio France-sleutel).
