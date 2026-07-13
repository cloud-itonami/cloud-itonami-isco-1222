(ns advertpr.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [advertpr.store :as store]
            [advertpr.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-substantiation! st {:sub-id "SUB-1" :client-id "client-1"
                                        :claim "30-day battery"
                                        :evidence "lab report LR-77"})
    (store/register-substantiation! st {:sub-id "SUB-2" :client-id "client-1"
                                        :claim "recycled aluminum body"
                                        :evidence "supplier cert SC-12"})
    (store/register-prohibition! st {:proh-id "PROH-1" :client-id "client-1"
                                     :claim "cures insomnia"})
    st))

(defn- draft [claims]
  {:op :draft-ad :effect :propose :claims claims :channel "web"
   :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-with-substantiated-claims
  (let [st (fresh-store)
        v (governor/check req {} (draft ["30-day battery"
                                         "recycled aluminum body"]) st)]
    (is (:ok? v))))

(deftest ok-with-no-claims
  (testing "copy that asserts nothing needs no evidence"
    (let [st (fresh-store)
          v (governor/check req {} (draft []) st)]
      (is (:ok? v)))))

(deftest hard-on-unsubstantiated-claim
  (testing "advertising is citation of registered evidence, not creative writing"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (draft ["30-day battery"
                                                  "waterproof to 100m"])
                                          :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :unsubstantiated-claim (:rule %)) (:violations v))))))

(deftest hard-on-prohibited-claim
  (testing "the blacklist is arithmetic, not tone"
    (let [st (fresh-store)]
      ;; even a substantiated claim is banned if the prohibition table
      ;; lists it — regulation outranks evidence
      (store/register-substantiation! st {:sub-id "SUB-3" :client-id "client-1"
                                          :claim "cures insomnia"
                                          :evidence "internal survey"})
      (let [v (governor/check req {} (assoc (draft ["cures insomnia"])
                                            :confidence 0.99) st)]
        (is (:hard? v))
        (is (some #(= :prohibited-claim (:rule %)) (:violations v)))))))

(deftest hard-on-foreign-substantiation
  (testing "another client's evidence does not substantiate this client's claim"
    (let [st (fresh-store)]
      (store/register-client! st {:client-id "client-2" :name "Other"})
      (let [v (governor/check {:client-id "client-2"} {}
                              (draft ["30-day battery"]) st)]
        (is (:hard? v))
        (is (some #(= :unsubstantiated-claim (:rule %)) (:violations v)))))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (draft []) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (draft ["30-day battery"])
                                        :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest escalates-ad-publication
  (let [st (fresh-store)
        v (governor/check req {} {:op :publish-ad :effect :propose
                                  :claims ["30-day battery"] :channel "web"
                                  :confidence 0.9 :stake :medium} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-press-release
  (let [st (fresh-store)
        v (governor/check req {} {:op :issue-press-release :effect :propose
                                  :claims [] :channel "wire"
                                  :confidence 0.9 :stake :medium} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} {:op :draft-ad :effect :propose
                                  :claims [] :channel "web"
                                  :confidence 0.3 :stake :low} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
