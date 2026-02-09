(ns futon3.gate.observe
  "L1-observe: tension observer for the glacial loop.

  Patterns:
  - futon-theory/structural-tension-as-observation

  Theory grounding: [I2(Observation-action-asymmetry) I5(Model-adequacy)]

  Scans accumulated proof-paths for recurring structural tensions using
  three criteria from structural-tension-as-observation:

  1. Structural irritation — recurring gap-PSRs
  2. Pre-symbolic pressure — repeated early-gate failures
  3. Trans-situational reappearance — same pattern across contexts"
  (:require [futon3.gate.errors :as errors]
            [futon3.gate.shapes :as shapes]
            [futon3.gate.util :as u]
            [futon3b.query.relations :as relations]
            [clojure.string :as str]))

;;; ============================================================
;;; Fingerprinting
;;; ============================================================

(defn- fingerprint
  "Generate a dedup key from a string by normalizing whitespace and lowering case."
  [s]
  (when (string? s)
    (-> s str/lower-case str/trim (str/replace #"\s+" " "))))

;;; ============================================================
;;; Helpers
;;; ============================================================

(defn- extract-gap-psrs
  "Extract gap-PSR entries from a single proof-path map."
  [pp]
  (let [path-id (or (:path/id pp) "unknown")
        events (get-in pp [:proof-path :events] [])
        mission-ref (get-in pp [:evidence :task-spec :task/mission-ref] "unknown")]
    (->> events
         (filter #(= :g3 (:gate/id %)))
         (map :gate/record)
         (filter #(= :gap (:psr/type %)))
         (map (fn [psr]
                {:path-id path-id
                 :mission-ref mission-ref
                 :rationale (:psr/rationale psr)
                 :fingerprint (fingerprint (or (:psr/rationale psr) ""))})))))

(defn- extract-rejection
  "Extract a rejection entry from a proof-path if last event is in early-gates."
  [pp early-gates]
  (let [path-id (or (:path/id pp) "unknown")
        mission-ref (get-in pp [:evidence :task-spec :task/mission-ref] "unknown")
        events (get-in pp [:proof-path :events] [])
        last-event (last events)]
    (when (and last-event (early-gates (:gate/id last-event)))
      (let [gate-id (:gate/id last-event)
            err-key (get-in last-event [:gate/record :error/key])
            fp-suffix (if err-key (name err-key) (name gate-id))]
        {:path-id path-id
         :mission-ref mission-ref
         :gate-id gate-id
         :fingerprint (str (name gate-id) "/" fp-suffix)}))))

(defn- extract-pattern-uses
  "Extract selection-PSR entries from a single proof-path map."
  [pp]
  (let [path-id (or (:path/id pp) "unknown")
        mission-ref (get-in pp [:evidence :task-spec :task/mission-ref] "unknown")
        events (get-in pp [:proof-path :events] [])]
    (->> events
         (filter #(= :g3 (:gate/id %)))
         (map :gate/record)
         (filter #(= :selection (:psr/type %)))
         (keep (fn [psr]
                 (when (:psr/pattern-ref psr)
                   {:path-id path-id
                    :mission-ref mission-ref
                    :pattern-ref (:psr/pattern-ref psr)}))))))

(defn- grouped->tension
  "Convert a group of entries into a TensionObservation."
  [tension-type fp entries description]
  (let [path-ids (vec (distinct (map :path-id entries)))
        contexts (vec (distinct (map :mission-ref entries)))]
    (shapes/validate! shapes/TensionObservation
      {:tension/id (u/gen-id "tension")
       :tension/type tension-type
       :tension/evidence-refs path-ids
       :tension/frequency (count entries)
       :tension/contexts contexts
       :tension/description description
       :tension/fingerprint fp
       :tension/observed-at (u/now-iso)})))

;;; ============================================================
;;; Scan: Structural Irritation
;;; ============================================================

(defn scan-structural-irritation
  "Find recurring gap-PSRs in proof-paths.

  Groups proof-path events by gap PSR rationale (exact-match fingerprint),
  filters by frequency threshold (default 2+).

  Returns seq of TensionObservation maps."
  [proof-paths {:keys [min-frequency] :or {min-frequency 2}}]
  (let [gap-psrs (mapcat extract-gap-psrs proof-paths)
        grouped (group-by :fingerprint gap-psrs)]
    (->> grouped
         (filter (fn [[_fp entries]] (>= (count entries) min-frequency)))
         (map (fn [[fp entries]]
                (let [desc (str "Recurring gap-PSR: " (first (keep :rationale entries)))]
                  (grouped->tension :structural-irritation fp entries desc)))))))

;;; ============================================================
;;; Scan: Pre-Symbolic Pressure
;;; ============================================================

(defn scan-pre-symbolic-pressure
  "Find tasks that repeatedly fail at early gates (G5, G3).

  Groups proof-path rejection events by gate-id + error-key,
  filters by frequency threshold.

  Returns seq of TensionObservation maps."
  [proof-paths {:keys [min-frequency] :or {min-frequency 2}}]
  (let [early-gates #{:g5 :g3}
        rejections (keep #(extract-rejection % early-gates) proof-paths)
        grouped (group-by :fingerprint rejections)]
    (->> grouped
         (filter (fn [[_fp entries]] (>= (count entries) min-frequency)))
         (map (fn [[fp entries]]
                (let [desc (str "Repeated early-gate rejection: " fp)]
                  (grouped->tension :pre-symbolic-pressure fp entries desc)))))))

;;; ============================================================
;;; Scan: Trans-Situational Reappearance
;;; ============================================================

(defn scan-trans-situational
  "Find patterns/fixes that appear across different missions or contexts.

  Groups proof-path PSRs by pattern-ref, checks if they span 2+ distinct
  mission-refs.

  Returns seq of TensionObservation maps."
  [proof-paths {:keys [min-contexts] :or {min-contexts 2}}]
  (let [pattern-uses (mapcat extract-pattern-uses proof-paths)
        grouped (group-by :pattern-ref pattern-uses)]
    (->> grouped
         (filter (fn [[_ref entries]]
                   (>= (count (distinct (map :mission-ref entries))) min-contexts)))
         (map (fn [[ref entries]]
                (let [desc (str "Pattern used across contexts: " ref)]
                  (grouped->tension :trans-situational-reappearance
                                    (str "trans/" ref)
                                    entries desc)))))))

;;; ============================================================
;;; Public API
;;; ============================================================

(defn apply!
  "L1-observe: scan proof-paths for structural tensions.

  Input state has :ports with :I-tensions (proof-paths to analyze).
  If :I-tensions is not provided, loads from the proof-path store.

  Returns state with :evidence {:tensions [...TensionObservation...]}."
  [state]
  (let [proof-paths (or (get-in state [:ports :I-tensions])
                        (relations/load-proof-paths))
        opts (or (:opts state) {})]
    (cond
      (or (nil? proof-paths) (empty? proof-paths))
      (assoc state :result (errors/reject :l1/no-proof-paths))

      :else
      (let [irritations (scan-structural-irritation proof-paths opts)
            pressures (scan-pre-symbolic-pressure proof-paths opts)
            trans-sit (scan-trans-situational proof-paths opts)
            all-tensions (vec (concat irritations pressures trans-sit))
            ;; Proof-path events must carry typed evidence records (see shapes/ProofPathEvent).
            ;; Record each tension as its own event rather than emitting a summary map.
            observed-at (u/now-iso)
            events (mapv (fn [t]
                           {:gate/id :l1-observe
                            :gate/record t
                            :gate/at observed-at})
                         all-tensions)]
        (-> state
            (assoc-in [:evidence :tensions] all-tensions)
            (update :proof-path (fnil into []) events)
            (assoc :result {:ok true :tensions all-tensions}))))))
