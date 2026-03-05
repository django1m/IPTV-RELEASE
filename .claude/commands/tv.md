# Google TV Remote Control & Deployment

Skill pour gérer le Google TV via ADB. L'appareil est à l'adresse `192.168.0.213`.

## Instructions

Tu es un assistant qui contrôle un Google TV via ADB (Android Debug Bridge).

### Connexion

1. Vérifie d'abord si l'appareil est déjà connecté avec `adb devices`
2. Si non connecté, exécute `adb connect 192.168.0.213:5555`
3. Si l'authentification échoue, indique à l'utilisateur d'accepter sur l'écran du TV
4. Confirme la connexion avec `adb devices` - le statut doit être "device" (pas "unauthorized")

### Commandes disponibles

En fonction de l'argument passé au skill, exécute l'action correspondante :

**`status`** (ou pas d'argument) - Affiche l'état de connexion et les infos de l'appareil :
```bash
adb -s 192.168.0.213:5555 shell getprop ro.product.model
adb -s 192.168.0.213:5555 shell getprop ro.build.version.release
adb -s 192.168.0.213:5555 shell dumpsys battery
adb -s 192.168.0.213:5555 shell wm size
adb -s 192.168.0.213:5555 shell ip addr show wlan0 | grep inet
```

**`deploy`** - Build et déploie l'app IPTV Player sur le Google TV :
1. Build l'APK avec `./gradlew assembleDebug` depuis le dossier `android-tv-app/`
2. Installe avec `adb -s 192.168.0.213:5555 install -r <chemin_apk>`
3. Lance l'app avec `adb -s 192.168.0.213:5555 shell am start -n com.iptvplayer.tv/.ui.login.LoginActivity`

**`launch`** - Lance l'app IPTV Player :
```bash
adb -s 192.168.0.213:5555 shell am start -n com.iptvplayer.tv/.ui.login.LoginActivity
```

**`stop`** - Arrête l'app IPTV Player :
```bash
adb -s 192.168.0.213:5555 shell am force-stop com.iptvplayer.tv
```

**`restart`** - Redémarre l'app (stop + launch)

**`log`** ou **`logs`** - Affiche les logs de l'app :
```bash
adb -s 192.168.0.213:5555 logcat -d -t 100 --pid=$(adb -s 192.168.0.213:5555 shell pidof com.iptvplayer.tv) 2>/dev/null || adb -s 192.168.0.213:5555 logcat -d -t 50 -s "ExoPlayer" "iptvplayer" "ActivityManager"
```

**`screen`** - Capture l'écran du TV :
```bash
adb -s 192.168.0.213:5555 exec-out screencap -p > /tmp/googletv_screen.png
```
Puis affiche le fichier avec l'outil Read.

**`input <texte>`** - Envoie du texte au TV :
```bash
adb -s 192.168.0.213:5555 shell input text "<texte>"
```

**`key <touche>`** - Envoie une touche. Touches courantes :
- `home` → KEYCODE_HOME
- `back` → KEYCODE_BACK
- `up` → KEYCODE_DPAD_UP
- `down` → KEYCODE_DPAD_DOWN
- `left` → KEYCODE_DPAD_LEFT
- `right` → KEYCODE_DPAD_RIGHT
- `enter` / `ok` → KEYCODE_DPAD_CENTER
- `play` → KEYCODE_MEDIA_PLAY_PAUSE
- `vol+` → KEYCODE_VOLUME_UP
- `vol-` → KEYCODE_VOLUME_DOWN
- `mute` → KEYCODE_MUTE
- `power` → KEYCODE_POWER
- `menu` → KEYCODE_MENU
- `settings` → lance les paramètres

```bash
adb -s 192.168.0.213:5555 shell input keyevent <KEYCODE>
```

**`apps`** - Liste les apps installées :
```bash
adb -s 192.168.0.213:5555 shell pm list packages -3
```

**`install <chemin_apk>`** - Installe un APK :
```bash
adb -s 192.168.0.213:5555 install -r "<chemin_apk>"
```

**`uninstall <package>`** - Désinstalle une app :
```bash
adb -s 192.168.0.213:5555 shell pm uninstall "<package>"
```

**`reboot`** - Redémarre le TV (demander confirmation à l'utilisateur) :
```bash
adb -s 192.168.0.213:5555 reboot
```

**`shell <commande>`** - Exécute une commande shell arbitraire :
```bash
adb -s 192.168.0.213:5555 shell <commande>
```

**`open <url>`** - Ouvre une URL dans le navigateur du TV :
```bash
adb -s 192.168.0.213:5555 shell am start -a android.intent.action.VIEW -d "<url>"
```

**`wake`** - Réveille le TV :
```bash
adb -s 192.168.0.213:5555 shell input keyevent KEYCODE_WAKEUP
```

**`sleep`** - Met le TV en veille :
```bash
adb -s 192.168.0.213:5555 shell input keyevent KEYCODE_SLEEP
```

### Argument reçu : $ARGUMENTS

Analyse l'argument et exécute la commande appropriée. Si aucun argument n'est donné, exécute `status`.

### Règles

- Toujours vérifier la connexion ADB avant d'exécuter une commande
- Utiliser `-s 192.168.0.213:5555` pour cibler spécifiquement ce device
- Pour les actions destructives (reboot, uninstall), demander confirmation
- Afficher les résultats de manière claire et formatée
- Si une commande échoue, diagnostiquer le problème et proposer des solutions
