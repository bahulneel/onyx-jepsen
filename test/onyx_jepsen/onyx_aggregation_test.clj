(ns onyx-jepsen.onyx-aggregation-test
  (:require [clojure.test :refer :all]
            [onyx-jepsen.onyx-test :as onyx-test]
            [jepsen 
             [client :as client]
             [checker :as check]
             [generator :as gen]]
            [onyx-jepsen.gen :as onyx-gen]
            [jepsen.core :as jc]
            [jepsen.control :as c]))

(def version
  "What onyx version should we test?"
  "version-not-supplied")

(def test-name 
  "onyx-aggregation-test")

(def env-config
  (-> "resources/prod-env-config.edn" slurp read-string))

(def peer-config
  (-> "resources/prod-peer-config.edn" slurp read-string))

(def test-setup 
  {:job-params {:batch-size 1}
   :job-type :window-state-job
   :nemesis :bridge-shuffle ; :bridge-shuffle or :random-halves
   :awake-mean 200
   :stopped-mean 100
   :time-limit 1600
   ; may or may not work when 5 is not divisible by n-jobs
   :n-jobs 1
   ; Minimum total = 5 (input ledgers) + 1 intermediate + 1 output
   :n-peers 3})

(defn generator [{:keys [job-type time-limit awake-mean stopped-mean n-jobs job-params] :as test-setup}]
  (let [input-data (map (fn [n]
                          {:id n :age (rand-int 100) :event-time (java.util.Date. n)}) 
                        (range))] 
    (gen/phases
      (->> (onyx-gen/filter-new identity 
                                (onyx-gen/frequency [(onyx-gen/adds input-data)
                                                     (onyx-gen/submit-job-gen job-type n-jobs job-params)
                                                     ;(gen/once (gc-peer-logs))
                                                     ]
                                                    [0.99
                                                     ;0.01
                                                     0.01]))
           (gen/stagger 1/10)
           (gen/nemesis (onyx-gen/start-stop-nemesis-seq awake-mean stopped-mean))
           (gen/time-limit time-limit)) 

      ;; Bring everything back at the end
      ;; This way we can test that the peers came back up
      (gen/nemesis (gen/once {:type :info :f :stop}))
      ;; Sleep for a while to give peers a chance to come back up
      ;; Should be enough time that curator backoff * max-retries is covered
      (gen/sleep 120)

      (onyx-gen/close-await-completion-gen)
      (onyx-gen/read-ledgers-gen :persist)
      (onyx-gen/read-ledgers-gen :identity-log)
      (onyx-gen/read-peer-log-gen))))

(deftest basic-test
  (is (-> (onyx-test/jepsen-test env-config peer-config test-setup test-name version (generator test-setup))
          jc/run!
          :results
          :valid?)))
