(ns futon3.gate.task
  "G5 - Task specification gate.

  Patterns:
  - coordination/task-shape-validation
  - coordination/intent-to-mission-binding"
  (:require [futon3.gate.errors :as errors]
            [futon3.gate.shapes :as shapes]
            [futon3.gate.util :as u]
            [futon3b.query.relations :as relations]))

(defn- mission-active?
  [missions mission-ref]
  (let [m (get missions mission-ref)]
    (and m (= :active (:mission/state m)))))

(defn apply!
  "Validate and bind task into a TaskSpec record.

  Expects:
  - (get-in state [:ports :I-request :task]) map
  - (get-in state [:ports :I-missions]) map mission-ref -> {:mission/state :active|...}
  "
  [state]
  (let [task (or (get-in state [:ports :I-request :task])
                 (get-in state [:ports :I-request :payload :task]))
        mission-ref (:task/mission-ref task)
        missions (or (not-empty (get-in state [:ports :I-missions]))
                     (relations/load-missions))]
    (cond
      (u/blankish? mission-ref)
      (assoc state :result (errors/reject :g5/missing-mission-ref
                                          {:missing [:task/mission-ref]}))

      (not (seq (:task/success-criteria task)))
      (assoc state :result (errors/reject :g5/missing-success-criteria
                                          {:missing [:task/success-criteria]}))

      (not (mission-active? missions mission-ref))
      (assoc state :result (errors/reject :g5/mission-not-active
                                          {:task/mission-ref mission-ref
                                           :mission (get missions mission-ref)}))

      :else
      (let [spec (-> task
                     (select-keys [:task/id :task/mission-ref :task/intent :task/scope :task/typed-io :task/success-criteria])
                     (update :task/id #(or % (u/gen-id "task"))))
            _ (shapes/validate! shapes/TaskSpec spec)
            event {:gate/id :g5
                   :gate/record spec
                   :gate/at (u/now-iso)}]
        (-> state
            (assoc-in [:evidence :task-spec] spec)
            (update :proof-path (fnil conj []) event)
            (assoc :result {:ok true}))))))
