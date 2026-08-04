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
   ;; REVERTED to permissive 2026-08-04. Tightening this to
   ;; [:vector [:map [:pattern-id ...]]] was correct in spirit -- `map?`
   ;; validates nothing, and the house writer uses :pattern-id while a runner
   ;; wrote :pattern/id -- but it BROKE A LIVE SYSTEM. Agents hold PSRs in
   ;; discipline-state that were validated under the old schema; pur-update
   ;; replays that PSR into the proof path at :g3, where the tightened schema
   ;; rejected it. A write-time schema tightening retroactively invalidates
   ;; in-flight state that is re-validated later. Re-tighten only with a
   ;; migration for held state, not mid-session.
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

(def TensionObservation
  [:map {:closed true}
   [:tension/id [:string {:min 1}]]
   [:tension/type [:enum :structural-irritation :pre-symbolic-pressure :trans-situational-reappearance]]
   [:tension/evidence-refs [:vector [:string {:min 1}]]]
   [:tension/frequency :int]
   [:tension/contexts [:vector [:string {:min 1}]]]
   [:tension/description [:string {:min 1}]]
   [:tension/fingerprint [:string {:min 1}]]
   [:tension/observed-at [:string {:min 1}]]])

(def CanonizationEvent
  [:map {:closed true}
   [:canon/id [:string {:min 1}]]
   [:canon/tension-ref [:string {:min 1}]]
   [:canon/phase [:enum :naming :selection :canalization]]
   [:canon/pattern-id [:string {:min 1}]]
   [:canon/action [:enum :create :update :deprecate]]
   [:canon/rationale [:string {:min 1}]]
   [:canon/at [:string {:min 1}]]])

(def GateRejection
  "Structured gate rejection record, suitable for inclusion in a proof-path."
  [:map {:closed true}
   [:type [:enum :gate/reject]]
   [:error/key :keyword]
   [:http/status :int]
   [:message [:string {:min 1}]]
   [:details map?]])

(def ProofPathEvent
  [:map {:closed true}
   [:gate/id [:enum :g5 :g4 :g3 :g2 :g1 :g0 :l1-observe :l1-canon]]
   [:gate/record [:or TaskSpec Assignment PSR Artifact PUR PAR TensionObservation CanonizationEvent GateRejection]]
   [:gate/at [:string {:min 1}]]])

(def ProofPath
  [:map {:closed true}
   [:path/id [:string {:min 1}]]
   [:events [:vector ProofPathEvent]]])

;; The primary record written at each gate. Every gate also admits a
;; GateRejection, identified by {:type :gate/reject}; see
;; `record-schema-for-event`. This map is intentionally diagnostic metadata:
;; ProofPathEvent retains its existing union, so this change does not reject
;; any gate/record pairing that was previously accepted.
(def gate-record-types
  {:g5 {:record-type :TaskSpec :schema TaskSpec}
   :g4 {:record-type :Assignment :schema Assignment}
   :g3 {:record-type :PSR :schema PSR}
   :g2 {:record-type :Artifact :schema Artifact}
   :g1 {:record-type :PUR :schema PUR}
   :g0 {:record-type :PAR :schema PAR}
   :l1-observe {:record-type :TensionObservation :schema TensionObservation}
   :l1-canon {:record-type :CanonizationEvent :schema CanonizationEvent}})

(defn- record-schema-for-event
  [{:gate/keys [id record]}]
  (if (= :gate/reject (:type record))
    {:record-type :GateRejection :schema GateRejection}
    (get gate-record-types id)))

(defn- schema-name
  [schema]
  (cond
    (= schema TaskSpec) :TaskSpec
    (= schema Assignment) :Assignment
    (= schema PSR) :PSR
    (= schema Artifact) :Artifact
    (= schema PUR) :PUR
    (= schema PAR) :PAR
    (= schema TensionObservation) :TensionObservation
    (= schema CanonizationEvent) :CanonizationEvent
    (= schema GateRejection) :GateRejection
    (= schema ProofPathEvent) :ProofPathEvent
    (= schema ProofPath) :ProofPath
    :else :unknown))

(defn- schema-error
  [schema value]
  {:schema (schema-name schema)
   :details (me/humanize (m/explain schema value))
   :value value})

(defn- event-error
  [event]
  (if-let [{:keys [record-type schema]} (record-schema-for-event event)]
    (if-not (m/validate schema (:gate/record event))
      (assoc (schema-error schema (:gate/record event))
             :gate/id (:gate/id event)
             :record-type record-type)
      (schema-error ProofPathEvent event))
    (schema-error ProofPathEvent event)))

(defn- proof-path-error
  [proof-path]
  (if (vector? (:events proof-path))
    (if-let [[event-index event]
             (first (keep-indexed
                     (fn [idx candidate]
                       (when-not (m/validate ProofPathEvent candidate)
                         [idx candidate]))
                     (:events proof-path)))]
      (assoc (event-error event) :event-index event-index)
      (schema-error ProofPath proof-path))
    (schema-error ProofPath proof-path)))

(defn validate!
  "Validate VALUE against SCHEMA, returning VALUE or throwing ex-info."
  [schema value]
  (if (m/validate schema value)
    value
    (let [error-data (cond
                       (= schema ProofPath) (proof-path-error value)
                       (= schema ProofPathEvent) (event-error value)
                       :else (schema-error schema value))]
      (throw (ex-info (str "Invalid " (name (:schema error-data)) " evidence shape")
                      error-data)))))
