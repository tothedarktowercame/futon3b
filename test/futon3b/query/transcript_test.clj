(ns futon3b.query.transcript-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [futon3b.query.transcript :as t]))

(defn- tmpdir []
  (let [d (java.nio.file.Files/createTempDirectory "futon3b-transcripts" (make-array java.nio.file.attribute.FileAttribute 0))]
    (.toFile d)))

(deftest parse-transcript-realizes-and-searches
  (testing "parse-transcript returns a realized vector and search finds a hit"
    (let [dir (tmpdir)
          f (io/file dir "S-1.jsonl")]
      (spit f (str "{\"role\":\"user\",\"content\":\"hello world\"}\n"
                   "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"PlanetMath is neat\"}]}\n"))
      (let [msgs (t/parse-transcript {:file f :session-id "S-1"})]
        (is (vector? msgs))
        (is (= 2 (count msgs))))
      (let [hits (t/search-transcript {:file f :session-id "S-1"} "planetmath")]
        (is (= 1 (count hits)))
        (is (= "S-1" (:session-id (first hits))))))))

