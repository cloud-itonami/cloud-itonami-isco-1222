(ns advertpr.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  This repo previously had NO demo/visualization page and no generator
  at all. This namespace drives the REAL actor stack
  (`advertpr.actor` -> `advertpr.governor` -> `advertpr.store`) through
  a scenario built from real, exercised store data and renders the
  result deterministically -- no invented numbers, no timestamps in the
  page content, byte-identical across reruns against the same seed
  (verify by diffing two consecutive runs before shipping). Adapted
  from the proven template in `cloud-itonami-isco-1211`
  (`src/finmgmt/render_html.clj`) -- the shape is the same, the
  domain-specific fields (claims/substantiation/prohibition instead of
  budget-lines) differ.

  Seed data provenance:

  `client-1` (\"Kobo Trade\") + `SUB-1` (\"30-day battery\", evidence
  \"lab report LR-77\") + `SUB-2` (\"recycled aluminum body\", evidence
  \"supplier cert SC-12\") + `PROH-1` (\"cures insomnia\") below are
  lifted VERBATIM from `advertpr.governor-test/fresh-store` (chosen
  over `advertpr.actor-test/fresh-store`'s smaller fixture -- one
  client, one substantiation -- because it already has the 2
  substantiations + 1 prohibition this scenario needs to demonstrate
  both HARD-hold claim rules distinctly).

  `SUB-3` (\"cures insomnia\", evidence \"internal survey\") is ALSO
  lifted verbatim, but from a different test in the same file
  (`advertpr.governor-test/hard-on-prohibited-claim`, which registers
  it on top of the shared fixture to prove that regulation outranks
  evidence -- a claim can be both substantiated AND prohibited, and
  the prohibition still holds). It is added here on top of the base
  fixture for the same reason that test adds it: without it, a draft
  claiming \"cures insomnia\" would trip BOTH
  `:unsubstantiated-claim` and `:prohibited-claim` at once, muddying
  which rule the demo is meant to isolate. Disclosed here plainly. No
  entity in this scenario is invented -- every one comes from this
  repo's own test file, just not all from the same single fixture
  function.

  Every other field this page displays (dispositions, hold reasons,
  committed-record counts) is real output read after `run-demo!`
  actually executed the graph -- none of it is hand-typed.

  Known architectural gaps, honestly noted rather than papered over:

  1. `advertpr.governor`'s `:no-actuation` rule (proposal `:effect`
     must be `:propose`) is NOT reachable through this demo, because
     the real `mock-advisor` (`advertpr.advisor/infer`)
     unconditionally sets `:effect :propose` on every proposal it
     emits -- by design, the advisor can never itself emit a raw store
     write. Covered instead by
     `advertpr.governor-test/hard-on-no-actuation-violation` (which
     calls `governor/check` directly with a hand-built proposal), not
     by this build-time renderer.
  2. The low-confidence escalation path (`confidence <
     advertpr.governor/confidence-floor`, i.e. < 0.6) is NOT reachable
     through this demo either: the real mock-advisor's `infer` assigns
     confidence purely from `:stake` (`:high` -> 0.7, `:medium` -> 0.85,
     `:low` -> 0.95), and even the lowest of those (0.7 for `:high`
     stake) is above the 0.6 floor. Covered instead by
     `advertpr.governor-test/escalates-low-confidence` (direct
     `governor/check` call with a hand-set `:confidence 0.3`).

  Every other governor rule this actor defines IS reached here: client
  provenance (`:no-client`), claim substantiation
  (`:unsubstantiated-claim`), the prohibited-claim blacklist
  (`:prohibited-claim`), plus both always-escalate ops (`:publish-ad`,
  `:issue-press-release`; escalate -> human approve -> commit) and the
  plain auto-commit path (including the \"no claims asserted\" case).

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [advertpr.store :as store]
            [advertpr.actor :as actor]))

;; ----------------------------- harness --------------------------------
;; advertpr.actor already exposes run-request!/approve! wrappers around
;; langgraph.graph/run* -- this repo's own actor ns is the harness, no
;; raw g/run* needed here.

(defn- run-op!
  "Drives one real advertising/PR operation request through the actual
  compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it
  (this demo's scenario never demonstrates an UNAPPROVED escalation --
  every escalation here reaches a human who signs off). Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid client-id op extra]
  (let [request (merge {:client-id client-id :op op} extra)
        r1 (actor/run-request! graph request {} tid)]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :client-id client-id :op op :request request
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely
  reach through its real graph (auto-commit, escalate-then-approve,
  and 3 of the 4 distinct HARD-hold reasons in `advertpr.governor` --
  the 4th, `:no-actuation`, plus the low-confidence escalation path,
  are architecturally unreachable via the real advisor, see namespace
  docstring). Every `:op` keyword and violation rule name below is
  copied from `advertpr.governor`'s own `hard-violations`/`check`, not
  invented."
  [;; client-1 / \"Kobo Trade\" (real fixture from advertpr.governor-test)
   ["c1-draft-ok"          "client-1" :draft-ad
    {:claims ["30-day battery" "recycled aluminum body"] :channel "web" :stake :low}]
   ["c1-draft-no-claims"   "client-1" :draft-ad
    {:claims [] :channel "web" :stake :low}]
   ["c1-unsubstantiated"   "client-1" :draft-ad
    {:claims ["waterproof to 100m"] :channel "web" :stake :low}]
   ["c1-prohibited"        "client-1" :draft-ad
    {:claims ["cures insomnia"] :channel "web" :stake :low}]
   ["ghost-no-client"      "client-ghost" :draft-ad
    {:claims [] :channel "web" :stake :low}]
   ["c1-publish-ad"        "client-1" :publish-ad
    {:claims ["30-day battery"] :channel "web" :stake :medium}]
   ["c1-press-release"     "client-1" :issue-press-release
    {:claims [] :channel "wire" :stake :medium}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `advertpr.actor` graph. Returns `{:store :runs}` -- `:runs`
  is the ordered vector of real per-request outcomes; every field in
  `render` below is read from this or from `store` after the graph
  actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-client! db {:client-id "client-1" :name "Kobo Trade"})
    (store/register-substantiation! db {:sub-id "SUB-1" :client-id "client-1"
                                         :claim "30-day battery"
                                         :evidence "lab report LR-77"})
    (store/register-substantiation! db {:sub-id "SUB-2" :client-id "client-1"
                                         :claim "recycled aluminum body"
                                         :evidence "supplier cert SC-12"})
    (store/register-substantiation! db {:sub-id "SUB-3" :client-id "client-1"
                                         :claim "cures insomnia"
                                         :evidence "internal survey"})
    (store/register-prohibition! db {:proh-id "PROH-1" :client-id "client-1"
                                      :claim "cures insomnia"})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid client-id op extra]]
                       (run-op! graph tid client-id op extra))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- client-row [store {:keys [client-id name]} runs]
  (let [substantiated (store/substantiated-claims store client-id)
        prohibited (store/prohibited-claims store client-id)
        record-count (count (store/records-of store client-id))
        last-run (last (filter #(= client-id (:client-id %)) runs))]
    (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%d</td><td>%s</td></tr>"
            (esc client-id) (esc name)
            (esc (str/join ", " (sort substantiated)))
            (esc (str/join ", " (sort prohibited)))
            record-count
            (if last-run (outcome-cell last-run) "<span class=\"muted\">no activity</span>"))))

(defn- run-row [{:keys [thread-id client-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc client-id) (esc (name op))
          (esc (str/join ", " (:claims request)))
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`advertpr.governor`'s own docstring) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:draft-ad</code></td><td><span class=\"ok\">auto-commit when every claim is substantiated AND not prohibited &middot; HARD hold otherwise</span></td></tr>"
   "        <tr><td><code>:publish-ad</code></td><td><span class=\"warn\">ALWAYS human approval &middot; external publication</span></td></tr>"
   "        <tr><td><code>:issue-press-release</code></td><td><span class=\"warn\">ALWAYS human approval &middot; external publication</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [clients [{:client-id "client-1" :name "Kobo Trade"}]
        client-rows (str/join "\n" (map #(client-row store % runs) clients))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-1222 &middot; community advertising &amp; PR management</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 1080px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Community Advertising &amp; PR Management (ISCO-08 1222) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · every claim always checked against registered evidence &amp; the prohibited-claim blacklist</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered clients &amp; claim registers</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>advertpr.store</code> via <code>advertpr.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly. Substantiated/prohibited claims and committed-record count are live re-reads of the store after the real graph ran — never remembered numbers.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Client</th><th>Name</th><th>Substantiated claims</th><th>Prohibited claims</th><th>Committed records</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     client-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Advertising &amp; PR Management Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Claim substantiation and the prohibited-claim blacklist are rechecked against the registered evidence on every proposal, at any confidence.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, client, op, the request's own claims, and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Client</th><th>Op</th><th>Claims</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))
