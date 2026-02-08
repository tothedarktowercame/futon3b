(ns futon3.gate.shapes
  "Evidence shape catalog for gate pipeline records.

  This is the schema boundary between gates. Keep these shapes small, typed,
  and stable; higher-level derivations should sit on top of these records."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def TaskSpec
  [:map {:closed true}
   [:task/id [:string {:min 1}]]
   [:task/mission-ref [:string {:min 1}]]
   [:task/intent {:optional true} [:maybe [:string {:min 1}]]]
   [:task/scope {:optional true} [:maybe map?]]
   [:task/typed-io {:optional true} [:maybe map?]]
   [:task/success-criteria [:vector [:or :keyword :string]]]])

(def Assignment
  [:map {:closed true}
   [:assign/task-id [:string {:min 1}]]
   [:assign/agent-id [:string {:min 1}]]
   [:assign/capabilities [:vector [:or :keyword :string]]]
   [:assign/exclusive? :boolean]])

(def PSR
  [:map {:closed true}
   [:psr/id [:string {:min 1}]]
   [:psr/task-id [:string {:min 1}]]
   [:psr/type [:enum :selection :gap]]
   [:psr/pattern-ref {:optional true} [:maybe [:string {:min 1}]]]
   [:psr/candidates {:optional true} [:vector map?]]
   [:psr/rationale {:optional true} [:maybe [:string {:min 1}]]]])

(def Artifact
  [:map {:closed true}
   [:artifact/id [:string {:min 1}]]
   [:artifact/task-id [:string {:min 1}]]
   [:artifact/type [:or :keyword :string]]
   [:artifact/ref map?]
   [:artifact/registered-at [:string {:min 1}]]])

(def PUR
  [:map {:closed true}
   [:pur/id [:string {:min 1}]]
   [:pur/psr-ref [:string {:min 1}]]
   [:pur/outcome [:enum :pass :fail]]
   [:pur/criteria-eval map?]
   [:pur/prediction-error {:optional true} [:maybe map?]]])

(def PAR
  [:map {:closed true}
   [:par/id [:string {:min 1}]]
   [:par/session-ref [:string {:min 1}]]
   [:par/what-worked {:optional true} [:maybe [:string {:min 1}]]]
   [:par/what-didnt {:optional true} [:maybe [:string {:min 1}]]]
   [:par/prediction-errors {:optional true} [:vector map?]]
   [:par/suggestions {:optional true} [:vector [:string {:min 1}]]]])

(def ProofPathEvent
  [:map {:closed true}
   [:gate/id [:enum :g5 :g4 :g3 :g2 :g1 :g0]]
   [:gate/record [:or TaskSpec Assignment PSR Artifact PUR PAR]]
   [:gate/at [:string {:min 1}]]])

(def ProofPath
  [:map {:closed true}
   [:path/id [:string {:min 1}]]
   [:events [:vector ProofPathEvent]]])

(defn validate!
  "Validate VALUE against SCHEMA, returning VALUE or throwing ex-info."
  [schema value]
  (if (m/validate schema value)
    value
    (throw (ex-info "Invalid evidence shape"
                    {:details (me/humanize (m/explain schema value))
                     :value value}))))

