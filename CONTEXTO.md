# Contexto de desarrollo (handoff)

Notas para retomar el trabajo sin releer toda la conversación. Última actualización: 2026-11-25.

## Qué es esto

Repositorio de extensiones para **Cloudstream 3** que reimplementa el addon de Kodi **AresHD**.

- Fuente de referencia del scraper: `../alfa-channels/areshd/plugin.js` (addon Kodi) y sus pruebas en `../alfa-channels/test/areshd.test.js`.
- Documentación de extractores: <https://fam007e.github.io/cloudstream/index.html>
- App / stubs: <https://github.com/recloudstream/cloudstream>

## Estado actual

Compila y genera el plugin:

- `./gradlew AresHD:make` → `AresHD/build/AresHD.cs3` (versión 4).
- Última verificación: BUILD SUCCESSFUL.
- Ojo: `make` **no** regenera `build/plugins.json`; para el listado hay que correr `./gradlew makePluginsJson`.
- Publicado en **`Netiscom/cloudstream-repo`** (público): rama `main` con el código y rama `builds` con el `.cs3` y `plugins.json` que genera el workflow.
- URL para añadir el repositorio en Cloudstream: `https://raw.githubusercontent.com/Netiscom/cloudstream-repo/main/repo.json`.
- Remotos: `origin` → `Netiscom/cloudstream-repo`; `upstream` → plantilla `recloudstream/TestPlugins`.
- Workflow `.github/workflows/build.yml` corregido (setup-java v4, setup-android v3 con paquetes explícitos, `permissions: contents: write`). Última ejecución en verde y el SHA-256 del `.cs3` publicado coincide con el build local.
- El workflow también se puede lanzar a mano: `gh workflow run Build -R Netiscom/cloudstream-repo --ref main`. Hace falta porque GitHub a veces no crea el run al hacer push (incidente del 25/11 o `workflow_dispatch` con HTTP 500); si el paso **Push builds** falla con `Internal Server Error`, se relanza y listo.
- La identidad de git está configurada **solo en este repo** (`Netiscom` + noreply de GitHub).

## Cómo compilar (Windows, bash/Git Bash)

No hay JDK/Android SDK en el PATH por defecto; el build necesita ambos:

```bash
cd cloudstream-repo
export JAVA_HOME="$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
./gradlew AresHD:make --console=plain
```

Otros targets: `AresHD:deployWithAdb`, `makePluginsJson`.

## Arreglos de build ya aplicados (no revertir)

- **Kotlin 2.4.21** en `build.gradle.kts` (era 2.1.0). El jar stub `com.lagradost:cloudstream3:pre-release` tiene metadata 2.4.0 y fallaba con `Class '...Voe' was compiled with an incompatible version of Kotlin`.
- **JVM target 11** (`sourceCompatibility`/`targetCompatibility` y `jvmTarget = JVM_11`, era 1.8). El jar trae bytecode JVM 11 y no se puede inlinear en 1.8.
- Jackson debe quedarse en **2.13.1** (compatibilidad con Android antiguo); no subir.

## Mapa de archivos

| Archivo | Contenido |
| --- | --- |
| `AresHD/src/main/kotlin/com/kodi/cloudstream/AresHD.kt` | Scraper principal: home, búsqueda, géneros, películas/series/episodios y `loadLinks`. |
| `AresHD/src/main/kotlin/com/kodi/cloudstream/VoeAltcha.kt` | Extractor VoE con puerta altcha (PBKDF2/SHA-256) y descifrado del config. |
| `AresHD/src/main/kotlin/com/kodi/cloudstream/AresHDPlugin.kt` | Registro de providers y extractores. |
| `AresHD/build.gradle.kts` | `version = 4`, tipos `Movie`/`TvSeries`, idioma `es`. |
| `repo.json` | Manifiesto del repositorio para Cloudstream. |

## AresHD.kt

- Datos desde `#__NEXT_DATA__`; rutas web `/pelicula/`, `/serie/`, `/episodio/` (verificadas 200 y alineadas con los modelos Kotlin).
- Reproductor: `player.php?h=…` devuelve `var url = '…'` apuntando a `voe.sx`, `doodstream.com`, `streamwish.to`, `vidhidepro.com`.
- `loadLinks` replica el addon de Kodi:
  - `LinkCandidate(language, server, quality, url)` y etiquetas tipo `Voesx (Latino)`.
  - `qualityValue()`: `FULL_HD`→P1080, `HD`→P720, etc. (`Qualities` del jar: `Unknown, P144…P2160`).
  - `withLanguage()` / `withFallbackQuality()` para no perder enlaces sin metadatos.
  - `SKIP_TARGETS`: youtube, youtu.be, plustream, cinestart., okru.cx, ok.ru, vk.com, facebook, instagram, tiktok, t.me, telegram.
