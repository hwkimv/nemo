package com.nemo.backend.domain.auth.controller;

import com.nemo.backend.domain.auth.service.TurnstileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class TurnstileController {

    private final TurnstileService turnstileService;

    @GetMapping(value = "/turnstile", produces = MediaType.TEXT_HTML_VALUE)
    public String turnstilePage() {
        String siteKey = turnstileService.getSiteKey();

        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
              <style>
                body {
                  margin: 0;
                  padding: 0;
                  display: flex;
                  justify-content: center;
                  align-items: center;
                  min-height: 100vh;
                  background: transparent;
                }
                #turnstile-widget {
                  display: flex;
                  justify-content: center;
                  align-items: center;
                }
              </style>
            </head>
            <body>
              <div id="turnstile-widget"></div>
              <script>
               if (window.TurnstileChannel) {
                  TurnstileChannel.postMessage('HOST:' + window.location.hostname);
               }
                function initTurnstile() {
                  if (window.turnstile) {
                    window.turnstile.render('#turnstile-widget', {
                      sitekey: '%s',
                      callback: function(token) {
                        TurnstileChannel.postMessage('SUCCESS:' + token);
                      },
                      'error-callback': function() {
                        TurnstileChannel.postMessage('ERROR:Turnstile verification failed');
                      },
                      'expired-callback': function() {
                        TurnstileChannel.postMessage('ERROR:Turnstile token expired');
                      }
                    });
                  } else {
                    setTimeout(initTurnstile, 100);
                  }
                }

                if (document.readyState === 'loading') {
                  document.addEventListener('DOMContentLoaded', initTurnstile);
                } else {
                  initTurnstile();
                }
              </script>
            </body>
            </html>
            """.formatted(siteKey); // 👈 여기서 siteKey 넣기
    }
}
