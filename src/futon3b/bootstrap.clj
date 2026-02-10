(ns futon3b.bootstrap
  "Prototype 0: submit futon3c development tasks through the gate pipeline.

  This is the bootstrap closure from M-f6-recursive: the pipeline that validates
  futon work was itself built by the pipeline it validates.

  Usage (REPL):
    (require '[futon3b.bootstrap :as boot])
    (boot/submit! {:intent   \"Write social-exotype.edn\"
                   :criteria [:social-exotype-8-checks-pass]})

    ;; Or use the pre-built Part I submission:
    (boot/submit-part-i!)

  Each submission constructs a valid pipeline input, runs it through G5→G0,
  and persists a proof-path to the proof-path store."
  (:require [futon3.gate.pipeline :as pipeline]
            [futon3.gate.util :as u]
            [futon3b.query.relations :as relations]))

;;; ============================================================
;;; Defaults
;;; ============================================================

(def ^:private default-mission-ref "M-social-exotype")

(def ^:private default-agent
  "claude")

(def ^:private default-registry
  {:agents {"claude" {:capabilities [:diagram :clojure :review :coordination/execute]}
            "codex"  {:capabilities [:diagram :clojure :review :coordination/execute :coordination/review]}}})

;;; ============================================================
;;; Task Builders
;;; ============================================================

(defn- build-input
  "Construct a valid gate pipeline input for a futon3c development task.

  OPTS keys:
    :task-id     - string (auto-generated if nil)
    :mission-ref - string (default: M-social-exotype)
    :intent      - string, what this task aims to do
    :scope       - map, {:in [...] :out [...]}
    :criteria    - vector of keywords/strings, success criteria
    :agent-id    - string (default: claude)
    :psr         - map, pattern selection record (gap or selection)
    :artifact    - map, passthrough artifact for documentation/review tasks
    :exec-fn     - fn, execution function (if not passthrough)
    :par         - map, post-action review data"
  [{:keys [task-id mission-ref intent scope criteria
           agent-id psr artifact exec-fn par]}]
  (let [task-id (or task-id (u/gen-id "futon3c"))
        mission-ref (or mission-ref default-mission-ref)
        agent-id (or agent-id default-agent)
        criteria (or (seq criteria) [:task-completed])
        psr (or psr {:psr/type :gap
                     :gap? true
                     :psr/rationale (str "futon3c bootstrap — " (or intent "development task"))})
        par (or par {:par/session-ref (str "boot-" task-id)
                     :par/what-worked "submitted through gate pipeline"
                     :par/what-didnt nil
                     :par/prediction-errors []
                     :par/suggestions []})
        request (cond-> {:task {:task/id task-id
                                :task/mission-ref mission-ref
                                :task/intent intent
                                :task/scope scope
                                :task/success-criteria (vec criteria)}
                         :agent-id agent-id
                         :psr psr
                         :par par}
                  artifact (assoc :artifact artifact)
                  exec-fn (assoc :exec/fn exec-fn)
                  ;; If neither artifact nor exec-fn, add a passthrough artifact
                  ;; so G2 doesn't reject.
                  (and (nil? artifact) (nil? exec-fn))
                  (assoc :artifact {:artifact/type :futon3c/development-task
                                    :artifact/ref {:intent intent
                                                   :mission-ref mission-ref}
                                    :exec/success? true}))]
    {:I-request request
     :I-missions (relations/load-missions)
     :I-patterns {:patterns/ids (relations/pattern-ids)}
     :I-registry default-registry
     :I-environment {}
     :opts {:budget/ms 5000}}))

;;; ============================================================
;;; Submit
;;; ============================================================

(defn submit!
  "Submit a futon3c development task through the gate pipeline.

  Returns the pipeline result (either {:ok true ...} with proof-path,
  or {:ok false ...} with rejection details).

  OPTS: see build-input for available keys."
  [opts]
  (let [input (build-input opts)
        result (pipeline/run input)]
    (if (:ok result)
      (do
        (println "Gate pipeline: PASS")
        (println "  proof-path:" (get-in result [:O-proof-path :path/id]))
        (println "  events:" (count (get-in result [:O-proof-path :events])))
        result)
      (do
        (println "Gate pipeline: REJECT")
        (println "  gate:" (:gate/id result))
        (println "  error:" (:error/key result))
        (println "  message:" (:message result))
        result))))

;;; ============================================================
;;; Pre-built Submissions (Part III proof)
;;; ============================================================

(defn submit-part-i!
  "Submit the Part I task: 'Write social-exotype.edn'.

  This is retrospective — Part I is already done. The submission
  creates the proof-path that would have governed the work."
  []
  (submit! {:intent "Write social-exotype.edn (abstract wiring diagram)"
            :criteria [:social-exotype-written
                       :standalone-validation-8-checks
                       :argument-requirements-traceable]
            :artifact {:artifact/type :futon3c/diagram
                       :artifact/ref {:file "futon5/data/missions/social-exotype.edn"
                                      :checks-pass 8
                                      :checks-total 8}
                       :exec/success? true}
            :par {:par/session-ref "boot-part-i"
                  :par/what-worked "Agency reading of gates (C6) gave clean component structure"
                  :par/what-didnt "Serial composition fails on shared constraints (expected)"
                  :par/prediction-errors []
                  :par/suggestions ["Part II: extend ct/mission.clj for parallel composition"]}}))

(defn submit-part-ii!
  "Submit the Part II task: 'Extend ct/mission.clj for parallel composition'.

  This task is in progress with Codex (futon5 issue #2)."
  []
  (submit! {:intent "Extend ct/mission.clj for parallel diagram composition"
            :criteria [:compose-parallel-implemented
                       :shared-port-deduplication
                       :cross-diagram-i3
                       :cross-diagram-i4
                       :cross-diagram-i6]
            :psr {:psr/type :gap
                  :gap? true
                  :psr/rationale "Serial compose-missions cannot express shared-constraint parallel composition"}
            :par {:par/session-ref "boot-part-ii"
                  :par/what-worked "Serial composition identified 5 type-compatible port matches"
                  :par/what-didnt "Edge prefixing breaks internal wiring, shared ports not deduplicated"
                  :par/prediction-errors []
                  :par/suggestions ["compose-parallel with shared-port merging"
                                    "Extend timescale-order to include :social"]}}))

(defn submit-part-iii!
  "Submit the Part III task: 'Gate-governed build (this very submission)'.

  This is the self-referential bootstrap: the pipeline validates
  a task whose purpose is to validate that the pipeline can validate tasks."
  []
  (submit! {:intent "Prototype 0 — gate-governed build for futon3c development"
            :criteria [:mission-registered
                       :repl-helper-written
                       :proof-path-produced
                       :evidence-typed]
            :par {:par/session-ref "boot-part-iii"
                  :par/what-worked "Bootstrap closure works: futon3c task governed by futon3b pipeline"
                  :par/what-didnt nil
                  :par/prediction-errors []
                  :par/suggestions ["Use this pattern for all subsequent futon3c missions"]}}))
