(ns futon3.gate.default
  "C-default baseline behavior between tasks.

  Prototype 0: placeholder to keep the namespace shape stable. The concrete
  behavior is implemented once the gate pipeline is wired into futon3b's
  transport surfaces."
  (:require [futon3.gate.pipeline :as pipeline]))

(defn run-default
  "Default-mode entrypoint: currently delegates to pipeline/run."
  [input]
  (pipeline/run input))

