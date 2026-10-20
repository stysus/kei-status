package kei.status.engine

/**
 * Renders run reports as one static page.
 *
 * The page is plain HTML with no external assets, so it can be published as-is to GitHub Pages and
 * still works offline. Everything that reaches it - source names, base URLs, exception messages,
 * sample URLs - comes from third-party extension code and is escaped: a source that scrapes a page
 * title containing markup must not be able to break the report.
 */
object ReportHtml {

    private const val EXTENSIONS_REPO = "https://github.com/keiyoushi/extensions-source"

    private val STATUS_ORDER = listOf(
        Status.FAIL,
        Status.BLOCKED,
        Status.UNSUPPORTED,
        Status.WARN,
        Status.PASS,
        Status.SKIP,
    )

    fun escape(value: String): String = buildString(value.length + 16) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

    /** Renders every report into a single page, worst modules first. */
    /**
     * [checkedAt] is when this page's run happened, not when a module's state was first seen.
     * A nightly job refreshes the page every night while most modules keep the state they had for
     * weeks, so a single "last checked" line describes the page honestly and no per-module
     * "since" label is needed.
     */
    fun site(
        reports: List<RunReport>,
        title: String = "Keiyoushi extension status",
        checkedAt: String = java.time.Instant.now().toString(),
    ): String {
        val ordered = reports.sortedWith(
            compareByDescending<RunReport> { it.status.severity }.thenBy { it.module },
        )
        val sources = ordered.flatMap { it.sources }
        val totals = STATUS_ORDER.associateWith { status -> sources.count { it.status == status } }

        // A handful of extension names exist in more than one language bucket (`:src:en:dynasty` and
        // `:src:zh:dynasty`). Only those headers need the bucket to be told apart, so only those get
        // the chip: a chip on every other header would be decoration that means nothing.
        val ambiguous = ordered.groupingBy { it.displayName }.eachCount()
            .filterValues { it > 1 }
            .keys

        return buildString(64 * 1024) {
            appendLine("<!doctype html>")
            appendLine("<html lang=\"en\">")
            appendLine("<head>")
            appendLine("<meta charset=\"utf-8\">")
            appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            appendLine("<title>${escape(title)}</title>")
            appendLine("<style>")
            appendLine(CSS)
            appendLine("</style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("<header>")
            appendLine("<h1>${escape(title)}</h1>")
            appendLine(
                "<p class=\"meta\">${ordered.size} module(s) &middot; ${sources.size} source(s)" +
                    " &middot; ${upstream(ordered)} &middot; last checked ${escape(humanTime(checkedAt))}</p>",
            )
            // Each tally is also the filter for its status: the number says how many are in that
            // state, and clicking it is the shortest way to see exactly those. A status with nothing
            // in it is left inert rather than offering a click that would empty the page.
            appendLine("<div class=\"totals\">")
            STATUS_ORDER.forEach { status ->
                val n = totals[status] ?: 0
                val off = if (n == 0) " disabled" else ""
                appendLine(
                    "<button type=\"button\" class=\"total\" data-filter=\"${status.css()}\"" +
                        " aria-pressed=\"false\"$off title=\"Show only ${status} sources\">" +
                        "<span class=\"pill ${status.css()}\">${status}</span>" +
                        "<b>$n</b></button>",
                )
            }
            appendLine("</div>")
            appendLine("</header>")
            appendLine("<main>")
            appendLine(
                "<div class=\"controls\">" +
                    "<input id=\"q\" type=\"search\" placeholder=\"Filter by source, module, url or message\">" +
                    "<button id=\"expand\" type=\"button\">Expand all</button>" +
                    "<button id=\"collapse\" type=\"button\">Collapse all</button>" +
                    "<span id=\"count\" class=\"hint\"></span>" +
                    "</div>",
            )

            if (ordered.isEmpty()) {
                appendLine("<p class=\"empty\">No reports were produced.</p>")
            }

            ordered.forEach { appendModule(it, ambiguous) }

            appendLine("</main>")
            appendLine("<script>")
            appendLine(SCRIPT)
            appendLine("</script>")
            appendLine("</body>")
            appendLine("</html>")
        }
    }

    private fun StringBuilder.appendModule(report: RunReport, ambiguous: Set<String>) {
        val worst = report.status
        // A module with one source has nothing to keep apart: its source *is* the module, so the
        // header carries the source's own language and URL and the scenarios follow it directly.
        // A second box would nest one row inside another for no gain.
        val only = report.sources.singleOrNull()

        appendLine(
            "<section class=\"module\" data-status=\"${worst.css()}\" " +
                "data-module=\"${escape(report.module)}\">",
        )

        // The module header is the toggle for everything inside it: with a thousand modules on the
        // page, collapsing one is how a reader gets past it. Native <details> means it still works
        // with scripting off. Everything starts closed so the first screen is the whole list of
        // modules rather than one module's worth of scenarios.
        val kind = if (only != null) " single" else ""
        appendLine("<details class=\"module-details$kind\">")
        appendLine("<summary class=\"module-head\">")
        appendLine("<span class=\"pill ${worst.css()}\">${worst}</span>")
        appendLine("<span class=\"module-name\">${escape(report.displayName)}</span>")
        when {
            // The header is the source row, so it says what every source row says.
            only != null -> only.lang.takeIf { it.isNotBlank() }?.let {
                appendLine("<span class=\"lang\">${escape(it)}</span>")
            }
            // Otherwise the bucket is worth a chip only when the name alone is not enough to tell
            // two headers apart.
            report.lang.isNotBlank() && report.displayName in ambiguous ->
                appendLine("<span class=\"lang\">${escape(report.lang)}</span>")
        }
        if (only != null) {
            if (only.baseUrl.isNotBlank()) {
                appendLine("<span class=\"url\">${escape(only.baseUrl)}</span>")
            }
        } else {
            appendLine("<span class=\"count\">${countOf(report.sources.size, "source")}</span>")
        }
        appendLine("</summary>")

        if (report.changes.isNotEmpty()) {
            appendLine("<ul class=\"changes\">")
            report.changes.forEach { change ->
                appendLine("<li>${transition(change)}</li>")
            }
            appendLine("</ul>")
        }

        if (report.sources.isEmpty()) {
            appendLine("<p class=\"empty\">No sources loaded.</p>")
        } else if (only != null) {
            appendLine("<div class=\"source-body\" data-status=\"${only.status.css()}\" data-source>")
            appendScenarios(only)
            appendLine("</div>")
        } else {
            report.sources.forEach { appendSource(it) }
        }

        appendLine("</details>")
        appendLine("</section>")
    }

    private fun StringBuilder.appendSource(source: SourceReport) {
        // Every source starts closed like the module around it: the page is a list first, and a
        // reader opens the one thing they came to look at.
        appendLine("<details class=\"source\" data-status=\"${source.status.css()}\" data-source>")
        appendLine("<summary>")
        appendLine("<span class=\"pill ${source.status.css()}\">${source.status}</span>")
        appendLine("<span class=\"name\">${escape(source.name)}</span>")
        if (source.lang.isNotBlank()) {
            appendLine("<span class=\"lang\">${escape(source.lang)}</span>")
        }
        if (source.baseUrl.isNotBlank()) {
            appendLine("<span class=\"url\">${escape(source.baseUrl)}</span>")
        }
        appendLine("</summary>")
        appendScenarios(source)
        appendLine("</details>")
    }

    private fun StringBuilder.appendScenarios(source: SourceReport) {
        appendLine("<table>")
        appendLine("<thead><tr><th>Status</th><th>Scenario</th><th>Time</th><th>Detail</th></tr></thead>")
        appendLine("<tbody>")
        source.scenarios.forEach { scenario ->
            appendLine("<tr>")
            appendLine("<td><span class=\"pill ${scenario.status.css()}\">${scenario.status}</span></td>")
            appendLine("<td class=\"scenario\">${escape(scenario.name)}</td>")
            appendLine("<td class=\"time\">${scenario.durationMs}ms</td>")
            appendLine("<td class=\"detail\">${detail(scenario)}</td>")
            appendLine("</tr>")
        }
        appendLine("</tbody>")
        appendLine("</table>")
    }

    private fun countOf(count: Int, noun: String): String =
        if (count == 1) "1 $noun" else "$count ${noun}s"

    /**
     * The upstream revision the reports were built from, shown once for the page.
     *
     * It used to sit in every module header, which repeated the same seven characters a thousand
     * times and read like a per-extension fact when it is a property of the run. The commit is the
     * `extensions-source` checkout the extensions were compiled from, so it links there.
     */
    private fun upstream(reports: List<RunReport>): String {
        val commits = reports.mapNotNull { it.extensionCommit }.distinct()
        return when (commits.size) {
            0 -> "extensions from an unknown revision"
            1 -> "extensions at <a href=\"$EXTENSIONS_REPO/commit/${escape(commits.single())}\"" +
                " title=\"the extensions-source revision these reports were built from\">" +
                "${escape(commits.single())}</a>"
            // Shards can land on different upstream revisions when a sweep straddles a commit.
            else -> "extensions from ${commits.size} revisions"
        }
    }

    private fun detail(scenario: ScenarioResult): String {
        val parts = buildList {
            scenario.message?.takeIf { it.isNotBlank() }?.let {
                // A skip explains itself ("supportsLatest = false"); only real trouble reads red.
                val tone = if (scenario.status == Status.SKIP || scenario.status == Status.PASS) "note" else "message"
                add("<span class=\"$tone\">${escape(it)}</span>")
            }
            if (scenario.notes.isNotEmpty()) {
                add(scenario.notes.joinToString("<br>") { escape(it) })
            }
            if (scenario.samples.isNotEmpty()) {
                add("<span class=\"samples\">${scenario.samples.joinToString("<br>") { escape(it) }}</span>")
            }
        }
        return if (parts.isEmpty()) "<span class=\"ok\">ok</span>" else parts.joinToString("<br>")
    }

    private fun Status.css(): String = name.lowercase()

    private fun humanTime(iso: String): String = runCatching {
        java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.parse(iso))
    }.getOrDefault(iso)

