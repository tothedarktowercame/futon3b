(ns futon3.gate.exec
  "G2 - Execution gate.

  Patterns:
  - coordination/bounded-execution
  - coordination/artifact-registration"
  (:require [futon3.gate.errors :as errors]
            [futon3.gate.shapes :as shapes]
            [futon3.gate.util :as u]))

(defn- budget-ms [state]
  (long (or (get-in state [:opts :budget/ms])
            (get-in state [:ports :I-request :budget/ms])
            5000)))

(defn- exec-fn [state]
  (or (get-in state [:ports :I-request :exec/fn])
      (get-in state [:ports :I-environment :exec/fn])
      (get-in state [:ports :I-request :exec-fn])))

(defn apply!
  "Run bounded execution and register an Artifact record.

  The actual side effects are delegated to an injected exec fn for Prototype 0.
  Later, this gate will compose:
  - futon3 check DSL
  - futon3a sidecar.store proposal pipeline
  - futon3a meme.arrow typed edge creation"
  [state]
  (let [task (get-in state [:evidence :task-spec])
        assignment (get-in state [:evidence :assignment])
        psr (get-in state [:evidence :psr])
        env (get-in state [:ports :I-environment])
        f (exec-fn state)]
    (if-not (fn? f)
      (assoc state :result (errors/reject :g2/artifact-unregistered
                                          {:reason "no exec function configured"}))
      (let [start (System/nanoTime)
            timeout (budget-ms state)
            outcome (try
                      (let [p (promise)]
                        (future
                          (try
                            (deliver p {:ok true :value (f {:task task
                                                           :assignment assignment
                                                           :psr psr
                                                           :environment env})})
                            (catch Throwable t
                              (deliver p {:ok false :error t}))))
                        (deref p timeout ::timeout))
                      (catch Throwable t
                        {:ok false :error t}))
            elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)]
        (cond
          (= outcome ::timeout)
          (assoc state :result (errors/reject :g2/budget-exceeded
                                              {:budget/ms timeout
                                               :elapsed/ms elapsed-ms}))

          (and (map? outcome) (false? (:ok outcome)) (:error outcome))
          (assoc state :result (errors/reject :g2/artifact-unregistered
                                              {:exception (-> (:error outcome) .getMessage)
                                               :elapsed/ms elapsed-ms}))

          :else
          (let [value (if (and (map? outcome) (contains? outcome :value))
                        (:value outcome)
                        outcome)
                artifact (cond
                           (map? (:artifact value)) (:artifact value)
                           (map? value) value
                           :else nil)]
            (if-not (map? artifact)
              (assoc state :result (errors/reject :g2/artifact-unregistered
                                                  {:reason "exec did not return an artifact map"
                                                   :elapsed/ms elapsed-ms}))
              (let [record {:artifact/id (or (:artifact/id artifact) (u/gen-id "artifact"))
                            :artifact/task-id (:task/id task)
                            :artifact/type (or (:artifact/type artifact) :artifact/unknown)
                            :artifact/ref (or (:artifact/ref artifact)
                                              (dissoc artifact :artifact/id :artifact/type))
                            :artifact/registered-at (u/now-iso)}
                    _ (shapes/validate! shapes/Artifact record)
                    event {:gate/id :g2
                           :gate/record record
                           :gate/at (u/now-iso)}]
                (-> state
                    (assoc-in [:evidence :artifact] record)
                    (assoc-in [:evidence :exec/outcome] (assoc artifact :exec/elapsed-ms elapsed-ms))
                    (update :proof-path (fnil conj []) event)
                    (assoc :result {:ok true}))))))))))
