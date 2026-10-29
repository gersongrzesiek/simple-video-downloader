# Simple Video Downloader

Navegador Android sencillo que detecta los vídeos de la página que estás viendo y los descarga, con un bloqueador de anuncios integrado (compatible con las listas de EasyList, AdGuard y Adblock Plus). Sin anuncios, sin rastreadores, sin cuentas.

## Qué hace

- **Navegador con pestañas**: botón Inicio (favoritos y sitios más visitados), panel lateral con las pestañas abiertas y los favoritos, barra de búsqueda (DuckDuckGo), pantalla completa para vídeos y opción de abrir enlaces compartidos desde otras apps.
- **Detector de vídeos**: vigila las peticiones de red de la página y lista los archivos `.mp4`, `.webm`, `.mp3`… y las listas HLS (`.m3u8`). Si una URL no tiene extensión clara, pregunta al servidor su tipo (pidiendo solo 1 byte). El botón de descarga está gris hasta que encuentra un vídeo y entonces se pone rojo; al tocarlo muestra cada resolución con su tamaño en MB (estimado en HLS).
- **Descargas en segundo plano** con WorkManager y notificación de progreso:
  - Archivos directos.
  - HLS: elige calidad, baja los segmentos de 4 en 4, descifra AES-128 y los une en un `.ts` (o `.mp4` si el vídeo usa fMP4).
  - Se guardan en `Descargas/SimpleVideoDownloader` (MediaStore en Android 10+, sin permisos de almacenamiento).
- **Barra inferior**: *Tab* (la página actual), *Progreso* (descargas en curso, cancelar o reintentar) y *Terminado* (vídeos descargados con miniatura y peso; se reproducen dentro de la app, se pueden borrar o abrir la página de origen, y si el archivo ya no está en el teléfono se vuelve a descargar).
- **Bloqueador de anuncios**:
  - Bloquea peticiones de anuncios y rastreadores antes de que salgan del móvil.
  - Oculta los huecos que dejan los anuncios (reglas `##selector`), inyectando el CSS al principio de cada página.
  - Bloquea ventanas emergentes sin toque del usuario y redirecciones automáticas hacia publicidad.
  - Listas elegibles en Ajustes: EasyList, EasyPrivacy, EasyList Spanish, AdGuard Mobile Ads (activadas por defecto), AdGuard Base, Peter Lowe y Fanboy's Annoyance. Se actualizan solas cada 4 días con Wi-Fi.
  - Se puede desactivar por sitio desde el menú ("Bloquear anuncios en este sitio").
  - Incluye una lista mínima para funcionar aunque aún no se hayan descargado las listas completas.

## Límites conocidos

- **YouTube no está soportado a propósito**: sus condiciones prohíben descargar y Google Play rechaza las apps que lo hacen.
- **DASH (`.mpd`)** se detecta pero aún no se descarga (necesitaría unir audio y vídeo con FFmpeg o Media3).
- Contenido con **DRM** (Netflix, Disney+, SAMPLE-AES…) no se puede descargar.
- El bloqueador implementa la sintaxis básica de las listas (reglas de red con las opciones más comunes y ocultación CSS). Las reglas avanzadas de uBlock/AdGuard (scriptlets, `$redirect`, `$removeparam`, selectores procedimentales…) se ignoran, así que bloquea algo menos que AdGuard o uBlock Origin.
- Las descargas directas que se cortan vuelven a empezar desde cero.

## Cómo obtener el APK

Cada push compila el APK en GitHub Actions: abre la pestaña **Actions**, entra en la última ejecución de "Build APK" y descarga el artefacto `SimpleVideoDownloader-apk`. Contiene la versión `debug` y la `release` (firmada con la clave de depuración; cámbiala por la tuya antes de distribuir la app).

Para compilar en tu ordenador necesitas Android Studio (o el SDK de Android con la plataforma 35) y JDK 17:

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # pruebas del bloqueador, el detector y el lector HLS
```

## Estructura

| Carpeta | Contenido |
| --- | --- |
| `adblock/` | Lector de listas de filtros (`FilterParser`), motor de bloqueo (`AdBlockEngine`), descarga y actualización de listas (`AdBlocker`, `FilterUpdateWorker`) |
| `detect/` | Clasificación de URLs de medios (`MediaClassifier`) y detector por página (`MediaDetector`) |
| `download/` | Cola de descargas (`Downloads`), trabajador en segundo plano (`DownloadWorker`), lector HLS (`HlsParser`) y guardado (`MediaStoreSaver`) |
| `ui/` | Navegador (`MainActivity`, `BrowserClient`), descargas y ajustes |

Descarga solo contenido que tengas derecho a guardar.
