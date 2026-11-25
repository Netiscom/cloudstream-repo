# Cloudstream Repo (Kodi Scrapers)

Repositorio de extensiones para **Cloudstream 3** adaptado a partir de los canales de Kodi (`canales/`).

## Canales incluidos

- **AresHD** (`AresHD/`): Películas, series, búsqueda, géneros y resolución de enlaces vía `#__NEXT_DATA__` + `player.areshd.com` + extractores integrados de Cloudstream (`Voe`, `Dood`, `StreamWish`, `VidHide`, `Vidara`).
- **VoE con altcha** (`VoeAltcha.kt`): los espejos de VoE (voe.sx, caseyemavoid.com, morencius.com) sirven una verificación «Confirm you're human» con challenge altcha (PBKDF2/SHA-256). El extractor resuelve el proof-of-work en paralelo y descifra el config ofuscado, igual que el addon de Kodi.

## Cómo compilar localmente (Windows)

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat AresHD:make
```

El archivo compilado `.cs3` se generará en `AresHD\build\AresHD.cs3` (y también puedes generar `build\plugins.json` con `.\gradlew.bat make makePluginsJson`).

Para instalarlo directamente por ADB en un dispositivo conectado:

```powershell
.\gradlew.bat AresHD:deployWithAdb
```

## Cómo publicarlo en GitHub para Cloudstream

1. Sube este proyecto a `Netiscom/cloudstream-repo` y crea una rama vacía llamada `builds` (o deja que el workflow la use después de crearla una vez).
2. Ya está configurado para `Netiscom/cloudstream-repo` en `build.gradle.kts` (`setRepo`) y `repo.json` (`pluginLists`). Si lo mueves a otro repositorio, actualiza ambos.
3. En GitHub: **Settings → Actions → General → Workflow permissions → Read and write permissions**.
4. Cada push a `main` o `master` ejecutará `.github/workflows/build.yml` y publicará los `.cs3` y `plugins.json` en la rama `builds`.
5. En Cloudstream: **Ajustes → Extensiones → Añadir repositorio** y pega la URL raw de `repo.json`:
   `https://raw.githubusercontent.com/Netiscom/cloudstream-repo/main/repo.json`
