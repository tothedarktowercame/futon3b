(ns futon3.gate.evidence
  "G0 - Evidence durability gate.

  Patterns:
  - coordination/session-durability-check
  - coordination/par-as-obligation"
  (:require [futon3.gate.errors :as errors]
            [futon3.gate.shapes :as shapes]
            [futon3.gate.util :as u]
            [futon3b.query.relations :as relations]))

(defn- sink-fn
  "Resolve the evidence sink function. Tries injected sinks first,
   falls back to the built-in EDN proof-path store."
  [state]
  (or (get-in state [:ports :I-request :evidence/sink])
      (get-in state [:ports :I-environment :evidence/sink])
      (get-in state [:ports :I-request :evidence-sink])
      relations/append-proof-path!))

(defn- par-input [state]
  (or (get-in state [:ports :I-request :par])
      (get-in state [:ports :I-request :payload :par])))

(defn apply!
  "Enforce PAR obligation and verify durability by persisting the proof-path.

  For Prototype 0, durability is an injected sink function returning
  {:ok true :path/id \"...\"}."
  [state]
  (let [task (get-in state [:evidence :task-spec])
        par-in (par-input state)
        sink (sink-fn state)]
    (cond
      (nil? par-in)
      (assoc state :result (errors/reject :g0/no-par
                                          {:task/id (:task/id task)
                                           :missing [:par]}))

      (not (fn? sink))
      (assoc state :result (errors/reject :g0/durability-failed
                                          {:reason "no evidence sink configured"}))

      :else
      (let [par {:par/id (or (:par/id par-in) (u/gen-id "par"))
                 :par/session-ref (or (:par/session-ref par-in) (:task/id task))
                 :par/what-worked (:par/what-worked par-in)
                 :par/what-didnt (:par/what-didnt par-in)
                 :par/prediction-errors (vec (or (:par/prediction-errors par-in) []))
                 :par/suggestions (vec (or (:par/suggestions par-in) []))}
            _ (shapes/validate! shapes/PAR par)
            state (-> state
                      (assoc-in [:evidence :par] par)
                      (update :proof-path (fnil conj []) {:gate/id :g0 :gate/record par :gate/at (u/now-iso)}))
            proof-path {:path/id (or (get-in state [:proof-path/id]) (u/gen-id "path"))
                        :events (vec (get state :proof-path))}
            _ (shapes/validate! shapes/ProofPath proof-path)
            persisted (try
                        (sink {:proof-path proof-path
                               :evidence (:evidence state)})
                        (catch Throwable t
                          {:ok false :error (.getMessage t)}))]
        (if-not (:ok persisted)
          (assoc state :result (errors/reject :g0/durability-failed
                                              {:task/id (:task/id task)
                                               :error (or (:error persisted) "sink returned ok=false")}))
          (assoc state
                 :result {:ok true
                          :O-artifacts (get-in state [:evidence :artifact])
                          :O-evidence {:task-spec (get-in state [:evidence :task-spec])
                                       :assignment (get-in state [:evidence :assignment])
                                       :psr (get-in state [:evidence :psr])
                                       :pur (get-in state [:evidence :pur])
                                       :par (get-in state [:evidence :par])}
                          :O-proof-path (assoc proof-path :path/id (or (:path/id persisted) (:path/id proof-path)))
                          :O-events []}))))))
