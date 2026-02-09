(ns futon3.gate.errors
  "Gate rejection catalog and helpers.

  This is the single source of truth for:
  - error keys
  - their owning gate
  - HTTP status mapping
  - machine-readable shape of rejections"
  (:require [clojure.string :as str]))

(def catalog
  {;; G5
   :g5/missing-mission-ref {:gate :g5 :http/status 400 :message "Task has no mission reference."}
   :g5/missing-success-criteria {:gate :g5 :http/status 400 :message "Task has no success criteria."}
   :g5/mission-not-active {:gate :g5 :http/status 400 :message "Referenced mission is not active."}

   ;; G4
   :g4/agent-not-registered {:gate :g4 :http/status 403 :message "Agent is not registered."}
   :g4/capability-mismatch {:gate :g4 :http/status 403 :message "Agent lacks required capabilities."}
   :g4/already-assigned {:gate :g4 :http/status 409 :message "Task is already assigned to another agent."}

   ;; G3
   :g3/no-psr {:gate :g3 :http/status 400 :message "No PSR provided and no gap declaration."}
   :g3/pattern-not-found {:gate :g3 :http/status 404 :message "Referenced pattern not found in library."}

   ;; G2
   :g2/budget-exceeded {:gate :g2 :http/status 408 :message "Execution budget exceeded."}
   :g2/artifact-unregistered {:gate :g2 :http/status 500 :message "Produced artifact was not registered."}

   ;; G1
   :g1/no-pur {:gate :g1 :http/status 400 :message "No PUR produced for PSR."}
   :g1/criteria-not-met {:gate :g1 :http/status 422 :message "Success criteria evaluation failed."}

   ;; G0
   :g0/durability-failed {:gate :g0 :http/status 503 :message "Evidence durability check failed."}
   :g0/no-par {:gate :g0 :http/status 400 :message "No PAR produced before session close."}

   ;; L1
   :l1/no-proof-paths     {:gate :l1-observe :http/status 422 :message "No proof-paths available for tension analysis."}
   :l1/no-tensions-found  {:gate :l1-observe :http/status 200 :message "No structural tensions detected."}
   :l1/canon-no-candidate {:gate :l1-canon   :http/status 422 :message "No tension meets canonicalization threshold."}
   :l1/write-failed       {:gate :l1-canon   :http/status 503 :message "Failed to write canonized pattern to library."}
   :l1/pattern-exists     {:gate :l1-canon   :http/status 409 :message "Pattern already exists in library; canalization refused to overwrite."}})

(defn- normalize-gate-id [gate]
  (cond
    (nil? gate) nil
    (keyword? gate) gate
    (string? gate) (keyword (str/lower-case gate))
    :else (keyword (str gate))))

(defn reject
  "Build a structured gate rejection.

  Shape:
  {:ok false
   :type :gate/reject
   :gate/id :g3
   :error/key :g3/no-psr
   :http/status 400
   :message \"...\"
   :details {...}}

  NOTE: Callers should short-circuit on {:ok false ...}."
  ([error-key] (reject error-key nil))
  ([error-key details]
   (let [{:keys [gate message] :as entry} (get catalog error-key)
         status (:http/status entry)]
     (when-not entry
       (throw (ex-info "Unknown gate error key" {:error/key error-key})))
     {:ok false
      :type :gate/reject
      :gate/id (normalize-gate-id gate)
      :error/key error-key
      :http/status status
      :message message
      :details (or details {})})))
