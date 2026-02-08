(ns futon3.gate.auth
  "G4 - Agent authorization gate.

  Patterns:
  - coordination/capability-gate
  - coordination/assignment-binding"
  (:require [clojure.set :as set]
            [futon3.gate.errors :as errors]
            [futon3.gate.shapes :as shapes]
            [futon3.gate.util :as u]))

(defn- agent-entry
  [registry agent-id]
  (or (get registry agent-id)
      (get registry (keyword agent-id))))

(defn- capabilities-for [agent]
  (let [caps (or (:capabilities agent)
                 (:agent/capabilities agent)
                 (:caps agent)
                 [])]
    (->> caps
         (map (fn [c] (cond (keyword? c) c (string? c) (keyword c) :else (keyword (str c)))))
         set)))

(defn apply!
  "Authorize agent and bind an Assignment record.

  Expects:
  - task spec in [:evidence :task-spec]
  - registry map in (get-in state [:ports :I-registry :agents]) or [:ports :I-registry]
  - agent id in [:ports :I-request :agent :agent/id] or [:ports :I-request :agent-id]"
  [state]
  (let [task (get-in state [:evidence :task-spec])
        task-id (:task/id task)
        agent-id (or (get-in state [:ports :I-request :agent :agent/id])
                     (get-in state [:ports :I-request :agent-id])
                     (get-in state [:ports :I-request :agent :id]))
        registry (or (get-in state [:ports :I-registry :agents])
                     (get-in state [:ports :I-registry])
                     {})
        assigned (or (get-in state [:ports :I-registry :assignments]) {})
        required (or (:task/required-capabilities task) [])
        required-set (set (map #(cond (keyword? %) % (string? %) (keyword %) :else (keyword (str %))) required))
        agent (when (and (string? agent-id) (seq agent-id))
                (agent-entry registry agent-id))
        caps (capabilities-for agent)]
    (cond
      (or (u/blankish? agent-id) (nil? agent))
      (assoc state :result (errors/reject :g4/agent-not-registered
                                          {:agent/id agent-id}))

      (and (contains? assigned task-id)
           (not= (get assigned task-id) agent-id))
      (assoc state :result (errors/reject :g4/already-assigned
                                          {:task/id task-id
                                           :assigned-to (get assigned task-id)
                                           :agent/id agent-id}))

      (and (seq required-set) (not (set/subset? required-set caps)))
      (assoc state :result (errors/reject :g4/capability-mismatch
                                          {:agent/id agent-id
                                           :task/id task-id
                                           :required (sort required-set)
                                           :agent/capabilities (sort caps)}))

      :else
      (let [record {:assign/task-id task-id
                    :assign/agent-id agent-id
                    :assign/capabilities (vec (sort (map name caps)))
                    :assign/exclusive? true}
            _ (shapes/validate! shapes/Assignment record)
            event {:gate/id :g4
                   :gate/record record
                   :gate/at (u/now-iso)}]
        (-> state
            (assoc-in [:evidence :assignment] record)
            (update :proof-path (fnil conj []) event)
            (assoc :result {:ok true}))))))
