package com.simplevideodownloader.detect

/**
 * Script que se inyecta en cada página e iframe para encontrar vídeos sin esperar a que se reproduzcan:
 * lee las etiquetas `<video>`/`<source>`, los metadatos `og:video` y las URLs .m3u8/.mp4 escritas en los
 * scripts de la página. Avisa a la app por el puente `SVDMedia.found(url, frameUrl, infoJson)`.
 *
 * De cada `<video>` manda también su tamaño en pantalla, si va en bucle, sin sonido, con controles y su
 * duración, para que la app descarte los que son gifs animados.
 */
object PageScanner {
    const val BRIDGE = "SVDMedia"

    val SCRIPT = """
        (function(){
          try {
            if (window.__svdScan || !window.SVDMedia || !/^https?:/.test(location.protocol)) return;
            window.__svdScan = true;
            var seen = {};
            var MEDIA_HINT = /m3u8|mp4|webm|mpd/i;
            var TEXT_URL = /https?:(?:\\?\/){2}[^\s"'<>()]+?\.(?:m3u8|mp4|webm|mpd)(?:\?[^\s"'<>()]*)?(?=["'\s<>()]|\\)/gi;
            function report(u, info) {
              if (!u || typeof u !== 'string') return;
              u = u.replace(/\\\//g, '/').replace(/\\u0026/gi, '&').replace(/\\u002f/gi, '/').replace(/&amp;/g, '&').trim();
              if (/^(blob|data|javascript|about):/i.test(u)) return;
              try { u = new URL(u, location.href).href; } catch (e) { return; }
              if (!/^https?:/i.test(u)) return;
              var json = JSON.stringify(info || {});
              if (seen[u] === json) return;
              seen[u] = json;
              try { SVDMedia.found(u, location.href, json); } catch (e) {}
            }
            function infoOf(v) {
              var r = v.getBoundingClientRect();
              return {
                el: 1, w: Math.round(r.width), h: Math.round(r.height),
                loop: !!v.loop, muted: !!(v.muted || v.defaultMuted || v.hasAttribute('muted')),
                controls: !!v.controls, dur: isFinite(v.duration) ? v.duration : -1
              };
            }
            function scanMedia() {
              var list = document.querySelectorAll('video, audio');
              for (var i = 0; i < list.length; i++) {
                var v = list[i], info = infoOf(v);
                report(v.currentSrc || v.getAttribute('src') || v.getAttribute('data-src'), info);
                var sources = v.querySelectorAll('source');
                for (var j = 0; j < sources.length; j++) report(sources[j].getAttribute('src') || sources[j].getAttribute('data-src'), info);
              }
            }
            function scanMeta() {
              var list = document.querySelectorAll('meta[property^="og:video"], meta[name^="twitter:player:stream"], meta[itemprop="contentUrl"], link[itemprop="contentUrl"]');
              for (var i = 0; i < list.length; i++) {
                var c = list[i].getAttribute('content') || list[i].getAttribute('href');
                if (c && /^(https?:)?\/\//i.test(c) && !/\.(swf|html?|php)(\?|#|$)/i.test(c)) report(c, { meta: 1 });
              }
            }
            function scanText(t) {
              if (!t || typeof t !== 'string' || t.length > 3000000 || !MEDIA_HINT.test(t)) return;
              // Las listas HLS las lee el detector de red; aquí solo interesan textos con enlaces (JSON, scripts).
              if (t.lastIndexOf('#EXTM3U', 0) === 0) return;
              var found = t.replace(/\\u002f/gi, '/').match(TEXT_URL);
              if (found) for (var k = 0; k < found.length && k < 60; k++) report(found[k], { text: 1 });
            }
            function scanScripts() {
              var list = document.querySelectorAll('script:not([src])');
              for (var i = 0; i < list.length; i++) scanText(list[i].textContent);
            }
            // Muchos reproductores piden la lista de calidades (JSON) al cargar: leemos esas respuestas.
            try {
              var send = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.send = function () {
                var x = this;
                try {
                  x.addEventListener('load', function () {
                    try {
                      if (x.responseType === '' || x.responseType === 'text') scanText(x.responseText);
                      else if (x.responseType === 'json' && x.response) scanText(JSON.stringify(x.response));
                    } catch (e) {}
                  });
                } catch (e) {}
                return send.apply(this, arguments);
              };
            } catch (e) {}
            if (window.fetch) {
              var origFetch = window.fetch;
              window.fetch = function () {
                var p = origFetch.apply(this, arguments);
                try {
                  p.then(function (r) {
                    try {
                      var ct = (r.headers.get('content-type') || '').toLowerCase();
                      if (/json|javascript|text\/plain|text\/html/.test(ct)) r.clone().text().then(scanText, function () {});
                    } catch (e) {}
                  }, function () {});
                } catch (e) {}
                return p;
              };
            }
            var timer = null, runs = 0;
            function scan() {
              timer = null;
              if (runs++ > 60) return;
              try { scanMedia(); scanMeta(); scanScripts(); } catch (e) {}
            }
            function schedule() { if (!timer) timer = setTimeout(scan, 700); }
            document.addEventListener('DOMContentLoaded', schedule);
            window.addEventListener('load', schedule);
            // Cuando un vídeo carga sus datos sabemos su duración real: lo volvemos a mirar.
            document.addEventListener('loadedmetadata', function (e) {
              var t = e.target;
              if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO')) report(t.currentSrc || t.src, infoOf(t));
            }, true);
            function observe() {
              try {
                new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
              } catch (e) {}
            }
            if (document.documentElement) observe(); else document.addEventListener('DOMContentLoaded', observe);
            schedule();
          } catch (e) {}
        })();
    """.trimIndent()
}
