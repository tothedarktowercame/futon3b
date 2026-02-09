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

(defn- passthrough-artifact
  "Extract an explicit artifact from the request for pass-through tasks
   (documentation, review, etc.) that don't need a real exec function."
  [state]
  (or (get-in state [:ports :I-request :artifact])
      (get-in state [:ports :I-request :payload :artifact])))

(defn- register-artifact
  "Build and validate an Artifact evidence record."
  [state artifact elapsed-ms]
  (let [task (get-in state [:evidence :task-spec])
        record {:artifact/id (or (:artifact/id artifact) (u/gen-id "artifact"))
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
        (assoc :result {:ok true}))))

(defn apply!
  "Run bounded execution and register an Artifact record.

  Two execution modes:
  1. Injected exec/fn — called with task/assignment/psr/environment context,
     must return a map with artifact data. Subject to budget enforcement.
  2. Pass-through — if no exec/fn but an :artifact map is in the request,
     register it directly. For documentation, review, or pre-completed tasks."
  [state]
  (let [task (get-in state [:evidence :task-spec])
        assignment (get-in state [:evidence :assignment])
        psr (get-in state [:evidence :psr])
        env (get-in state [:ports :I-environment])
        f (exec-fn state)
        pt (passthrough-artifact state)]
    (cond
      ;; Mode 1: injected exec function
      (fn? f)
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
              (register-artifact state artifact elapsed-ms)))))

      ;; Mode 2: pass-through artifact
      (map? pt)
      (register-artifact state pt 0.0)

      ;; No exec and no passthrough
      :else
      (assoc state :result (errors/reject :g2/artifact-unregistered
                                          {:reason "no exec function or passthrough artifact configured"})))))
