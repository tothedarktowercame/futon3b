(ns futon3.gate.level1-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [futon3.gate.observe :as observe]
            [futon3.gate.canon :as canon]
            [futon3.gate.level1 :as level1]
            [futon3.gate.shapes :as shapes]
            [futon3.gate.pipeline :as pipeline]
            [futon3b.query.relations :as relations]
            [malli.core :as m]))

;;; ============================================================
;;; Hermetic temp-dir fixture (same pattern as pipeline_test.clj)
;;; ============================================================

(def ^:dynamic *test-proof-dir* nil)
(def ^:dynamic *test-library-dir* nil)
(def ^:dynamic *test-missions-file* nil)

(defn with-temp-dirs [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "futon3b-l1-proof-" (System/nanoTime)))
        lib (io/file (System/getProperty "java.io.tmpdir")
                     (str "futon3b-l1-lib-" (System/nanoTime)))
        missions (io/file (System/getProperty "java.io.tmpdir")
                          (str "futon3b-l1-missions-" (System/nanoTime) ".edn"))]
    (.mkdirs dir)
    (.mkdirs lib)
    (try
      ;; Hermetic pattern library with one pattern
      (let [coord (io/file lib "coordination")]
        (.mkdirs coord)
        (spit (io/file coord "mandatory-psr.flexiarg")
              (str "@flexiarg coordination/mandatory-psr\n"
                   "@title Mandatory PSR (Test Fixture)\n"
                   "! conclusion:\n  ok\n")))
      ;; Hermetic missions registry
      (spit missions
            (pr-str {"M-coordination-rewrite"
                     {:mission/id "M-coordination-rewrite"
                      :mission/state :active}
                     "M-other-mission"
                     {:mission/id "M-other-mission"
                      :mission/state :active}}))
      (with-redefs [relations/proof-path-dir (constantly (str dir))
                    relations/library-roots (constantly [(str lib)])
                    relations/missions-file (constantly (str missions))]
        (binding [*test-proof-dir* dir
                  *test-library-dir* lib
                  *test-missions-file* missions]
          (relations/invalidate-pattern-cache!)
          (f)))
      (finally
        (doseq [f (reverse (file-seq dir))] (.delete f))
        (doseq [f (reverse (file-seq lib))] (.delete f))
        (.delete missions)))))

(use-fixtures :each with-temp-dirs)

;;; ============================================================
;;; Helper: build proof-path data
;;; ============================================================

(defn- make-proof-path
  "Build a minimal proof-path map for testing."
  [{:keys [path-id mission-ref events]}]
  {:path/id (or path-id (str "path-" (System/nanoTime)))
   :proof-path {:path/id (or path-id (str "path-" (System/nanoTime)))
                :events (vec events)}
   :evidence {:task-spec {:task/id (str "T-" (System/nanoTime))
                          :task/mission-ref (or mission-ref "M-coordination-rewrite")}}
   :persisted-at (str (java.time.Instant/now))})

(defn- gap-psr-event [rationale]
  {:gate/id :g3
   :gate/record {:psr/id (str "psr-" (System/nanoTime))
                 :psr/task-id (str "T-" (System/nanoTime))
                 :psr/type :gap
                 :psr/rationale rationale}
   :gate/at (str (java.time.Instant/now))})

(defn- selection-psr-event [pattern-ref]
  {:gate/id :g3
   :gate/record {:psr/id (str "psr-" (System/nanoTime))
                 :psr/task-id (str "T-" (System/nanoTime))
                 :psr/type :selection
                 :psr/pattern-ref pattern-ref}
   :gate/at (str (java.time.Instant/now))})

(defn- rejection-event [gate-id]
  {:gate/id gate-id
   :gate/record {:error/key (keyword (name gate-id) "test-error")}
   :gate/at (str (java.time.Instant/now))})

;;; ============================================================
;;; Observer Tests
;;; ============================================================

