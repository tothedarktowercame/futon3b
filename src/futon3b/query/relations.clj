(ns futon3b.query.relations
  "Core.logic relations backed by real stores.

   Each relation wraps a different store (XTDB, SQLite, filesystem)
   and exposes it as a core.logic goal. Queries unify across stores
   through logic variables.

   The tri-theory interpretation:
   - Prose:  library/*.flexiarg (human reads intent)
   - Logic:  these relations (system checks structural claims)
   - Tensor: futon5/ct + hexagram (geometric characterization)

   This namespace is the LOGIC leg."
  (:require [clojure.core.logic :as l]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [futon3b.query.transcript :as transcript]))

;;; ============================================================
;;; Goal constructor helper
;;; ============================================================

(defn- choices
  "Create a goal that unifies lvar with each item in coll.
   The fundamental bridge between external data and core.logic."
  [lvar coll]
  (l/membero lvar (vec coll)))

;;; ============================================================
;;; Transcript Relations
;;; ============================================================

(defn transcript-sessiono
  "Relate a logic var to session summary maps.
   Backed by filesystem scan of ~/.claude/projects/."
  [session-meta]
  (fn [a]
    (let [summaries (transcript/all-session-summaries)]
      (l/to-stream
       (for [s summaries
             :let [a' (l/unify a session-meta s)]
             :when a']
         a')))))

(defn transcript-texto
  "Relate session-id and match-info for a given query string.
   query-str must be a ground string (not a logic variable)."
  [session-id match query-str]
  (fn [a]
    (let [results (transcript/search-all-transcripts query-str {:limit 200})]
      (l/to-stream
       (for [r results
             :let [a' (-> a
                          (l/unify session-id (:session-id r))
                          (l/unify match r))]
             :when a']
         a')))))

;;; ============================================================
;;; Pattern Library Relations
;;; ============================================================

(def ^:private default-library-path
  (str (System/getProperty "user.home") "/code/futon3/library"))

(defn- find-flexiarg-files
  "Find all .flexiarg files in the library."
  ([] (find-flexiarg-files default-library-path))
  ([base-path]
   (let [base (io/file base-path)]
     (when (.exists base)
       (->> (file-seq base)
            (filter #(str/ends-with? (.getName %) ".flexiarg"))
            (map (fn [f]
                   {:file (str f)
                    :pattern-id (str/replace (.getName f) ".flexiarg" "")
                    :directory (.getName (.getParentFile f))
                    :content (slurp f)})))))))

(defonce ^:private pattern-cache (atom nil))

(defn- load-patterns []
  (or @pattern-cache
      (reset! pattern-cache (vec (find-flexiarg-files)))))

(defn patterno
  "Relate a logic var to pattern metadata maps."
  [pattern-meta]
  (fn [a]
    (let [patterns (load-patterns)]
      (l/to-stream
       (for [p patterns
             :let [a' (l/unify a pattern-meta p)]
             :when a']
         a')))))

(defn pattern-texto
  "Relate pattern-id to patterns whose content matches query-str.
   query-str must be a ground string."
  [pattern-id query-str]
  (fn [a]
    (let [patterns (load-patterns)
          query-lower (str/lower-case query-str)]
      (l/to-stream
       (for [p patterns
             :when (str/includes?
                    (str/lower-case (:content p))
                    query-lower)
             :let [a' (l/unify a pattern-id (:pattern-id p))]
             :when a']
         a')))))

;;; ============================================================
;;; Federated Search
;;; ============================================================

(defn search-texto
  "Federated text search across all stores.
   Relates source-type, entity-id, and match-info for a query string.
   query-str must be a ground string."
  [source entity-id match query-str]
  (l/conde
   ;; Search transcripts
   [(l/== source :transcript)
    (transcript-texto entity-id match query-str)]
   ;; Search patterns
   [(l/== source :pattern)
    (pattern-texto entity-id query-str)
    (l/fresh [meta]
      (patterno meta)
      (l/project [meta]
        (l/== match meta)
        (l/== entity-id (:pattern-id meta))))]))

;;; ============================================================
;;; Convenience: run queries and return Clojure data
;;; ============================================================

(defn search
  "Run a federated text search. Returns seq of maps.
   Usage: (search \"PlanetMath\")
          (search \"PlanetMath\" {:limit 10})"
  ([query-str] (search query-str {}))
  ([query-str {:keys [limit] :or {limit 20}}]
   (let [;; Gather results from each store independently
         ;; (simpler and more reliable than full unification for text search)
         transcript-results
         (->> (transcript/search-all-transcripts query-str {:limit limit})
              (map (fn [r] {:source :transcript
                            :id (:session-id r)
                            :match r})))
         pattern-results
         (->> (load-patterns)
              (filter #(str/includes?
                        (str/lower-case (:content %))
                        (str/lower-case query-str)))
              (map (fn [p] {:source :pattern
                            :id (:pattern-id p)
                            :match (dissoc p :content)})))]
     (take limit (concat transcript-results pattern-results)))))

(defn sessions
  "List all known sessions. Returns seq of metadata maps."
  ([] (sessions {}))
  ([{:keys [limit] :or {limit 100}}]
   (take limit (transcript/all-session-summaries))))

(defn patterns
  "List all known patterns. Returns seq of {:pattern-id :directory :file}."
  ([] (patterns {}))
  ([{:keys [limit] :or {limit 200}}]
   (->> (load-patterns)
        (map #(dissoc % :content))
        (take limit))))

(defn session-count
  "How many sessions exist?"
  []
  (count (transcript/all-session-summaries)))

(defn pattern-count
  "How many patterns exist in the library?"
  []
  (count (load-patterns)))
