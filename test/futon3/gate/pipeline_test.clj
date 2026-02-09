(ns futon3.gate.pipeline-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [futon3.gate.pipeline :as pipeline]
            [futon3b.query.relations :as relations]))

(def base-missions
  {"M-coordination-rewrite"
   {:mission/id "M-coordination-rewrite"
    :mission/state :active}})

(def base-patterns
  {:patterns/ids #{"coordination/task-shape-validation"
                   "coordination/mandatory-psr"}})

(def base-registry
  {:agents {"codex-1" {:capabilities [:coordination/execute :coordination/review]}}})

(defn ok-sink [{:keys [proof-path]}]
  {:ok true :path/id (:path/id proof-path)})

(defn ok-exec [_]
  {:artifact/type :demo/artifact
   :artifact/ref {:demo "ok"}
   :exec/success? true})

(def base-input
  {:I-missions base-missions
   :I-patterns base-patterns
   :I-registry base-registry
   :I-environment {}
   :opts {:budget/ms 200}
   :I-request {:task {:task/id "T-1"
                      :task/mission-ref "M-coordination-rewrite"
                      :task/intent "wire gate scaffold"
                      :task/success-criteria [:demo/ok]
                      :task/required-capabilities [:coordination/execute]}
               :agent-id "codex-1"
               :psr {:psr/type :selection
                     :psr/pattern-ref "coordination/mandatory-psr"
                     :psr/rationale "Prototype 0"}
               :exec/fn ok-exec
               :par {:par/session-ref "S-1"
                     :par/what-worked "ok"
                     :par/what-didnt "n/a"
                     :par/prediction-errors []
                     :par/suggestions []}
               :evidence/sink ok-sink}})

(deftest rejects-missing-mission-ref
  (let [out (pipeline/run (assoc-in base-input [:I-request :task] (dissoc (get-in base-input [:I-request :task]) :task/mission-ref)))]
    (is (false? (:ok out)))
    (is (= :g5 (:gate/id out)))
    (is (= :g5/missing-mission-ref (:error/key out)))))

(deftest rejects-missing-success-criteria
  (let [out (pipeline/run (assoc-in base-input [:I-request :task :task/success-criteria] []))]
    (is (false? (:ok out)))
    (is (= :g5 (:gate/id out)))
    (is (= :g5/missing-success-criteria (:error/key out)))))

(deftest rejects-unregistered-agent
  (let [out (pipeline/run (assoc-in base-input [:I-request :agent-id] "nope"))]
    (is (false? (:ok out)))
    (is (= :g4 (:gate/id out)))
    (is (= :g4/agent-not-registered (:error/key out)))))

(deftest rejects-pattern-not-found
  (let [out (pipeline/run (assoc-in base-input [:I-request :psr :psr/pattern-ref] "missing/pattern"))]
    (is (false? (:ok out)))
    (is (= :g3 (:gate/id out)))
    (is (= :g3/pattern-not-found (:error/key out)))))

(deftest rejects-missing-exec-fn
  (let [out (pipeline/run (update-in base-input [:I-request] dissoc :exec/fn))]
    (is (false? (:ok out)))
    (is (= :g2 (:gate/id out)))
    (is (= :g2/artifact-unregistered (:error/key out)))))

(deftest rejects-criteria-not-met
  (let [bad-exec (fn [_] {:artifact/type :demo/artifact :artifact/ref {:demo "no"} :exec/success? false})
        out (pipeline/run (assoc-in base-input [:I-request :exec/fn] bad-exec))]
    (is (false? (:ok out)))
    (is (= :g1 (:gate/id out)))
    (is (= :g1/criteria-not-met (:error/key out)))))

(deftest rejects-missing-par
  (let [out (pipeline/run (update-in base-input [:I-request] dissoc :par))]
    (is (false? (:ok out)))
    (is (= :g0 (:gate/id out)))
    (is (= :g0/no-par (:error/key out)))))

(deftest happy-path-produces-proof-path
  (let [out (pipeline/run base-input)]
    (is (true? (:ok out)))
    (is (map? (:O-proof-path out)))
    (is (= 6 (count (get-in out [:O-proof-path :events]))))
    (is (string? (get-in out [:O-proof-path :path/id])))
    (is (map? (:O-evidence out)))
    (is (map? (:O-artifacts out)))))

;;; ============================================================
;;; Phase 2: Store Integration Tests
;;; ============================================================

;; Use a temp dir for proof-paths so tests don't pollute real data.
(def ^:dynamic *test-proof-dir* nil)

