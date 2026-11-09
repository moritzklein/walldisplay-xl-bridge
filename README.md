# walldisplay-xl-bridge (WallKeys)

> [!IMPORTANT]
> **Diese App wurde zu 100 % von einer KI erzeugt – von Claude (Anthropic), über Claude Code.**
> Quellcode, Build-Skript, Webinterface und diese Anleitung wurden vollständig von Claude geschrieben.
> Auch die Analyse der Gerätefirmware (wie Tasten, Sensoren und Relais angesprochen werden) hat Claude per `adb` selbst durchgeführt.
> Der Repository-Inhaber hat die Anforderungen formuliert und die Tests am echten Gerät durchgeführt, aber keine Zeile Code geschrieben.
> Nutzung auf eigene Gefahr – siehe [Haftungsausschluss](#haftungsausschluss).

Eine kleine Android-App ohne Root für das **Shelly Wall Display XL** (Modell SAWD-3A1XE10EU2). Sie macht die Hardware des Displays direkt im eigenen Smarthome nutzbar, **ohne die Shelly-Oberfläche oder die Shelly-Cloud**. Gedacht ist sie für alle, die auf dem Display eine eigene Visualisierung betreiben (z. B. mit Fully Kiosk Browser).

| Hardware | Funktion in WallKeys |
|---|---|
| 4 Hardware-Tasten | kurz / lang / Loslassen nach Langdruck → **KNX**, **MQTT**, **Webhook**, **Relais** |
| Temperatur & Luftfeuchte | → MQTT, optional KNX (DPT 9.001 / 9.007) |
| Lichtsensor | → MQTT, optional KNX (DPT 9.004), **automatische Helligkeit** |
| Näherungssensor | → MQTT, optional KNX (DPT 1.002), **Standby & Aufwecken** |
| Relais | ← MQTT, Taste, Webinterface; Status → MQTT, KNX |
| Hintergrundbeleuchtung | Auto-Helligkeit, Standby, steuerbar per MQTT |

Konfiguriert wird alles über ein **Webinterface auf Port 8080**. Die App startet nach jedem Neustart von selbst und braucht dafür **weder Root noch eine dauerhafte adb-Verbindung**.

> Dieses Projekt steht in keiner Verbindung zu Shelly / Allterco Robotics. „Shelly“ wird nur genannt, um das kompatible Gerät zu beschreiben.

---

## Inhalt

- [Funktionen im Detail](#funktionen-im-detail)
- [Installation per adb](#installation-per-adb)
- [Optional: Shelly-App (Stargate) abschalten](#optional-shelly-app-stargate-abschalten)
- [Konfiguration](#konfiguration)
- [MQTT-Topics](#mqtt-topics)
- [KNX-Telegramme](#knx-telegramme)
- [HTTP-API des Webinterfaces](#http-api-des-webinterfaces)
- [Firewall / Ports](#firewall--ports)
- [Aktualisieren & Deinstallieren](#aktualisieren--deinstallieren)
- [Selbst bauen](#selbst-bauen)
- [Technischer Hintergrund](#technischer-hintergrund)
- [Fehlersuche](#fehlersuche)
- [Haftungsausschluss](#haftungsausschluss)

---

## Funktionen im Detail

**Tasten**
- Pro Taste drei Ereignisse: **kurz**, **lang** (ab 800 ms, einstellbar) und **Loslassen nach Langdruck**. Damit klappt z. B. Dimmen: Langdruck = `3.007 +`, Loslassen = `3.007 stop`.
- Pro Taste und Ereignis frei kombinierbar:
  - **KNX:** beliebig viele Telegramme. Bei `toggle` wird vorher die Status-Gruppenadresse gelesen, dann wird echt umgeschaltet.
  - **MQTT:** ein Standard-Topic plus ein eigenes Topic mit eigener Payload.
  - **Webhook:** GET/POST/PUT, Basic-Auth direkt in der URL (z. B. DoorBird-Türöffner), selbstsignierte HTTPS-Zertifikate optional erlaubt.
  - **Relais:** lokal ein, aus oder umschalten. Das funktioniert auch ohne Netzwerk.

**KNX direkt (KNXnet/IP)**
- **Tunneling** (Unicast, UDP 3671, NAT-Modus) zu jedem KNX-IP-Router bzw. jeder KNX-IP-Schnittstelle. Pro Aktion wird kurz ein Tunnel geöffnet und wieder geschlossen, es bleibt also kein Tunnel-Platz dauerhaft belegt.
- Alternativ **Routing** (Multicast 224.0.23.12).
- Unterstützte DPTs: 1, 2, 3, 5 (inkl. 5.001 %, 5.003 °), 6, 7, 8, 9, 12, 13, 14, 16, 17, 18, 20, 232 (RGB).
- KNX IP Secure wird **nicht** unterstützt.

**Sensoren**
- Temperatur und Luftfeuchte (CHT8305), mit einstellbarer Korrektur. Shellys Werkswert für das XL ist −1,2 K, weil die Elektronik den Sensor mit aufheizt.
- Helligkeit in Lux und Näherung (Person vor dem Display ja/nein), dazu die CPU-Temperatur.
- Messwerte im Intervall (Standard 60 s), Helligkeit zusätzlich bei deutlicher Änderung, Näherung sofort.

**Display**
- Automatische Helligkeit: Die Lux-Werte werden logarithmisch auf eine Min/Max-Helligkeit abgebildet, mit weicher Überblendung und Hysterese.
- Standby per Näherung: Annäherung oder Tastendruck macht sofort hell. Ist X Sekunden niemand da, geht die Beleuchtung auf die Standby-Helligkeit (0 % = aus). Fully läuft dabei unverändert weiter.
- Über MQTT lassen sich Standby, eine feste Helligkeit oder die Rückkehr zur Automatik setzen.

**MQTT**
- Eigener, schlanker MQTT-3.1.1-Client mit Dauerverbindung, automatischem Reconnect und Last Will (`…/status` = `online`/`offline`).
- Zustände und Messwerte werden retained gesendet.

---

## Installation per adb

### Voraussetzungen

- Shelly Wall Display XL (Android 11). Andere Shelly Wall Displays sind ungetestet, siehe [Technischer Hintergrund](#technischer-hintergrund).
- **adb über WLAN** ist auf dem Display aktiviert (Entwickleroptionen → USB-Debugging / ADB über Netzwerk, TCP-Port **5555**).
- Ein PC mit den [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) (`adb`):
  - Windows: `winget install Google.PlatformTools` oder das ZIP von der Google-Seite entpacken
  - macOS: `brew install android-platform-tools`
  - Debian/Ubuntu: `sudo apt install adb`

### 1. APK herunterladen

`WallKeys.apk` von der [Releases-Seite](../../releases/latest) herunterladen, oder [selbst bauen](#selbst-bauen).

### 2. Mit dem Display verbinden

IP-Adresse des Displays anpassen (Beispiel `192.168.1.50`):

```bash
adb connect 192.168.1.50:5555
```

```bash
adb devices -l
```

Das Display muss als `device` erscheinen (Modell `Blake`). Steht dort `unauthorized`, die adb-Anfrage auf dem Display bestätigen.

### 3. App installieren

```bash
adb install -r WallKeys.apk
```

### 4. Dienst aktivieren

WallKeys läuft als Bedienungshilfen-Dienst (AccessibilityService). Android startet ihn nach jedem Neustart automatisch. Einmalig aktivieren:

```bash
adb shell settings put secure enabled_accessibility_services de.wallkeys/de.wallkeys.KeyService
```

```bash
adb shell settings put secure accessibility_enabled 1
```

> ⚠️ Der erste Befehl **ersetzt** die Liste der aktiven Bedienungshilfen. Falls auf dem Display schon andere aktiv sind (prüfen mit `adb shell settings get secure enabled_accessibility_services`), alle durch `:` getrennt angeben, z. B. `andere.app/.Dienst:de.wallkeys/de.wallkeys.KeyService`.

### 5. Selbstheilung erlauben (empfohlen)

Stürzt ein Bedienungshilfen-Dienst ab, bindet Android ihn **nicht automatisch neu**. Ohne Gegenmaßnahme wären Tasten und Webinterface bis zur manuellen Reaktivierung weg. Ein eingebauter Watchdog prüft deshalb alle 15 Minuten, ob WallKeys läuft, und aktiviert den Dienst bei Bedarf neu. Dafür braucht er einmalig diese Berechtigung:

```bash
adb shell pm grant de.wallkeys android.permission.WRITE_SECURE_SETTINGS
```

### 6. Prüfen

```bash
adb shell "dumpsys accessibility | grep -A1 'Enabled services'"
```

Danach im Browser **`http://<IP-des-Displays>:8080`** öffnen. Im Protokoll unten auf der Seite steht `WallKeys … gestartet`. Wer jetzt eine Hardware-Taste drückt, sieht dort `Taste 1 (1): kurz`.

Die adb-Verbindung wird danach nicht mehr gebraucht. Sie ist nur für Updates nötig.

---

## Optional: Shelly-App (Stargate) abschalten

Die vorinstallierte Shelly-App (`cloud.shelly.stargate`) regelt ebenfalls Helligkeit und Relais und reagiert selbst auf die Tasten. Wer ausschließlich eine eigene Visu nutzt, kann sie **reversibel** deaktivieren. WallKeys übernimmt dann Helligkeit, Standby, Sensoren, Relais und Tasten:

```bash
adb shell pm disable-user --user 0 cloud.shelly.stargate
```

Rückgängig machen:

```bash
adb shell pm enable cloud.shelly.stargate
```

Was ohne Stargate wegfällt:
- Shelly-Oberfläche und Shelly-Cloud
- **Shelly-Firmware-Updates**
- Shellys Thermostat-Funktion

Danach einmal neu starten (`adb shell reboot`) und prüfen, ob der Kiosk-Browser wie gewohnt startet.

> Tipp: Falls Fully Kiosk eine eigene Helligkeits- oder Bildschirmschoner-Steuerung aktiv hat, diese abschalten, damit sich Fully und WallKeys nicht gegenseitig verstellen. Alternativ in WallKeys unter „Display“ die Helligkeitssteuerung deaktivieren.

---

## Konfiguration

Alles läuft über das Webinterface `http://<IP>:8080`:

| Bereich | Inhalt |
|---|---|
| **Live** | aktuelle Sensorwerte, Helligkeit, Relais mit Schaltknöpfen |
| **Tasten** | pro Taste und Ereignis: KNX-Telegramme, Relais-Aktion, MQTT-Topic/-Payload, Webhook; mit Test-Knopf |
| **KNX** | Router-IP, Port, Tunneling/Routing, Syntax-Hilfe |
| **Sensoren** | Intervall, Korrekturwerte, KNX-Gruppenadressen |
| **Relais** | aktivieren, Invertierung, KNX-Status-GA |
| **Display** | Auto-Helligkeit (Min/Max und Lux-Punkte), Standby-Zeit und -Helligkeit |
| **MQTT** | Broker, Zugangsdaten, Basis-Topic, QoS, Retain |
| **HTTP** | globale Webhook-URL, Timeout, TLS |
| **Allgemein** | Langdruck-Zeit, Webinterface-Port, **Webinterface-Passwort** |

Die Konfiguration liegt als JSON im privaten App-Verzeichnis und bleibt bei Updates erhalten. Platzhalter in MQTT- und Webhook-Feldern: `{button}` (1–4), `{name}`, `{event}` (`short`/`long`/`release`).

> 🔒 Das Webinterface ist ohne Passwort für jeden im Netz erreichbar und zeigt die gespeicherten Zugangsdaten (MQTT, Webhook-URLs). **Unter „Allgemein“ ein Passwort setzen** und Port 8080 per Firewall nur für vertrauenswürdige Geräte freigeben.

---

## MQTT-Topics

Basis-Topic standardmäßig `walldisplay` (einstellbar).

| Topic | Richtung | Payload | Retained |
|---|---|---|---|
| `walldisplay/status` | → | `online` / `offline` (Last Will) | ja |
| `walldisplay/button/1` … `/4` | → | `short`, `long`, `release` | einstellbar |
| `walldisplay/sensor/temperature` | → | °C, z. B. `23.2` | ja |
| `walldisplay/sensor/humidity` | → | % rF, z. B. `40` | ja |
| `walldisplay/sensor/illuminance` | → | lx | ja |
| `walldisplay/sensor/proximity` | → | `near` / `far` | ja |
| `walldisplay/sensor/cpu_temperature` | → | °C | ja |
| `walldisplay/relay` | → | `ON` / `OFF` | ja |
| `walldisplay/relay/set` | ← | `ON`, `OFF`, `TOGGLE` | – |
| `walldisplay/display` | → | `ON` (aktiv) / `OFF` (Standby) | ja |
| `walldisplay/display/brightness` | → | 0–100 | ja |
| `walldisplay/display/set` | ← | `ON` / `OFF` | – |
| `walldisplay/display/brightness/set` | ← | `0`–`100` oder `auto` | – |

Zusätzlich kann jede Taste ein beliebiges eigenes Topic mit eigener Payload senden.

---

## KNX-Telegramme

Eine Zeile pro Telegramm: `Gruppenadresse DPT Wert`. Kommentare mit `#`.

```text
1/1/10 1.001 1              # Licht ein
1/1/10 1.001 toggle 1/1/11  # umschalten (liest vorher Status-GA 1/1/11)
1/2/10 3.007 +              # heller dimmen   (Langdruck)
1/2/10 3.007 stop           # Dimmen stoppen  (Loslassen)
2/1/5  1.008 1              # Jalousie ab
3/2/5  5.001 75             # 75 %
4/0/1  9.001 21.5           # Sollwert 21,5 °C
2/0/7  17.001 3             # Szene 3 abrufen
2/0/7  18.001 learn 3       # Szene 3 speichern
5/0/9  232.600 #FF8800      # RGB
```

Gruppenadressen sind dreistufig (`1/2/3`), zweistufig (`1/234`) oder als Zahl möglich. Fehlerhafte Zeilen werden beim Speichern im Webinterface sofort gemeldet.

---

## HTTP-API des Webinterfaces

| Methode & Pfad | Funktion |
|---|---|
| `GET /api/status` | Sensorwerte, Relais, Display, MQTT-Status, Protokoll |
| `GET /api/config` / `POST /api/config` | Konfiguration lesen / speichern (JSON) |
| `POST /api/test?b=1&e=short` | Tastendruck simulieren (`e` = `short`/`long`/`release`) |
| `POST /api/relay?cmd=toggle` | Relais schalten (`on`/`off`/`toggle`) |

Bei gesetztem Passwort per HTTP Basic Auth (Benutzername beliebig).

---

## Firewall / Ports

| Richtung | Port | Zweck |
|---|---|---|
| PC → Display | TCP 5555 | adb (nur für Installation/Updates) |
| PC → Display | TCP 8080 | Webinterface |
| Display → Broker | TCP 1883 | MQTT |
| Display → KNX-Router | UDP 3671 | KNXnet/IP Tunneling |
| Display → Multicast | UDP 3671 an 224.0.23.12 | nur bei KNX-Routing, nur im selben Netz |
| Display → Ziel | je nach URL | Webhooks |

---

## Aktualisieren & Deinstallieren

Update (Einstellungen bleiben erhalten):

```bash
adb install -r WallKeys.apk
```

Updates müssen **mit demselben Schlüssel signiert** sein wie die installierte Version. Wer zwischen der Release-APK und einer selbst gebauten APK wechselt, muss vorher deinstallieren.

Deinstallieren:

```bash
adb uninstall de.wallkeys
```

---

## Selbst bauen

Es wird kein Gradle und kein Android Studio benötigt. Gebraucht werden:

- JDK 11 oder neuer (getestet mit OpenJDK 25)
- Android build-tools **r34**: `https://dl.google.com/android/repository/build-tools_r34-windows.zip` (bzw. `-linux.zip` / `-macosx.zip`)
- Android-Plattform **API 30**: `https://dl.google.com/android/repository/platform-30_r03.zip`

Beide ZIPs z. B. nach `C:\android-sdk` bzw. `~/android-sdk` entpacken. Dann:

```bash
bash build.sh
```

Unter Windows funktioniert das in der Git Bash. Das Skript erzeugt beim ersten Lauf einen eigenen Signatur-Keystore `wallkeys.keystore`. **Gut aufheben**, denn spätere Updates brauchen denselben Schlüssel. Pfade und Passwort lassen sich per Umgebungsvariable setzen (siehe Kopf von `build.sh`).

```
src/de/wallkeys/
  KeyService.java   Zentrale: Tasten, Aktionen, MQTT-Befehle, Lebenszyklus (AccessibilityService)
  Config.java       JSON-Konfiguration mit Defaults
  MqttClient.java   MQTT 3.1.1 (Publish, Subscribe, Last Will, Reconnect)
  Knx.java          KNXnet/IP Tunneling & Routing, DPT-Kodierung
  HttpAction.java   Webhooks inkl. Basic Auth
  Sensors.java      Temperatur, Feuchte, Licht, Näherung, CPU
  Display.java      Auto-Helligkeit & Standby
  Relay.java        Relais über sysfs
  SafeHandler.java  Main-Thread-Handler, der Fehler abfängt statt den Dienst abstürzen zu lassen
  Watchdog.java     Job, der den Dienst nach einem Absturz wieder aktiviert
  WebServer.java    eingebauter HTTP-Server für Webinterface und API
  Log.java          Protokoll für das Webinterface
assets/index.html   Webinterface (ohne externe Abhängigkeiten)
```

---

## Technischer Hintergrund

Was beim Analysieren des Geräts herausgekommen ist (Firmware-Stand Ende 2026, Android 11, Gerätename `Blake`):

- **Tasten:** Kernel-Treiber `adc-keys` → `KEY_F1`–`KEY_F4`. Die Firmware hat Androids `PhoneWindowManager` angepasst. Diese Tasten erreichen deshalb **weder Apps noch Accessibility-Key-Filter**. Stattdessen sendet das System den Broadcast **`com.smatek.keycode_action`** mit den Extras `keycode` (131–134) und `keydown` (true/false), und auf den hört WallKeys. Ein simulierter Tastendruck per `adb shell input keyevent` kommt deshalb **nicht** an. Zum Testen stattdessen:
  ```bash
  adb shell am broadcast -a com.smatek.keycode_action --ei keycode 131 --ez keydown true
  ```
- **Näherung:** kein Android-Sensor, sondern `gpio_keys` → `KEY_F5` (Person nähert sich) / `KEY_F6` (Person entfernt sich). Diese Events kommen über den Accessibility-Key-Filter an und werden nicht verschluckt.
- **Temperatur/Feuchte:** CHT8305, lesbar unter `/sys/devices/platform/twi.1/i2c-1/1-0040/temp1_input` und `humidity1_input` (in Milli-Einheiten).
- **Licht:** normaler Android-Lichtsensor (meldet nur Änderungen).
- **Relais:** `/sys/class/strelay/relay1`, beim XL **invertiert** (`0` = ein, `1` = aus).
- **Hintergrundbeleuchtung:** `/sys/class/backlight/backlight/brightness` (0–255). Die Shelly-App steuert die Helligkeit ebenfalls direkt über diese Datei.

Andere Modelle (Wall Display, Wall Display X2 …) nutzen teils andere Pfade. Relais, Sensoren und Helligkeit sind im Webinterface aber konfigurierbar, die Tasten-Broadcasts könnten dort ebenfalls funktionieren. Ungetestet.

---

## Fehlersuche

| Problem | Lösung |
|---|---|
| Webinterface nicht erreichbar | `adb shell "dumpsys accessibility \| grep -E 'Bound\|Crashed'"`. Steht WallKeys unter *Crashed services*, den Dienst mit den Befehlen aus Schritt 4 neu aktivieren (oder bis zu 15 min auf den Watchdog warten, falls Schritt 5 erledigt ist). Ursache: `adb logcat -b crash`. Firewall TCP 8080? |
| Tasten lösen nichts aus | Protokoll im Webinterface prüfen. Mit `am broadcast …` (siehe oben) testen. Ist der Dienst nach einem Update noch aktiv? |
| MQTT „getrennt“ | Broker-IP/Port, Zugangsdaten, Firewall TCP 1883. Fehlermeldung steht im Protokoll. |
| KNX „Keine Antwort“ | Router-IP, UDP 3671 in der Firewall freigegeben? IP Secure aktiv? Alle Tunnel belegt? |
| Display zu dunkel/hell | Unter „Display“ Min/Max-Helligkeit und Lux-Punkte anpassen. |
| Display geht nicht aus | Standby-Zeit > 0? Näherung im Live-Bereich prüfen (`frei` nötig). |
| Logcat | `adb logcat -s WallKeys` |

---

## Haftungsausschluss

Diese Software wurde **vollständig von einer KI (Claude von Anthropic) generiert** und nur in einer einzigen Umgebung getestet. Sie greift auf Hardware-Schnittstellen zu (Relais, Hintergrundbeleuchtung) und kann Aktionen im Smarthome auslösen (KNX, MQTT, Webhooks, z. B. Türöffner). Prüfe die Konfiguration sorgfältig, bevor du sicherheitsrelevante Funktionen damit verknüpfst. Die Software wird ohne jede Gewährleistung bereitgestellt, siehe [LICENSE](LICENSE).

Shelly ist eine Marke der Allterco Robotics / Shelly Group. KNX ist eine Marke der KNX Association. Dieses Projekt steht mit keinem der Unternehmen in Verbindung.
