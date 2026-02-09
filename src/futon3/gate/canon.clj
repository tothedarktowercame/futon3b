(ns futon3.gate.canon
  "L1-canon: canonicalize observed tensions into library patterns.

  Three-step Baldwin cycle from futon-theory/retroactive-canonicalization:

  1. NAMING    — recurring practice gets a stable name
  2. SELECTION — named practice shows trans-situational reappearance
  3. CANALIZATION — selected practice constrains future work

  Theory grounding: [A3(Evidence-driven) I5(Model-adequacy)]"
  (:require [futon3.gate.errors :as errors]
            [futon3.gate.shapes :as shapes]
            [futon3.gate.util :as u]
            [futon3b.query.relations :as relations]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;;; ============================================================
;;; Step 1: NAMING
;;; ============================================================

(defn name-tension
  "Take a TensionObservation and produce a candidate pattern name.

  Uses the tension fingerprint to generate a pattern-id like
  coordination/<fingerprint-slug>. Returns a CanonizationEvent
  with :canon/phase :naming."
  [tension]
  (let [fp (:tension/fingerprint tension)
        slug (-> (or fp "unknown")
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-|-$" "")
                 (->> (take 40) (apply str)))
        pattern-id (str "coordination/" slug)]
    (shapes/validate! shapes/CanonizationEvent
      {:canon/id (u/gen-id "canon")
       :canon/tension-ref (:tension/id tension)
       :canon/phase :naming
       :canon/pattern-id pattern-id
       :canon/action :create
       :canon/rationale (str "Named from tension: " (:tension/description tension))
       :canon/at (u/now-iso)})))

;;; ============================================================
;;; Step 2: SELECTION
;;; ============================================================

(defn select-candidate
  "Filter named tensions by canonicalization threshold.

  Threshold: frequency >= 3, contexts >= 2, evidence-refs >= 2.
  Returns CanonizationEvent with :canon/phase :selection, or nil
  if the tension does not meet threshold."
  [tension naming-event {:keys [min-frequency min-contexts min-evidence]
                         :or {min-frequency 3 min-contexts 2 min-evidence 2}}]
  (when (and (>= (:tension/frequency tension) min-frequency)
             (>= (count (:tension/contexts tension)) min-contexts)
             (>= (count (:tension/evidence-refs tension)) min-evidence))
    (shapes/validate! shapes/CanonizationEvent
      {:canon/id (u/gen-id "canon")
       :canon/tension-ref (:tension/id tension)
       :canon/phase :selection
       :canon/pattern-id (:canon/pattern-id naming-event)
       :canon/action :create
       :canon/rationale (str "Selected: freq=" (:tension/frequency tension)
                             " contexts=" (count (:tension/contexts tension))
                             " evidence=" (count (:tension/evidence-refs tension)))
       :canon/at (u/now-iso)})))

;;; ============================================================
;;; Step 3: CANALIZATION
;;; ============================================================

(defn- tension->flexiarg-content
  "Generate minimal flexiarg content from a tension observation."
  [tension pattern-id]
  (str "@flexiarg " pattern-id "\n"
       "@title " (:tension/description tension) "\n"
       "@keywords " (str/join " " (map #(str "#" %) (:tension/contexts tension))) "\n"
       "@theory-grounding [A3(Evidence-driven) I5(Model-adequacy)]\n"
       "\n"
       "IF a task encounters this recurring structural tension:\n"
       "  " (:tension/description tension) "\n"
       "  (observed " (:tension/frequency tension) " times across "
       (count (:tension/contexts tension)) " contexts)\n"
       "\n"
       "THEN apply this canonized resolution.\n"
       "\n"
       "! conclusion:\n"
       "  Canonized from tension " (:tension/id tension)
       " via Baldwin cycle (L1 glacial loop).\n"
       "  Evidence: " (str/join ", " (:tension/evidence-refs tension)) "\n"))

(defn canalize!
  "Write a new flexiarg to the library.

  Generates minimal flexiarg content from the tension observation.
  Writes to the first library root from relations/library-roots.
  Refuses to overwrite an existing pattern — returns a structured error
  via :l1/pattern-exists if the pattern-id already exists in the library.
  Returns CanonizationEvent with :canon/phase :canalization."
  [tension selection-event]
  (let [pattern-id (:canon/pattern-id selection-event)
        content (tension->flexiarg-content tension pattern-id)
        roots (relations/library-roots)
        root (first roots)]
    (cond
      (nil? root)
      (throw (ex-info "No library root available" {:pattern-id pattern-id}))

      (relations/pattern-exists? pattern-id)
      (throw (ex-info (str "Pattern already exists: " pattern-id)
                       {:pattern-id pattern-id
                        :error/key :l1/pattern-exists}))

      :else
      (let [parts (str/split pattern-id #"/")
            dir-name (if (> (count parts) 1) (first parts) "coordination")
            file-name (str (last parts) ".flexiarg")
            dir (io/file root dir-name)
            file (io/file dir file-name)]
        ;; Double-check: refuse to clobber an existing file even if cache missed it.
        (when (.exists file)
          (throw (ex-info (str "Flexiarg file already exists: " file)
                          {:pattern-id pattern-id
                           :file (str file)
                           :error/key :l1/pattern-exists})))
        (try
          (.mkdirs dir)
          (spit file content)
          (relations/invalidate-pattern-cache!)
          (shapes/validate! shapes/CanonizationEvent
            {:canon/id (u/gen-id "canon")
             :canon/tension-ref (:tension/id tension)
             :canon/phase :canalization
             :canon/pattern-id pattern-id
             :canon/action :create
             :canon/rationale (str "Wrote " file-name " to " root)
             :canon/at (u/now-iso)})
          (catch Exception e
            (throw (ex-info "Failed to write flexiarg"
                            {:pattern-id pattern-id
                             :file (str file)
                             :error (.getMessage e)}))))))))

;;; ============================================================
;;; Public API
;;; ============================================================

(defn apply!
  "L1-canon: canonicalize observed tensions into library patterns.

  Input state has :evidence {:tensions [...]}, :ports {:I-patterns config}.
  Returns state with :evidence {:canonizations [...CanonizationEvent...]}"
  [state]
  (let [tensions (get-in state [:evidence :tensions] [])
        opts (or (:opts state) {})]
    (if (empty? tensions)
      (assoc state :result {:ok true :canonizations []})
      (let [results (reduce
                      (fn [acc tension]
                        (let [naming (name-tension tension)
                              selection (select-candidate tension naming opts)]
                          (if selection
                            (try
                              (let [canalization (canalize! tension selection)]
                                (update acc :canonizations conj
                                        {:naming naming
                                         :selection selection
                                         :canalization canalization}))
                              (catch clojure.lang.ExceptionInfo e
                                (let [data (ex-data e)]
                                  (update acc :errors conj
                                          {:tension-id (:tension/id tension)
                                           :error/key (:error/key data)
                                           :details (dissoc data :error/key)
                                           :error (.getMessage e)})))
                              (catch Exception e
                                (update acc :errors conj
                                        {:tension-id (:tension/id tension)
                                         :error (.getMessage e)})))
                            (update acc :skipped conj
                                    {:tension-id (:tension/id tension)
                                     :reason "below threshold"}))))
                      {:canonizations [] :skipped [] :errors []}
                      tensions)
            canon-events (mapv :canalization (:canonizations results))
            all-events (vec (concat
                              (mapv :naming (:canonizations results))
                              (mapv :selection (:canonizations results))
                              canon-events))
            ;; Proof-path events must carry typed evidence records (see shapes/ProofPathEvent).
            ;; Record each canonization event rather than emitting a summary map.
            canon-at (u/now-iso)
             events (mapv (fn [e]
                           {:gate/id :l1-canon
                            :gate/record e
                            :gate/at canon-at})
                         all-events)]
        (if (and (empty? canon-events) (seq (:errors results)))
          (let [error-key (or (some :error/key (:errors results))
                              :l1/write-failed)]
            (assoc state :result (errors/reject error-key
                                                {:errors (:errors results)})))
          (-> state
              (assoc-in [:evidence :canonizations] all-events)
              (update :proof-path (fnil into []) events)
              (assoc :result {:ok true :canonizations all-events})))))))
