(ns advertpr.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [advertpr.actor :as actor]
            [advertpr.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-substantiation! st {:sub-id "SUB-1" :client-id "client-1"
                                        :claim "30-day battery"
                                        :evidence "lab report LR-77"})
    st))

(deftest commits-a-substantiated-draft
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :draft-ad :stake :low
                 :claims ["30-day battery"] :channel "web"}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-an-unsubstantiated-draft
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :draft-ad :stake :low
                 :claims ["waterproof to 100m"] :channel "web"}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-publishes-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :publish-ad :stake :medium
                 :claims ["30-day battery"] :channel "web"}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
