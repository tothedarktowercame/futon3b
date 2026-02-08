(ns futon3b.query.relations-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [futon3b.query.relations :as r]))

(defn- tmpdir []
  (let [d (java.nio.file.Files/createTempDirectory "futon3b-patterns" (make-array java.nio.file.attribute.FileAttribute 0))]
    (.toFile d)))

(deftest multiarg-and-flexiarg-ids-are-extracted
  (testing ".multiarg produces one entry for @multiarg plus one per @arg block; .flexiarg uses @flexiarg id"
    (let [root (tmpdir)
          lib (io/file root "library" "demo")
          _ (.mkdirs lib)
          flex (io/file lib "x.flexiarg")
          multi (io/file lib "y.multiarg")]
      (spit flex (str "@flexiarg demo/flex\n"
                      "@title Flex\n"
                      "! conclusion:\n  ok\n"))
      (spit multi (str "@multiarg demo/multi\n"
                       "@title Multi\n"
                       "\n"
                       "@arg demo/a\n+ IF:\n  a\n"
                       "\n"
                       "@arg demo/b\n+ IF:\n  b\n"))
      (let [entries (->> (#'r/pattern-files [(str root)])
                         (mapcat #'r/file->pattern-entries))
            ids (set (map :pattern-id entries))]
        (is (contains? ids "demo/flex"))
        (is (contains? ids "demo/multi"))
        (is (contains? ids "demo/a"))
        (is (contains? ids "demo/b"))
        (is (= 4 (count ids)))))))