- Idiomas en la ficha: `LoadResponse` no tiene campo propio, así que se añaden como etiquetas en `tags` (después de los géneros) con `availableLanguages()`, que descarta las claves de `videos` sin ningún servidor utilizable (el sitio las lista vacías). Aplica a películas y a la ficha de episodio.
- Dood, StreamWish, VidHide y Vidara **no** se reimplementan: ya existen en el jar (`DoodstreamCom`, `StreamWishExtractor`, `VidHidePro`; `Vidara` ya hace POST a `/api/stream`).
- VoE sí tiene extractor propio porque el `Voe` del jar no resuelve la puerta altcha actual.

## VoeAltcha.kt

Subclases por espejo: `VoeAltcha` (`voe.sx`), `VoeAltchaCasey` (`caseyseemavoid.com`), `VoeAltchaMorencius` (`morencius.com`).

Flujo real observado:

1. `voe.sx/e/<code>` responde con `window.location.href = '<espejo>/e/<code>'`.
2. El espejo sirve un form con `_token`, `<altcha-widget challenge="https://<espejo>/<hash>">`.
3. Challenge JSON: `{parameters:{algorithm:"PBKDF2/SHA-256", nonce, salt, cost:10000, keyLength:32, keyPrefix:"00", expiresAt}, signature}`. `cost` real = 10000, prefijo `00`.
4. POST `_token`, `access=0`, `altcha=base64({challenge:<raw>,"solution":{counter,derivedKey,time}})`.
5. La respuesta trae `<script type="application/json">["<config cifrado>"]`; al descifrar da JSON con `source` (m3u8) y `direct_access_url` (mp4). Se emiten ambos.

Decisiones de implementación:

- PBKDF2 hecho a mano ≈ 29.8 ms/intento en JVM, así que el PoW se resuelve **en paralelo** (4 hilos, `ExecutorService`, presupuesto ~20 s). Medido en vivo: 0.9–3.5 s por challenge.
- Se comprobó paridad exacta con la implementación de referencia: mismo `counter`, mismo `derivedKey` (`5ec02b91…0522b`), mismo `source` tras descifrar.
- `AresHDPlugin.kt` registra `VoeAltcha()` y sus mirrors; se eliminaron los antiguos `VoeMirrorCasey`/`VoeMirrorMorencius` y el import de `Voe`.
- Subtítulos: el reproductor del espejo (`/js/loader*.js`) recorre `sources[0].captions` y exige `file` **y** `language` en cada entrada, añadiendo `kind` (`"captions"` por defecto) y `default`. Esquema confirmado descifrando el array ofuscado del loader (índice `0x1d5` → `language`, `0x1d9` → `kind`, `0x1b1` → `default`, `0x1ec` → `default_captions_language`). `VoeCaption` acepta `file`/`url` y etiqueta en `label`/`language`/`lang`; se ignoran `kind` distintos de `captions`/`subtitles` y se resuelven las rutas relativas contra el origen del espejo.

## Cómo se validó (sin dispositivo Android)

- **Node**: `fetch` + `pbkdf2Sync` contra el servidor real para obtener el challenge y comparar soluciones. En Git Bash, `mktemp -d` + `cygpath -w` para las rutas que se pasan a Node (`/tmp` no sirve).
- **Java**: harness con reflexión sobre `AresHD/build/tmp/kotlin-classes/debug`. Classpath necesario: `jetified-cloudstream.jar`, `kotlin-stdlib-2.4.21`, **`kotlin-reflect-1.8.21`** (sin él `MainAPIKt.<clinit>` falla y `parseJson` devuelve null), `jackson-*2.13*`, `jsoup`, `okhttp`, `okio`.
- Helpers disponibles del jar: `base64Decode`/`base64Encode`/`base64DecodeArray` (`MainAPIKt`), `AppUtils.parseJson`/`toJson`, `M3u8Helper`.

## Pendiente / próximos pasos

1. Probar en dispositivo: instalar el `.cs3` y resolver VoE/Dood/StreamWish de verdad.
2. ~~Subtítulos de VoE~~ **Hecho** en v4: `emitSubtitles()` lee `captions` del config. Muestreo amplio (258 configs de películas + 116 de episodios, más ~150 previos): `captions` siempre `[]`, así que AresHD no parece subir subtítulos a VoE y el camino con datos reales sigue sin verse. El config sí trae `default_captions_language` (p. ej. `"sq"`), pero es un resto de la plantilla del host. Muchos enlaces VoE de episodios ya están borrados (404 en `/e/<código>`), no es fallo del extractor.
3. ~~Placeholders y publicación~~ **Hecho**: `setRepo` y `repo.json` apuntan a `Netiscom/cloudstream-repo`, con ramas `main`/`builds` y workflow en verde.
4. ~~Commit~~ **Hecho**: el trabajo está commiteado y publicado en `main`.

## Limitaciones conocidas

- No hay runtime Android en esta máquina: la reproducción dentro de la app no está verificada.
- En la ficha de **serie** no se pueden mostrar idiomas: `thisSerie` no trae `videos` y los episodios solo dan título/número/fecha/slug, así que habría que pedir una página de episodio aparte.
- Un `keyPrefix` de 3–4 hex puede agotar el presupuesto de tiempo del solver; el presupuesto actual (20 s, 4 hilos) cubre el prefijo real de 2 hex.