    private fun transition(change: StatusChange): String {
        val what = if (change.scenario == null) "source" else change.scenario
        val from = change.from
            ?.let { "<span class=\"pill ${it.css()}\">$it</span>" }
            ?: "<span class=\"pill skip\">new</span>"
        val to = change.to
            ?.let { "<span class=\"pill ${it.css()}\">$it</span>" }
            ?: "<span class=\"pill skip\">gone</span>"
        val lang = if (change.lang.isBlank()) "" else " <span class=\"lang\">${escape(change.lang)}</span>"
        return "<span class=\"name\">${escape(change.source)}</span>$lang" +
            " <span class=\"scenario\">${escape(what)}</span> $from &rarr; $to"
    }

    private val CSS = """
        :root {
          color-scheme: light dark;
          --bg: #ffffff; --fg: #16181d; --muted: #6b7280; --line: #e5e7eb;
          --card: #fafafa; --accent: #2563eb;
          --pass: #15803d; --pass-bg: #dcfce7;
          --warn: #a16207; --warn-bg: #fef9c3;
          --fail: #b91c1c; --fail-bg: #fee2e2;
          --blocked: #7c2d12; --blocked-bg: #ffedd5;
          --unsupported: #6b21a8; --unsupported-bg: #f3e8ff;
          --skip: #4b5563; --skip-bg: #f3f4f6;
        }
        @media (prefers-color-scheme: dark) {
          :root {
            --bg: #0d1117; --fg: #e6edf3; --muted: #8b949e; --line: #30363d;
            --card: #161b22; --accent: #58a6ff;
            --pass: #3fb950; --pass-bg: #12351f;
            --warn: #d29922; --warn-bg: #3a2d0c;
            --fail: #f85149; --fail-bg: #3d1418;
            --blocked: #db6d28; --blocked-bg: #3a2110;
            --unsupported: #bc8cff; --unsupported-bg: #2a1c3d;
            --skip: #8b949e; --skip-bg: #21262d;
          }
        }
        * { box-sizing: border-box; }
        body {
          margin: 0; padding: 0 1.25rem 4rem;
          background: var(--bg); color: var(--fg);
          font: 15px/1.5 ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
        }
        header { max-width: 1100px; margin: 0 auto; padding: 2rem 0 1rem; }
        h1 { font-size: 1.6rem; margin: 0 0 .35rem; letter-spacing: -.02em; }
        .meta { color: var(--muted); margin: 0 0 1rem; font-size: .875rem; }
        .meta a { color: inherit; text-decoration-color: var(--line); text-underline-offset: 2px; }
        .meta a:hover { color: var(--accent); text-decoration-color: currentColor; }
        .totals { display: flex; flex-wrap: wrap; gap: .5rem; }
        /* The tally doubles as the filter for its status, so it is a button that has to still read
           as a chip: the pill keeps its own colour and only the frame reacts. */
        .total {
          display: inline-flex; align-items: center; gap: .4rem;
          border: 1px solid var(--line); border-radius: 8px;
          padding: .3rem .6rem; background: var(--card); font-size: .8rem;
          font-family: inherit; color: inherit; cursor: pointer;
        }
        .total:hover { border-color: var(--accent); }
        .total:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
        .total[aria-pressed="true"] {
          border-color: var(--accent);
          box-shadow: inset 0 0 0 1px var(--accent);
        }
        /* Nothing in that state: the chip still reports the zero, it just does not offer a click
           that would empty the page. */
        .total:disabled { cursor: default; opacity: .55; }
        .total:disabled:hover { border-color: var(--line); }
        main { max-width: 1100px; margin: 0 auto; }
        .controls { display: flex; gap: .75rem; align-items: center; margin: 1.25rem 0; }
        input[type=search] {
          flex: 1; min-width: 0; padding: .55rem .7rem;
          border: 1px solid var(--line); border-radius: 8px;
          background: var(--card); color: var(--fg); font-size: .9rem;
        }
        input[type=search]:focus { outline: 2px solid var(--accent); outline-offset: -1px; }
        button {
          padding: .5rem .75rem; border: 1px solid var(--line); border-radius: 8px;
          background: var(--card); color: var(--fg); font-size: .8rem; cursor: pointer;
          white-space: nowrap;
        }
        button:hover { border-color: var(--accent); color: var(--accent); }
        .hint { color: var(--muted); font-size: .8rem; white-space: nowrap; }
        /* A collapsed module is a single row in a list, so the gap between modules belongs to the
           row's own padding and not to a margin: 2rem of margin per module turned a 1,400-row list
           into a very long scroll of mostly empty space. */
        .module { margin: 0; }
        .module-head {
          display: flex; align-items: center; gap: .6rem; flex-wrap: wrap;
          font-size: 1rem; margin: 0; padding: .5rem 0;
          border-bottom: 1px solid var(--line);
          cursor: pointer; list-style: none;
        }
        /* Opened, the header becomes a heading for its sources and needs air under it. The gap
           before the next module is 8px, which with the next header's own padding lands the next
           name exactly 16px below the card — the same rhythm as two collapsed headers. */
        details.module-details[open] > .module-head { margin-bottom: .55rem; }
        details.module-details[open] { padding-bottom: .5rem; }
        .module-head::-webkit-details-marker { display: none; }
        /* The marker is drawn here so it can rotate: the native triangle is not styleable. */
        .module-head::before {
          content: ""; flex: none;
          width: 0; height: 0;
          border-left: 6px solid var(--muted);
          border-top: 5px solid transparent;
          border-bottom: 5px solid transparent;
          transform-origin: 25% 50%;
          transition: transform .12s ease;
        }
        details[open] > .module-head::before { transform: rotate(90deg); }
        .module-head:hover .module-name { color: var(--accent); }
        .module-head:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
        .module-name { font-weight: 700; }
        .count { margin-left: auto; color: var(--muted); font-size: .75rem; font-weight: 400; }
        .changes {
          list-style: none; margin: 0 0 .75rem; padding: .5rem .8rem;
          border: 1px solid var(--line); border-left: 3px solid var(--accent);
          border-radius: 8px; background: var(--card); font-size: .82rem;
        }
        .changes li { display: flex; align-items: center; gap: .4rem; flex-wrap: wrap; }
        .changes li + li { margin-top: .3rem; }
        .pill {
          display: inline-block; min-width: 4.6rem; text-align: center;
          padding: .12rem .45rem; border-radius: 999px;
          font-size: .7rem; font-weight: 700; letter-spacing: .02em;
        }
        .pill.pass { color: var(--pass); background: var(--pass-bg); }
        .pill.warn { color: var(--warn); background: var(--warn-bg); }
        .pill.fail { color: var(--fail); background: var(--fail-bg); }
        .pill.blocked { color: var(--blocked); background: var(--blocked-bg); }
        .pill.unsupported { color: var(--unsupported); background: var(--unsupported-bg); }
        .pill.skip { color: var(--skip); background: var(--skip-bg); }
        /* The card a source's scenarios live in. A multi-source module gets one per source; a
           single-source module gets one too, so the table looks the same either way. */
        details.source, .source-body {
          border: 1px solid var(--line); border-radius: 10px;
          background: var(--card); margin: 0 0 .5rem; overflow: hidden;
        }
        /* The gap belongs between cards, not after the last one: the module already provides the
           space before the next module. */
        details.source:last-child, .source-body:last-child { margin-bottom: 0; }
        details.source > summary {
          display: flex; align-items: center; gap: .6rem; flex-wrap: wrap;
          padding: .6rem .8rem; cursor: pointer; list-style: none;
        }
        details.source > summary::-webkit-details-marker { display: none; }
        details.source > summary:hover { background: rgba(127,127,127,.06); }
        .name { font-weight: 600; }
        .lang { color: var(--muted); font-size: .75rem; text-transform: uppercase; }
        .url {
          color: var(--muted); font-size: .78rem; margin-left: auto;
          font-family: ui-monospace, monospace; overflow-wrap: anywhere;
        }
        table { width: 100%; border-collapse: collapse; font-size: .85rem; }
        thead th {
          text-align: left; padding: .4rem .8rem; font-size: .7rem;
          text-transform: uppercase; letter-spacing: .04em;
          color: var(--muted); border-top: 1px solid var(--line);
          border-bottom: 1px solid var(--line); font-weight: 600;
        }
        /* When the table is the first thing in the card, its top border would double the card's. */
        .source-body > table:first-child > thead > tr > th { border-top: none; }
        tbody td { padding: .45rem .8rem; border-bottom: 1px solid var(--line); vertical-align: top; }
        tbody tr:last-child td { border-bottom: none; }
        .scenario { font-family: ui-monospace, monospace; white-space: nowrap; }
        .time { color: var(--muted); white-space: nowrap; font-variant-numeric: tabular-nums; }
        .detail { overflow-wrap: anywhere; }
        .message { color: var(--fail); }
        .note { color: var(--muted); }
        .samples { color: var(--muted); font-size: .78rem; }
        .ok { color: var(--muted); }
        .empty { color: var(--muted); font-style: italic; }
        .hidden { display: none !important; }
        footer {
          max-width: 1100px; margin: 3rem auto 0; padding-top: 1rem;
          border-top: 1px solid var(--line); color: var(--muted); font-size: .78rem;
        }
    """.trimIndent()

