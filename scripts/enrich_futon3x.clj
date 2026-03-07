(ns scripts.enrich-futon3x
  "Add futon3x namespace metadata to futon1a hypergraph.

   Creates mission-provenance hyperedges linking futon3a/3b namespaces
   to M-futon3x-e2e.

   Run: cd futon3b && clj -M -i scripts/enrich_futon3x.clj -e '(scripts.enrich-futon3x/-main)'"
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]))

(def ^:private futon1a-url "http://localhost:7071")

(def ^:private namespaces
  "Key namespaces participating in the e2e demo."
  [{:ns "meme.schema"               :repo "futon3a" :role "SQLite schema for meme store"}
   {:ns "meme.core"                 :repo "futon3a" :role "Entity/artifact/alias CRUD"}
   {:ns "meme.arrow"                :repo "futon3a" :role "Kolmogorov typed semantic arrows"}
   {:ns "meme.bridge"               :repo "futon3a" :role "Sense-shift bridge triples"}
   {:ns "sidecar.store"             :repo "futon3a" :role "Event-sourced proposal store"}
   {:ns "futon3b.query.relations"   :repo "futon3b" :role "Federated search + core.logic relations"}
   {:ns "futon3.gate.pipeline"      :repo "futon3b" :role "G5→G0 gate composition"}
   {:ns "futon3.gate.pattern"       :repo "futon3b" :role "G3 pattern gate (PSR validation)"}])

(defn- post-hyperedge [hx-type endpoints]
  (let [url (str futon1a-url "/api/alpha/hyperedge")
        payload (json/write-str {:hx/type hx-type
                                  :hx/endpoints endpoints
                                  :penholder "api"})
        conn (doto (-> (java.net.URI. url) .toURL .openConnection)
               (.setRequestMethod "POST")
               (.setRequestProperty "Content-Type" "application/json")
               (.setDoOutput true))]
    (with-open [os (.getOutputStream conn)]
      (.write os (.getBytes payload "UTF-8")))
    (let [status (.getResponseCode conn)
          body (try (slurp (.getInputStream conn))
                    (catch Exception _ (slurp (.getErrorStream conn))))]
      {:status status :body body})))

(defn -main [& _args]
  (println "=== Enriching futon1a with futon3x namespace metadata ===")
  (println)

  (doseq [{:keys [ns repo role]} namespaces]
    (print (str "  " ns " → M-futon3x-e2e ... "))
    (flush)
    (let [result (post-hyperedge
                  "mission-provenance"
                  [ns "M-futon3x-e2e"])]
      (if (= 200 (:status result))
        (println "OK")
        (println (str "WARN " (:status result) " " (:body result))))))

  (println)
  (println "=== Verifying ===")
  (let [url (str futon1a-url "/api/alpha/hyperedges?end=M-futon3x-e2e")
        body (slurp url)
        data (read-string body)]
    (println (str "  Hyperedges touching M-futon3x-e2e: " (:count data)))
    (doseq [hx (:hyperedges data)]
      (println (str "    " (:hx/type hx) " " (:hx/endpoints hx))))))
