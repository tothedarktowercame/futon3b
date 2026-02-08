(ns futon3.gate.pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon3.gate.pipeline :as pipeline]))

(def base-missions
  {"M-coordination-rewrite"
   {:mission/id "M-coordination-rewrite"
    :mission/state :active}})

(def base-patterns
  {:patterns/ids #{"coordination/task-shape-validation"
                   "coordination/mandatory-psr"}})

(def base-registry
  {:agents {"codex-1" {:capabilities [:coordination/execute :coordination/review]}}})

(defn ok-sink [{:keys [proof-path]}]
  {:ok true :path/id (:path/id proof-path)})

(defn ok-exec [_]
  {:artifact/type :demo/artifact
   :artifact/ref {:demo "ok"}
   :exec/success? true})

(def base-input
  {:I-missions base-missions
   :I-patterns base-patterns
   :I-registry base-registry
   :I-environment {}
   :opts {:budget/ms 200}
   :I-request {:task {:task/id "T-1"
                      :task/mission-ref "M-coordination-rewrite"
                      :task/intent "wire gate scaffold"
                      :task/success-criteria [:demo/ok]
                      :task/required-capabilities [:coordination/execute]}
               :agent-id "codex-1"
               :psr {:psr/type :selection
                     :psr/pattern-ref "coordination/mandatory-psr"
                     :psr/rationale "Prototype 0"}
               :exec/fn ok-exec
               :par {:par/session-ref "S-1"
                     :par/what-worked "ok"
                     :par/what-didnt "n/a"
                     :par/prediction-errors []
                     :par/suggestions []}
               :evidence/sink ok-sink}})

(deftest rejects-missing-mission-ref
  (let [out (pipeline/run (assoc-in base-input [:I-request :task] (dissoc (get-in base-input [:I-request :task]) :task/mission-ref)))]
    (is (false? (:ok out)))
    (is (= :g5 (:gate/id out)))
    (is (= :g5/missing-mission-ref (:error/key out)))))

(deftest rejects-missing-success-criteria
  (let [out (pipeline/run (assoc-in base-input [:I-request :task :task/success-criteria] []))]
    (is (false? (:ok out)))
    (is (= :g5 (:gate/id out)))
    (is (= :g5/missing-success-criteria (:error/key out)))))

(deftest rejects-unregistered-agent
  (let [out (pipeline/run (assoc-in base-input [:I-request :agent-id] "nope"))]
    (is (false? (:ok out)))
    (is (= :g4 (:gate/id out)))
    (is (= :g4/agent-not-registered (:error/key out)))))

(deftest rejects-pattern-not-found
  (let [out (pipeline/run (assoc-in base-input [:I-request :psr :psr/pattern-ref] "missing/pattern"))]
    (is (false? (:ok out)))
    (is (= :g3 (:gate/id out)))
    (is (= :g3/pattern-not-found (:error/key out)))))

(deftest rejects-missing-exec-fn
  (let [out (pipeline/run (update-in base-input [:I-request] dissoc :exec/fn))]
    (is (false? (:ok out)))
    (is (= :g2 (:gate/id out)))
    (is (= :g2/artifact-unregistered (:error/key out)))))

(deftest rejects-criteria-not-met
  (let [bad-exec (fn [_] {:artifact/type :demo/artifact :artifact/ref {:demo "no"} :exec/success? false})
        out (pipeline/run (assoc-in base-input [:I-request :exec/fn] bad-exec))]
    (is (false? (:ok out)))
    (is (= :g1 (:gate/id out)))
    (is (= :g1/criteria-not-met (:error/key out)))))

(deftest rejects-missing-par
  (let [out (pipeline/run (update-in base-input [:I-request] dissoc :par))]
    (is (false? (:ok out)))
    (is (= :g0 (:gate/id out)))
    (is (= :g0/no-par (:error/key out)))))

(deftest happy-path-produces-proof-path
  (let [out (pipeline/run base-input)]
    (is (true? (:ok out)))
    (is (map? (:O-proof-path out)))
    (is (= 6 (count (get-in out [:O-proof-path :events]))))
    (is (string? (get-in out [:O-proof-path :path/id])))
    (is (map? (:O-evidence out)))
    (is (map? (:O-artifacts out)))))

