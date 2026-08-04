(ns futon3.gate.shapes-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon3.gate.shapes :as shapes]))

(def ^:private valid-records
  {:g5 {:task/id "task-1"
        :task/mission-ref "mission-1"
        :task/success-criteria [:done]}
   :g4 {:assign/task-id "task-1"
        :assign/agent-id "agent-1"
        :assign/capabilities [:proof]
        :assign/exclusive? true}
   :g3 {:psr/id "psr-1"
        :psr/task-id "task-1"
        :psr/type :selection}
   :g2 {:artifact/id "artifact-1"
        :artifact/task-id "task-1"
        :artifact/type :proof
        :artifact/ref {:path "proof.lean"}
        :artifact/registered-at "2026-08-04T00:00:00Z"}
   :g1 {:pur/id "pur-1"
        :pur/psr-ref "psr-1"
        :pur/outcome :pass
        :pur/criteria-eval {:done true}}
   :g0 {:par/id "par-1"
        :par/session-ref "session-1"}
   :l1-observe {:tension/id "tension-1"
                :tension/type :structural-irritation
                :tension/evidence-refs ["path-1"]
                :tension/frequency 2
                :tension/contexts ["mission-1"]
                :tension/description "Repeated mismatch"
                :tension/fingerprint "mismatch"
                :tension/observed-at "2026-08-04T00:00:00Z"}
   :l1-canon {:canon/id "canon-1"
              :canon/tension-ref "tension-1"
              :canon/phase :naming
              :canon/pattern-id "pattern-1"
              :canon/action :create
              :canon/rationale "Repeated evidence"
              :canon/at "2026-08-04T00:00:00Z"}})

(defn- event
  [gate-id record]
  {:gate/id gate-id
   :gate/record record
   :gate/at "2026-08-04T00:00:00Z"})

(defn- validation-error
  [proof-path]
  (try
    (shapes/validate! shapes/ProofPath proof-path)
    nil
    (catch clojure.lang.ExceptionInfo e
      {:message (ex-message e)
       :data (ex-data e)})))

(deftest every-current-gate-record-still-validates
  (doseq [[gate-id record] valid-records]
    (testing (str gate-id " validates its current writer record")
      (is (= (event gate-id record)
             (shapes/validate! shapes/ProofPathEvent (event gate-id record)))))))

(deftest gate-rejection-remains-valid-at-every-gate
  (let [rejection {:type :gate/reject
                   :error/key :gate/test
                   :http/status 400
                   :message "rejected"
                   :details {}}]
    (doseq [gate-id (keys valid-records)]
      (is (= (event gate-id rejection)
             (shapes/validate! shapes/ProofPathEvent
                               (event gate-id rejection)))))))

(deftest accepted-values-are-not-tightened-by-gate-record-map
  (testing "the historical union still accepts a PUR at :g3"
    (let [cross-gate-event (event :g3 (:g1 valid-records))]
      (is (= cross-gate-event
             (shapes/validate! shapes/ProofPathEvent cross-gate-event))))))

(deftest malformed-g3-record-reports-only-psr
  (let [bad-psr {:psr/id "psr-1"
                 :psr/task-id "task-1"
                 :psr/type :selection
                 :pur/outcome :pass}
        error (validation-error {:path/id "path-1"
                                 :events [(event :g3 bad-psr)]})]
    (is (= "Invalid PSR evidence shape" (:message error)))
    (is (= :PSR (get-in error [:data :schema])))
    (is (= :g3 (get-in error [:data :gate/id])))
    (is (= :PSR (get-in error [:data :record-type])))
    (is (= {:pur/outcome ["disallowed key"]}
           (get-in error [:data :details])))))

(deftest malformed-g1-record-reports-only-pur
  (let [bad-pur {:pur/id "pur-1"
                 :pur/psr-ref "psr-1"
                 :pur/outcome :pass}
        error (validation-error {:path/id "path-1"
                                 :events [(event :g1 bad-pur)]})]
    (is (= "Invalid PUR evidence shape" (:message error)))
    (is (= :PUR (get-in error [:data :schema])))
    (is (= :g1 (get-in error [:data :gate/id])))
    (is (= :PUR (get-in error [:data :record-type])))
    (is (= {:pur/criteria-eval ["missing required key"]}
           (get-in error [:data :details])))))
