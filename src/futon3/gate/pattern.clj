(ns futon3.gate.pattern
  "G3 - Pattern reference gate.

  Patterns:
  - coordination/mandatory-psr
  - coordination/pattern-search-protocol"
  (:require [futon3.gate.errors :as errors]
            [futon3.gate.shapes :as shapes]
            [futon3.gate.util :as u]))

(defn- patterns-exists?
  [patterns pattern-id]
  (cond
    (fn? (:patterns/exists? patterns)) (boolean ((:patterns/exists? patterns) pattern-id))
    (set? (:patterns/ids patterns)) (contains? (:patterns/ids patterns) pattern-id)
    (coll? (:patterns/ids patterns)) (contains? (set (:patterns/ids patterns)) pattern-id)
    :else false))

(defn apply!
  "Require a PSR (or a gap declaration) and ensure the referenced pattern exists.

  Expects:
  - task spec in [:evidence :task-spec]
  - patterns config in (get-in state [:ports :I-patterns])
  - psr input in (get-in state [:ports :I-request :psr])"
  [state]
  (let [task (get-in state [:evidence :task-spec])
        task-id (:task/id task)
        patterns (or (get-in state [:ports :I-patterns]) {})
        psr-in (or (get-in state [:ports :I-request :psr])
                   (get-in state [:ports :I-request :payload :psr])
                   {})
        psr-type (or (:psr/type psr-in)
                     (when (:gap? psr-in) :gap)
                     :selection)
        pattern-ref (or (:psr/pattern-ref psr-in)
                        (:pattern/id psr-in)
                        (:pattern-ref psr-in))]
    (cond
      (and (not= :gap psr-type) (u/blankish? pattern-ref))
      (assoc state :result (errors/reject :g3/no-psr
                                          {:task/id task-id
                                           :missing [:psr/pattern-ref]
                                           :accepted-types [:selection :gap]}))

      (and (= :selection psr-type)
           (not (patterns-exists? patterns pattern-ref)))
      (assoc state :result (errors/reject :g3/pattern-not-found
                                          {:task/id task-id
                                           :psr/pattern-ref pattern-ref}))

      :else
      (let [record {:psr/id (or (:psr/id psr-in) (u/gen-id "psr"))
                    :psr/task-id task-id
                    :psr/type (if (= :gap psr-type) :gap :selection)
                    :psr/pattern-ref (when (= :selection psr-type) pattern-ref)
                    :psr/candidates (vec (or (:psr/candidates psr-in) []))
                    :psr/rationale (:psr/rationale psr-in)}
            _ (shapes/validate! shapes/PSR record)
            event {:gate/id :g3
                   :gate/record record
                   :gate/at (u/now-iso)}]
        (-> state
            (assoc-in [:evidence :psr] record)
            (update :proof-path (fnil conj []) event)
            (assoc :result {:ok true}))))))
