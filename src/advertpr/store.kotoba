(ns advertpr.store
  "SSoT for the ISCO-08 1222 community advertising & PR management
  actor (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors
  section). Modeled on cloud-itonami-isco-4311's bookkeeping.store.

  Domain:

    client         — a registered organization (:client-id, :name)
    substantiation — a registered evidence record {:sub-id :client-id
                     :claim :evidence}. Every claim an ad makes must be
                     a member of this set — advertising is the citation
                     of registered evidence, not creative writing.
    prohibition    — a registered forbidden claim {:proh-id :client-id
                     :claim} (regulatory / legal blacklist). The
                     intersection of ad claims and this set must be
                     empty.
    record         — a committed operating record (drafted ad,
                     published ad, press release) — written ONLY via
                     commit-record!.
    ledger         — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (substantiated-claims [s client-id])
  (prohibited-claims [s client-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-substantiation! [s sub])
  (register-prohibition! [s proh])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (substantiated-claims [_ client-id]
    (into #{} (comp (filter #(= client-id (:client-id %)))
                    (map :claim))
          (vals (:substantiations @a))))
  (prohibited-claims [_ client-id]
    (into #{} (comp (filter #(= client-id (:client-id %)))
                    (map :claim))
          (vals (:prohibitions @a))))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-substantiation! [s sub]
    (swap! a assoc-in [:substantiations (:sub-id sub)] sub) s)
  (register-prohibition! [s proh]
    (swap! a assoc-in [:prohibitions (:proh-id proh)] proh) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :substantiations {}
                                    :prohibitions {} :records [] :ledger []}
                                   seed)))))
