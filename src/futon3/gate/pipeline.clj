(ns futon3.gate.pipeline
  "G5→G0 composition.

  This is the Level 0 boundary: all coordinated work must traverse the gates
  in order. The first failing gate determines the error; later gates never run."
  (:require [futon3.gate.auth :as g4]
            [futon3.gate.evidence :as g0]
            [futon3.gate.exec :as g2]
            [futon3.gate.pattern :as g3]
            [futon3.gate.task :as g5]
            [futon3.gate.shapes :as shapes]
            [futon3.gate.util :as u]
            [futon3.gate.validate :as g1]
            [futon3b.query.relations :as relations]))

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
      ;; Rejection: persist a minimal proof-path so L1 can observe the failure.
      (let [result (:result s6)
            rejection-record {:type (:type result)
                              :error/key (:error/key result)
                              :http/status (:http/status result)
                              :message (:message result)
                              :details (or (:details result) {})}
            rejection-event {:gate/id (:gate/id result)
                             :gate/record rejection-record
                             :gate/at (u/now-iso)}
            proof-path {:path/id (u/gen-id "path")
                        :events (conj (vec (:proof-path s6)) rejection-event)}
            user-sink (or (get-in s6 [:ports :I-request :evidence/sink])
                          (get-in s6 [:ports :I-environment :evidence/sink]))]
        ;; Best-effort write; don't let sink failure mask the gate rejection.
        (try
          ;; Ensure the persisted proof-path stays within the typed evidence boundary.
          (shapes/validate! shapes/ProofPath proof-path)
          ;; Always persist to the durable store so L1 can load it.
          (relations/append-proof-path! {:proof-path proof-path
                                         :evidence (assoc (:evidence s6)
                                                          :rejection result)})
          ;; Optionally also publish to an external sink, if provided.
          (when (and (fn? user-sink)
                     (not (identical? user-sink relations/append-proof-path!)))
            (user-sink {:proof-path proof-path
                        :evidence (assoc (:evidence s6)
                                         :rejection result)}))
          (catch Throwable _))
        (assoc result
               :proof-path (:proof-path s6)
               :evidence (:evidence s6))))))
