# Cloudflare, AWS WAF, and anti-bot challenges

A site that answers with a challenge is handled the way the app handles it, in two places: requests
made through OkHttp, and WebViews a source opens itself.

## Requests

`CloudflareInterceptor` detects the challenge, opens the page in Chromium, lets the challenge settle,
copies the cookies the browser earned into the OkHttp cookie jar, and replays the request once.
`cf_clearance` is bound to the user agent that earned it, so the interceptor sends that user agent on
every later request to the host too; a replay under any other identity is refused. If the challenge
comes back, the run reports `BLOCKED`; the solver limits itself to one attempt per host per minute, so
an extension this browser cannot clear fails fast instead of paying for a solve on every request.

## WebViews

A source that opens a challenged URL itself gets the same treatment, but only after the loaded page
has been confirmed to be an interstitial, so ordinary pages are never clicked.

## What counts as a barrier

A response only counts as a barrier when Cloudflare says so: a `cf-mitigated: challenge` header, an
`x-amzn-waf-action` header for AWS WAF, or a page Cloudflare itself served, recognised by the
challenge bootstrap (`_cf_chl_opt`, `/cdn-cgi/challenge-platform/`) or by its error page
(`cf-error-details`). `cf-ray` alone is not evidence: it is on every response from a Cloudflare-fronted
site, including the site's own 403s. A site that answers 403 for its own reasons — a chapter that
needs an unlock, an age gate — keeps that answer, so the source reports what it meant instead of the
run inventing a challenge. A firewall block is still `BLOCKED`, but it is not solved: no browser can
turn a block into a puzzle.

## Clicking the widget

Two properties of the live widget shape the clicker:

- The checkbox is an iframe inside a **closed** shadow root, so page JavaScript cannot see it:
  `document.querySelectorAll('iframe')` returns nothing while the checkbox is plainly on screen.
  It is located through the DevTools DOM domain, which pierces shadow roots.
- The widget is only clickable after Turnstile has booted, which is well after `readyState` is
  `complete` and even after the geometry is correct. A click before that is silently discarded and
  the interstitial stays up forever. Turnstile announces that moment with a `postMessage` event
  named `interactiveBegin`, so the click is gated on that event rather than on a timer.

The click is a real mouse event dispatched through the DevTools input domain. Each widget is
clicked at most once: Cloudflare issues a fresh widget id when it replaces a rejected widget, and
the caller remembers the ids it has clicked. The click is gated on the widget announcing that it
accepts input, so it is only ever spent on a widget that is actually waiting; pressing one a zone
would have passed on its own is itself a signal it rejects, measured as Turnstile `fail 600010`. A
challenge that renders no widget is left alone and reported as `BLOCKED`. Nothing is forged — if the
browser does not earn `cf_clearance`, the run says so.

## Why the browser keeps its own identity

The browser presents its own identity rather than being told to look like the app. A zone compares
the user agent with the rest of the browser, so an override contradicts it; and the clearance a
mismatched browser earns is bound to an identity the replay cannot reproduce. Measured against
`01.komikzoid.id`, one script, same machine and IP:

| browser | user agent | result |
| --- | --- | --- |
| headless | native | stuck at the checkbox |
| headless | app | stuck at the checkbox |
| visible | app | stuck at the checkbox; clicking it ends in Turnstile `fail 600010` |
| visible | native | cleared in about two seconds, no click |

So a display is not optional for challenged zones: `ci/run-shard.sh` runs the checker under
`xvfb-run` when it is installed, and the browser falls back to headless only when no display can be
created. Clearance earned by a visible browser is accepted by the replay when the replay presents
the same user agent, which is what the interceptor does for every request to the host.

## Turning the solver off

Set `KEI_STATUS_NO_CF_SOLVE=1` to skip the attempt entirely and report the challenge immediately.