(defn with-temp-proof-dir [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "futon3b-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (try
      (with-redefs [relations/proof-path-dir (constantly (str dir))]
        (binding [*test-proof-dir* dir]
          (f)))
      (finally
        ;; Clean up
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))))

(use-fixtures :each with-temp-proof-dir)

(deftest g3-resolves-real-pattern
  (testing "G3 checks the real pattern library when no I-patterns set provided"
    ;; Use a pattern we know exists in the library
    (let [real-pattern "coordination/mandatory-psr"
          input (-> base-input
                    (dissoc :I-patterns)  ;; no injected set — force library lookup
                    (assoc-in [:I-request :psr :psr/pattern-ref] real-pattern))
          out (pipeline/run input)]
      (is (true? (:ok out))
          (str "Pipeline should succeed with real pattern " real-pattern)))))

(deftest g3-rejects-nonexistent-real-pattern
  (testing "G3 rejects a pattern that doesn't exist in the real library"
    (let [input (-> base-input
                    (dissoc :I-patterns)
                    (assoc-in [:I-request :psr :psr/pattern-ref]
                              "nonexistent/fake-pattern-xyz"))
          out (pipeline/run input)]
      (is (false? (:ok out)))
      (is (= :g3 (:gate/id out)))
      (is (= :g3/pattern-not-found (:error/key out))))))

(deftest durable-sink-writes-proof-path
  (testing "Pipeline without injected sink writes proof-path to EDN file"
    (let [input (-> base-input
                    (update-in [:I-request] dissoc :evidence/sink)  ;; use built-in sink
                    (assoc-in [:I-request :task :task/id] "T-durable-test"))
          out (pipeline/run input)]
      (is (true? (:ok out))
          "Pipeline should succeed with built-in EDN sink")
      (is (string? (get-in out [:O-proof-path :path/id])))
      ;; Verify the file was written
      (let [files (->> (file-seq *test-proof-dir*)
                       (filter #(clojure.string/ends-with? (.getName %) ".edn")))]
        (is (= 1 (count files))
            "Exactly one proof-path EDN file should be written")
        (when (seq files)
          (let [content (read-string (slurp (first files)))]
            (is (= (get-in out [:O-proof-path :path/id]) (:path/id content)))
            (is (= 6 (count (get-in content [:proof-path :events]))))
            (is (some? (:persisted-at content)))))))))

(deftest proof-path-round-trip-queryable
  (testing "Proof-path written by pipeline is queryable via relations"
    (let [task-id (str "T-roundtrip-" (System/nanoTime))
          input (-> base-input
                    (update-in [:I-request] dissoc :evidence/sink)
                    (assoc-in [:I-request :task :task/id] task-id))
          out (pipeline/run input)]
      (is (true? (:ok out)))
      ;; Now search for it
      (let [found (relations/search-proof-paths task-id)]
        (is (= 1 (count found))
            "Should find exactly one proof-path matching the task-id")
        (when (seq found)
          (is (= task-id (get-in (first found) [:evidence :task-spec :task/id]))))))))

(deftest g5-resolves-mission-from-registry
  (testing "G5 resolves mission-ref from data/missions.edn when no I-missions injected"
    (let [input (-> base-input
                    (dissoc :I-missions)  ;; force registry lookup
                    (assoc-in [:I-request :task :task/mission-ref]
                              "M-coordination-rewrite"))
          out (pipeline/run input)]
      (is (true? (:ok out))
          "Pipeline should succeed — M-coordination-rewrite is :active in missions.edn"))))

(deftest g5-rejects-unknown-mission-from-registry
  (testing "G5 rejects unknown mission-ref when resolved from registry"
    (let [input (-> base-input
                    (dissoc :I-missions)
                    (assoc-in [:I-request :task :task/mission-ref]
                              "M-nonexistent-mission"))
          out (pipeline/run input)]
      (is (false? (:ok out)))
      (is (= :g5 (:gate/id out)))
      (is (= :g5/mission-not-active (:error/key out))))))

(deftest g2-passthrough-artifact
  (testing "G2 accepts a passthrough artifact when no exec/fn provided"
    (let [input (-> base-input
                    (update-in [:I-request] dissoc :exec/fn)
                    (assoc-in [:I-request :artifact]
                              {:artifact/type :doc/review
                               :artifact/ref {:reviewed "AGENTS.md"}
                               :exec/success? true}))
          out (pipeline/run input)]
      (is (true? (:ok out))
          "Pipeline should succeed with passthrough artifact"))))