(deftest observer-finds-structural-irritation
  (testing "3 gap-PSR proof-paths with same fingerprint → at least 1 structural-irritation tension"
    (let [pp1 (make-proof-path {:events [(gap-psr-event "No pattern for deployment validation")]})
          pp2 (make-proof-path {:events [(gap-psr-event "No pattern for deployment validation")]})
          pp3 (make-proof-path {:events [(gap-psr-event "No pattern for deployment validation")]})
          state {:ports {:I-tensions [pp1 pp2 pp3]}
                 :evidence {}
                 :proof-path []}
          result (observe/apply! state)
          tensions (get-in result [:evidence :tensions])
          irritations (filter #(= :structural-irritation (:tension/type %)) tensions)]
      (is (true? (get-in result [:result :ok])))
      (is (= 1 (count irritations))
          "Should find exactly 1 structural-irritation tension")
      (let [tension (first irritations)]
        (is (>= (:tension/frequency tension) 3))
        (is (string? (:tension/fingerprint tension)))
        (is (string? (:tension/description tension)))))))

(deftest observer-finds-pre-symbolic-pressure
  (testing "3 proof-paths with G5 rejections → 1 tension observation"
    (let [pp1 (make-proof-path {:events [(rejection-event :g5)]})
          pp2 (make-proof-path {:events [(rejection-event :g5)]})
          pp3 (make-proof-path {:events [(rejection-event :g5)]})
          state {:ports {:I-tensions [pp1 pp2 pp3]}
                 :evidence {}
                 :proof-path []}
          result (observe/apply! state)]
      (is (true? (get-in result [:result :ok])))
      (let [pressures (->> (get-in result [:evidence :tensions])
                           (filter #(= :pre-symbolic-pressure (:tension/type %))))]
        (is (= 1 (count pressures)))
        (when (seq pressures)
          (is (>= (:tension/frequency (first pressures)) 3)))))))

(deftest observer-finds-trans-situational
  (testing "Same pattern used across 2 missions → 1 tension observation"
    (let [pp1 (make-proof-path {:mission-ref "M-coordination-rewrite"
                                :events [(selection-psr-event "coordination/mandatory-psr")]})
          pp2 (make-proof-path {:mission-ref "M-other-mission"
                                :events [(selection-psr-event "coordination/mandatory-psr")]})
          state {:ports {:I-tensions [pp1 pp2]}
                 :evidence {}
                 :proof-path []}
          result (observe/apply! state)]
      (is (true? (get-in result [:result :ok])))
      (let [trans (->> (get-in result [:evidence :tensions])
                       (filter #(= :trans-situational-reappearance (:tension/type %))))]
        (is (= 1 (count trans)))
        (when (seq trans)
          (is (>= (count (:tension/contexts (first trans))) 2)))))))

(deftest observer-returns-empty-on-no-tensions
  (testing "1 clean proof-path → {:ok true :tensions []}"
    (let [pp (make-proof-path {:events [(selection-psr-event "coordination/mandatory-psr")]})
          state {:ports {:I-tensions [pp]}
                 :evidence {}
                 :proof-path []}
          result (observe/apply! state)]
      (is (true? (get-in result [:result :ok])))
      ;; With only 1 proof-path, no threshold can be met for irritation/pressure
      ;; Trans-situational needs 2+ contexts, also won't trigger with 1 context
      (is (empty? (get-in result [:evidence :tensions]))))))

(deftest observer-rejects-empty-store
  (testing "No proof-paths → :l1/no-proof-paths"
    (let [state {:ports {:I-tensions []}
                 :evidence {}
                 :proof-path []}
          result (observe/apply! state)]
      (is (false? (get-in result [:result :ok])))
      (is (= :l1/no-proof-paths (get-in result [:result :error/key]))))))

;;; ============================================================
;;; Canonicalizer Tests
;;; ============================================================

(deftest canonicalizer-names-tension
  (testing "Tension → CanonizationEvent with :naming phase"
    (let [tension {:tension/id "tension-test-1"
                   :tension/type :structural-irritation
                   :tension/evidence-refs ["path-1" "path-2" "path-3"]
                   :tension/frequency 3
                     :tension/contexts ["M-coordination-rewrite" "M-other"]
                     :tension/description "Recurring gap: deployment validation"
                     :tension/fingerprint "no pattern for deployment validation"
                     :tension/observed-at (str (java.time.Instant/now))}
            naming (canon/name-tension tension)]
        (is (= :naming (:canon/phase naming)))
        (is (string? (:canon/pattern-id naming)))
        (is (str/starts-with? (:canon/pattern-id naming) "coordination/"))
        (is (= "tension-test-1" (:canon/tension-ref naming))))))

(deftest canonicalizer-selects-by-threshold
  (testing "Below-threshold tension filtered out"
    (let [below {:tension/id "tension-below"
                 :tension/type :structural-irritation
                 :tension/evidence-refs ["path-1"]
                 :tension/frequency 1
                 :tension/contexts ["M-coordination-rewrite"]
                 :tension/description "Low frequency gap"
                 :tension/fingerprint "low-freq"
                 :tension/observed-at (str (java.time.Instant/now))}
          naming (canon/name-tension below)
          selection (canon/select-candidate below naming {})]
      (is (nil? selection)
          "Below-threshold tension should not be selected")))

  (testing "Above-threshold tension is selected"
    (let [above {:tension/id "tension-above"
                 :tension/type :structural-irritation
                 :tension/evidence-refs ["path-1" "path-2" "path-3"]
                 :tension/frequency 5
                 :tension/contexts ["M-coordination-rewrite" "M-other"]
                 :tension/description "High frequency gap"
                 :tension/fingerprint "high-freq"
                 :tension/observed-at (str (java.time.Instant/now))}
          naming (canon/name-tension above)
          selection (canon/select-candidate above naming {})]
      (is (some? selection))
      (is (= :selection (:canon/phase selection))))))

(deftest canonicalizer-writes-flexiarg
  (testing "Above-threshold tension → new .flexiarg file on disk"
    (let [tension {:tension/id "tension-write-test"
                   :tension/type :structural-irritation
                   :tension/evidence-refs ["path-1" "path-2" "path-3"]
                   :tension/frequency 5
                   :tension/contexts ["M-coordination-rewrite" "M-other"]
                   :tension/description "Needs deployment validation pattern"
                   :tension/fingerprint "needs deployment validation pattern"
                   :tension/observed-at (str (java.time.Instant/now))}
          naming (canon/name-tension tension)
          selection (canon/select-candidate tension naming {})
          _ (is (some? selection) "Should be selected")
          canalization (canon/canalize! tension selection)]
        (is (= :canalization (:canon/phase canalization)))
        ;; Verify file was written
        (let [pattern-id (:canon/pattern-id selection)
              parts (str/split pattern-id #"/")
              dir-name (first parts)
              file-name (str (last parts) ".flexiarg")
              written-file (io/file *test-library-dir* dir-name file-name)]
          (is (.exists written-file)
              (str "Flexiarg file should exist at " written-file))
          (when (.exists written-file)
            (let [content (slurp written-file)]
              (is (str/includes? content "@flexiarg"))
              (is (str/includes? content "deployment validation"))))))))

;;; ============================================================
;;; Full Loop Tests
;;; ============================================================

(deftest full-loop-round-trip
  (testing "Integration: gap-PSRs → observe → canonize (required) → new flexiarg → G3 accepts"
    ;; Step 1: Create proof-paths with recurring gap-PSRs via the pipeline
    (let [ok-exec (fn [_] {:artifact/type :demo/artifact :artifact/ref {:demo "ok"} :exec/success? true})
          ok-sink (fn [{:keys [proof-path]}] {:ok true :path/id (:path/id proof-path)})
          base-input {:I-missions {"M-coordination-rewrite"
                                   {:mission/id "M-coordination-rewrite"
                                    :mission/state :active}
                                   "M-other-mission"
                                   {:mission/id "M-other-mission"
                                    :mission/state :active}}
                      :I-patterns {:patterns/ids #{"coordination/mandatory-psr"}}
                      :I-registry {:agents {"codex-1" {:capabilities [:coordination/execute :coordination/review]}}}
                      :I-environment {}
                      :opts {:budget/ms 200}}

          ;; Create 3 proof-paths with gap-PSRs across 2 mission contexts so
          ;; the canonicalizer selection threshold (contexts>=2, freq>=3) is met.
          gap-paths (vec
                      (for [i (range 3)]
                        (let [task-id (str "T-gap-" i)
                              mission-ref (if (zero? (mod i 2)) "M-coordination-rewrite" "M-other-mission")
                              out (pipeline/run
                                    (assoc base-input
                                      :I-request {:task {:task/id task-id
                                                         :task/mission-ref mission-ref
                                                         :task/intent "deploy validation"
                                                         :task/success-criteria [:demo/ok]
                                                         :task/required-capabilities [:coordination/execute]}
                                                   :agent-id "codex-1"
                                                   :psr {:psr/type :gap
                                                         :gap? true
                                                         :psr/rationale "No pattern for deployment validation"}
                                                   :exec/fn ok-exec
                                                   :par {:par/session-ref (str "S-gap-" i)
                                                         :par/what-worked "partial"
                                                         :par/what-didnt "no matching pattern"
                                                         :par/prediction-errors []
                                                         :par/suggestions []}
                                                   :evidence/sink ok-sink}))]
                          (is (true? (:ok out)) (str "Pipeline should succeed for gap " i))
                          ;; Build proof-path data as if loaded from store
                          {:path/id (get-in out [:O-proof-path :path/id])
                           :proof-path (:O-proof-path out)
                           :evidence (:O-evidence out)
                           :persisted-at (str (java.time.Instant/now))})))
          ;; Step 2: Run L1 with these proof-paths
          l1-result (level1/run {:I-tensions gap-paths
                                 :I-patterns {}
                                 :opts {}})]
      (is (true? (:ok l1-result))
          "L1 should succeed")
      (is (pos? (count (:observations l1-result)))
          "Should find at least one tension")

      ;; Step 3: Canonization must happen and produce at least one canalization event.
      (let [canalizations (->> (:canonizations l1-result)
                               (filter #(= :canalization (:canon/phase %)))
                               vec)]
        (is (seq canalizations) "L1 should canalize at least one new pattern.")
        (let [new-pattern-id (:canon/pattern-id (first canalizations))]
          (is (string? new-pattern-id))
          ;; Verify G3 can now find the new pattern
          (relations/invalidate-pattern-cache!)
          (is (relations/pattern-exists? new-pattern-id)
              (str "New pattern " new-pattern-id " should exist in library"))

          ;; Step 4: Run pipeline with the new pattern (force real library lookup)
          (let [out (pipeline/run
                      (assoc base-input
                        :I-patterns nil
                        :I-request {:task {:task/id "T-uses-new-pattern"
                                           :task/mission-ref "M-coordination-rewrite"
                                           :task/intent "deploy with new pattern"
                                           :task/success-criteria [:demo/ok]
                                           :task/required-capabilities [:coordination/execute]}
                                   :agent-id "codex-1"
                                   :psr {:psr/type :selection
                                         :psr/pattern-ref new-pattern-id
                                         :psr/rationale "Using canonized pattern"}
                                   :exec/fn ok-exec
                                   :par {:par/session-ref "S-new-pattern"
                                         :par/what-worked "Used new pattern"
                                         :par/what-didnt "n/a"
                                         :par/prediction-errors []
                                         :par/suggestions []}
                                   :evidence/sink ok-sink}))]
      (is (true? (:ok out))
          (str "Pipeline should succeed with new pattern " new-pattern-id))))))))

;;; ============================================================
;;; Shape Validation Tests
;;; ============================================================

(deftest shapes-validate
  (testing "TensionObservation passes Malli validation"
    (let [tension {:tension/id "t-1"
                   :tension/type :structural-irritation
                   :tension/evidence-refs ["path-1"]
                   :tension/frequency 2
                   :tension/contexts ["M-test"]
                   :tension/description "Test tension"
                   :tension/fingerprint "test-fp"
                   :tension/observed-at "2026-02-09T00:00:00Z"}]
      (is (m/validate shapes/TensionObservation tension))))

  (testing "CanonizationEvent passes Malli validation"
    (let [event {:canon/id "c-1"
                 :canon/tension-ref "t-1"
                 :canon/phase :naming
                 :canon/pattern-id "coordination/test-pattern"
                 :canon/action :create
                 :canon/rationale "Test canonization"
                 :canon/at "2026-02-09T00:00:00Z"}]
      (is (m/validate shapes/CanonizationEvent event)))))
