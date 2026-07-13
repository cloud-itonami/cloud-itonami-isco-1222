(ns advertpr.governor
  "AdvertisingPRManagementGovernor — the independent safety/
  traceability layer for the ISCO-08 1222 community advertising & PR
  actor (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors
  section). Modeled on cloud-itonami-isco-4311's bookkeeping.governor.
  Advertising twist: a claim is either a member of the registered
  substantiation set or it is not — advertising is the citation of
  registered evidence, not creative writing; and the prohibited-claim
  table is a regulatory blacklist whose intersection with the copy
  must be empty.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the organization must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. claim substantiation — every claim in the copy must be a member
                           of THIS client's registered substantiation
                           set (subset check; no invented evidence,
                           no borrowing another client's evidence).
    4. prohibited claims — the intersection of the copy's claims and
                           the registered prohibited set must be empty
                           (a blacklist is arithmetic, not tone).
  ESCALATION invariants (:escalate? true, human sign-off):
    5. :op :publish-ad / :issue-press-release (external publication).
    6. low confidence (< `confidence-floor`)."
  (:require [clojure.set :as set]
            [advertpr.store :as store]))

(def confidence-floor 0.6)

(defn- hard-violations [{:keys [proposal]} client-record substantiated prohibited]
  (let [claims (set (:claims proposal))
        unsubstantiated (set/difference claims substantiated)
        banned (set/intersection claims prohibited)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and client-record (seq unsubstantiated))
      (conj {:rule :unsubstantiated-claim
             :detail (str "根拠未登録の主張 " (vec unsubstantiated)
                          "（広告は登録済み根拠の引用であって創作ではない）")})

      (and client-record (seq banned))
      (conj {:rule :prohibited-claim
             :detail (str "禁止主張表と交差 " (vec banned)
                          "（ブラックリストは算術であってトーンではない）")}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `advertpr.store/Store`. Pure — never mutates
  the store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        substantiated (store/substantiated-claims store (:client-id request))
        prohibited (store/prohibited-claims store (:client-id request))
        hard (hard-violations {:request request :proposal proposal}
                              client-record substantiated prohibited)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (contains? #{:publish-ad :issue-press-release} (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
