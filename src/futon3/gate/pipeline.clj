(ns futon3.gate.pipeline
  "G5→G0 composition.

  This is the Level 0 boundary: all coordinated work must traverse the gates
  in order. The first failing gate determines the error; later gates never run."
  (:require [futon3.gate.auth :as g4]
            [futon3.gate.evidence :as g0]
            [futon3.gate.exec :as g2]
            [futon3.gate.pattern :as g3]
            [futon3.gate.task :as g5]
            [futon3.gate.validate :as g1]))

(defn- ok? [state]
  (true? (get-in state [:result :ok])))

(defn- gate-step [f state]
  (let [state' (f state)]
    (if (contains? state' :result)
      state'
      (assoc state' :result {:ok true}))))

(defn run
  "Run the gate pipeline.

  INPUT is a map containing port data, e.g.:
  {:I-request {:task {...} :agent-id \"codex\" :psr {...} :exec/fn (fn ...) :par {...} :evidence/sink (fn ...)}
   :I-missions {...}
   :I-patterns {...}
   :I-registry {...}
   :I-environment {...}
   :opts {:budget/ms 1000}}"
  [input]
  (let [state0 {:ports (select-keys input [:I-request :I-missions :I-patterns :I-registry :I-environment :I-tensions])
                :opts (:opts input)
                :evidence {}
                :proof-path []}
        s1 (gate-step g5/apply! state0)
        s2 (if (ok? s1) (gate-step g4/apply! s1) s1)
        s3 (if (ok? s2) (gate-step g3/apply! s2) s2)
        s4 (if (ok? s3) (gate-step g2/apply! s3) s3)
        s5 (if (ok? s4) (gate-step g1/apply! s4) s4)
        s6 (if (ok? s5) (gate-step g0/apply! s5) s5)]
    (if (ok? s6)
      (:result s6)
      ;; Rejection: include partial proof-path for audit.
      (assoc (:result s6)
             :proof-path (:proof-path s6)
             :evidence (:evidence s6)))))

