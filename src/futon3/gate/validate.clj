(ns futon3.gate.validate
  "G1 - Validation gate.

  Patterns:
  - coordination/mandatory-pur
  - coordination/cross-validation-protocol"
  (:require [futon3.gate.errors :as errors]
            [futon3.gate.shapes :as shapes]
            [futon3.gate.util :as u]))

(defn- validate-fn [state]
  (or (get-in state [:ports :I-request :validate/fn])
      (get-in state [:ports :I-environment :validate/fn])
      (get-in state [:ports :I-request :validate-fn])))

(defn- default-criteria-eval
  [{:keys [task exec-outcome]}]
  (let [criteria (vec (or (:task/success-criteria task) []))
        success? (boolean (or (:exec/success? exec-outcome)
                              (:success? exec-outcome)
                              (:ok exec-outcome)
                              false))]
    {:criteria criteria
     :passed? success?
     :notes (when-not success?
              (or (:error exec-outcome) "execution did not report success"))}))

(defn apply!
  "Create PUR and enforce success criteria."
  [state]
  (let [task (get-in state [:evidence :task-spec])
        psr (get-in state [:evidence :psr])
        artifact (get-in state [:evidence :artifact])
        exec-outcome (get-in state [:evidence :exec/outcome])
        f (validate-fn state)]
    (if (nil? psr)
      (assoc state :result (errors/reject :g1/no-pur {:reason "missing PSR (prior gate did not run?)"}))
      (let [criteria-eval (try
                            (cond
                              (fn? f) (f {:task task :psr psr :artifact artifact :exec-outcome exec-outcome})
                              :else (default-criteria-eval {:task task :exec-outcome exec-outcome}))
                            (catch Throwable t
                              {:passed? false :error (.getMessage t)}))
            passed? (true? (:passed? criteria-eval))
            pur {:pur/id (u/gen-id "pur")
                 :pur/psr-ref (:psr/id psr)
                 :pur/outcome (if passed? :pass :fail)
                 :pur/criteria-eval (dissoc criteria-eval :passed?)
                 :pur/prediction-error (:prediction-error criteria-eval)}]
        (if-not (try (shapes/validate! shapes/PUR pur) true
                     (catch Exception _ false))
          (assoc state :result (errors/reject :g1/no-pur {:reason "invalid PUR shape"
                                                          :pur pur}))
          (let [state' (-> state
                           (assoc-in [:evidence :pur] pur)
                           (update :proof-path (fnil conj []) {:gate/id :g1 :gate/record pur :gate/at (u/now-iso)}))]
            (if-not passed?
              (assoc state' :result (errors/reject :g1/criteria-not-met
                                                   {:task/id (:task/id task)
                                                    :pur pur
                                                    :artifact/id (:artifact/id artifact)}))
              (assoc state' :result {:ok true}))))))))

