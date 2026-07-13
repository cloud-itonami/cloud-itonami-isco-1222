(ns advertpr.advisor
  "AdvertisingPRManagementAdvisor — proposes an advertising/PR
  operation (draft an ad, publish an ad, issue a press release) for a
  registered organization. Swappable mock/llm; the advisor ONLY
  proposes — `advertpr.governor` checks the substantiation and
  prohibition sets independently. Modeled on cloud-itonami-isco-4311's
  advisor.

  A proposal: {:op :draft-ad|:publish-ad|:issue-press-release
               :effect :propose :claims [str] :channel str
               :stake kw :confidence n :rationale str}")

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake claims channel] :as request}]
  {:op op
   :effect :propose
   :claims claims
   :channel channel
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are an advertising and PR advisor. Given a request, propose an
   :op, the :claims list and :channel, an honest :confidence and a
   :stake. Never invent evidence — the governor checks every claim
   against the registered substantiation and prohibition sets.")

(defn- parse-proposal [content]
  (try
    (let [p (read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