    /**
     * Filtering is a text match over the source block plus a status match, so a page with a thousand
     * sources stays usable without a framework or a build step.
     *
     * Every source is a `[data-source]` unit, whether it is its own collapsible box or the body of a
     * single-source module. A source also matches its module's full path, which is kept as an
     * attribute rather than displayed: `:src:en:` still finds a module whose header reads `Aurora`.
     *
     * The status tallies in the header are filters too. Clicking one narrows the page to that
     * status, clicking it again clears it, and the text box keeps working alongside. Filtering only
     * decides which modules are listed; it never opens anything, so a status view starts collapsed
     * exactly like the page itself.
     */
    private val SCRIPT = """
        (function () {
          var input = document.getElementById('q');
          var count = document.getElementById('count');
          var units = Array.prototype.slice.call(document.querySelectorAll('[data-source]'));
          var sections = Array.prototype.slice.call(document.querySelectorAll('section.module'));
          var moduleDetails = Array.prototype.slice.call(document.querySelectorAll('details.module-details'));
          var totals = Array.prototype.slice.call(document.querySelectorAll('.total'));
          var status = null;

          function haystack(unit) {
            var section = unit.closest('section.module');
            var details = unit.closest('details.module-details');
            var text = unit.textContent;
            if (section) text += ' ' + (section.getAttribute('data-module') || '');
            // A single-source module shows the source in its header, which sits outside the unit.
            if (details && details.classList.contains('single')) {
              var head = details.querySelector('.module-head');
              if (head) text += ' ' + head.textContent;
            }
            return text.toLowerCase();
          }

          function apply() {
            var needle = (input.value || '').trim().toLowerCase();
            var shown = 0;
            units.forEach(function (el) {
              var hit = (!needle || haystack(el).indexOf(needle) !== -1) &&
                (!status || el.getAttribute('data-status') === status);
              el.classList.toggle('hidden', !hit);
              if (hit) shown++;
            });
            sections.forEach(function (el) {
              var visible = el.querySelectorAll('[data-source]:not(.hidden)').length;
              el.classList.toggle('hidden', visible === 0);
            });
            count.textContent = shown + ' / ' + units.length + ' sources';
          }

          totals.forEach(function (button) {
            button.addEventListener('click', function () {
              var wanted = button.getAttribute('data-filter');
              status = status === wanted ? null : wanted;
              totals.forEach(function (other) {
                var on = other.getAttribute('data-filter') === status;
                other.setAttribute('aria-pressed', on ? 'true' : 'false');
              });
              apply();
            });
          });

          input.addEventListener('input', apply);

          // Opening stays the reader's decision, filtered or not: a status view is a list of the
          // modules in that state, the same shape as the unfiltered page.
          document.getElementById('expand').addEventListener('click', function () {
            sections.forEach(function (section) {
              if (section.classList.contains('hidden')) return;
              var body = section.querySelector('details.module-details');
              if (body) body.open = true;
            });
            units.forEach(function (el) {
              if (el.tagName === 'DETAILS' && !el.classList.contains('hidden')) el.open = true;
            });
          });
          document.getElementById('collapse').addEventListener('click', function () {
            units.forEach(function (el) { if (el.tagName === 'DETAILS') el.open = false; });
            moduleDetails.forEach(function (el) { el.open = false; });
          });

          apply();
        })();
    """.trimIndent()
}
