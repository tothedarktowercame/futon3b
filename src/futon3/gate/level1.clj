(ns futon3.gate.level1
  "Level 1 glacial loop composition: L1-observe → L1-canon.

  Analogous to how pipeline.clj composes G5→G0 for Level 0.
  The glacial loop watches Level 0 proof-paths for recurring tensions
  and evolves the pattern library."
  (:require [futon3.gate.observe :as observe]
            [futon3.gate.canon :as canon]))

(defn run
  "Run the Level 1 glacial loop.

  INPUT: {:I-tensions <proof-paths or explicit tension data>
          :I-patterns <library config>}

  Returns: {:ok true/false
            :observations [TensionObservation...]
            :canonizations [CanonizationEvent...]}"
  [input]
  (let [state0 {:ports (select-keys input [:I-tensions :I-patterns])
                :opts (:opts input)
                :evidence {}
                :proof-path []}
        ;; Step 1: Observe tensions
        s1 (observe/apply! state0)]
    (if-not (true? (get-in s1 [:result :ok]))
      ;; Observer rejected (e.g. no proof-paths)
      {:ok false
       :error/key (get-in s1 [:result :error/key])
       :message (get-in s1 [:result :message])
       :observations []
       :canonizations []}
      ;; Observer found tensions (possibly empty)
      (let [tensions (get-in s1 [:evidence :tensions] [])]
        (if (empty? tensions)
          ;; Clean "nothing to do" — not an error
          {:ok true
           :observations []
           :canonizations []}
          ;; Step 2: Canonicalize
          (let [s2 (canon/apply! s1)
                canon-result (:result s2)]
            {:ok (true? (:ok canon-result))
             :observations tensions
             :canonizations (get-in s2 [:evidence :canonizations] [])}))))))
