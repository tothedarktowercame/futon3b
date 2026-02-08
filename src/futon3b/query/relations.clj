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
;;; Configuration
;;; ============================================================

(def ^:private default-library-roots
  "Pattern library roots, searched in order. Configurable via
   FUTON_LIBRARY_ROOTS env var (colon-separated paths)."
  (if-let [env (System/getenv "FUTON_LIBRARY_ROOTS")]
    (str/split env #":")
    [(str (System/getProperty "user.home") "/code/futon3b/library")
     (str (System/getProperty "user.home") "/code/futon3/library")]))

;;; ============================================================
;;; Pattern Library
;;; ============================================================

(defn- parse-pattern-id
  "Extract pattern ID from flexiarg file content.
   Looks for @flexiarg or @multiarg header. Falls back to filename."
  [file-content filename]
  (or (when-let [m (re-find #"@(?:flexiarg|multiarg)\s+(\S+)" file-content)]
        (second m))
      (str/replace filename ".flexiarg" "")))

(defn- find-flexiarg-files
  "Find all .flexiarg files in the given library roots."
  [roots]
  (->> roots
       (mapcat (fn [root]
                 (let [base (io/file root)]
                   (when (.exists base)
                     (->> (file-seq base)
                          (filter #(str/ends-with? (.getName %) ".flexiarg"))
                          (map (fn [f]
                                 (let [content (slurp f)]
                                   {:file (str f)
                                    :filename (.getName f)
                                    :pattern-id (parse-pattern-id content (.getName f))
                                    :directory (.getName (.getParentFile f))
                                    :root root
                                    :content content}))))))))
       ;; Deduplicate by pattern-id (first root wins)
       (reduce (fn [acc p]
                 (if (contains? (:seen acc) (:pattern-id p))
                   acc
                   (-> acc
                       (update :seen conj (:pattern-id p))
                       (update :patterns conj p))))
               {:seen #{} :patterns []})
       :patterns))

(defonce ^:private pattern-cache (atom nil))
(defonce ^:private pattern-cache-mtime (atom 0))

(defn- load-patterns
  "Load patterns, invalidating cache if any file has been modified."
  []
  (let [now (System/currentTimeMillis)]
    ;; Re-check at most every 5 seconds
    (if (and @pattern-cache
             (< (- now @pattern-cache-mtime) 5000))
      @pattern-cache
      (let [patterns (vec (find-flexiarg-files default-library-roots))]
        (reset! pattern-cache patterns)
        (reset! pattern-cache-mtime now)
        patterns))))

(defn invalidate-pattern-cache!
  "Force pattern cache to reload on next access."
  []
  (reset! pattern-cache nil)
  (reset! pattern-cache-mtime 0))

;;; ============================================================
;;; Core.logic Relations: Transcripts
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
    (if-not (string? query-str)
      ()  ;; fail: query-str must be a ground string
      (let [results (transcript/search-all-transcripts query-str {:limit 200})]
        (l/to-stream
         (for [r results
               :let [a' (-> a
                            (l/unify session-id (:session-id r))
                            (l/unify match r))]
               :when a']
           a'))))))

;;; ============================================================
;;; Core.logic Relations: Patterns
;;; ============================================================

(defn patterno
  "Relate a logic var to pattern metadata maps (without :content)."
  [pattern-meta]
  (fn [a]
    (let [patterns (load-patterns)]
      (l/to-stream
       (for [p patterns
             :let [slim (dissoc p :content)
                   a' (l/unify a pattern-meta slim)]
             :when a']
         a')))))

(defn pattern-texto
  "Relate pattern-id to patterns whose content matches query-str.
   query-str must be a ground string."
  [pattern-id query-str]
  (fn [a]
    (if-not (string? query-str)
      ()  ;; fail: query-str must be a ground string
      (let [patterns (load-patterns)
            query-lower (str/lower-case query-str)]
        (l/to-stream
         (for [p patterns
               :when (str/includes?
                      (str/lower-case (:content p))
                      query-lower)
               :let [a' (l/unify a pattern-id (:pattern-id p))]
               :when a']
           a'))))))

;;; ============================================================
;;; Federated Search (convenience, not core.logic)
;;; ============================================================

(defn search
  "Run a federated text search. Returns seq of maps.
   Usage: (search \"PlanetMath\")
          (search \"PlanetMath\" {:limit 10})"
  ([query-str] (search query-str {}))
  ([query-str {:keys [limit] :or {limit 20}}]
   (let [transcript-results
         (->> (transcript/search-all-transcripts query-str {:limit limit})
              (map (fn [r] {:source :transcript
                            :id (:session-id r)
                            :match (select-keys r [:snippet :line-number
                                                   :role :file])})))
         pattern-results
         (->> (load-patterns)
              (filter #(str/includes?
                        (str/lower-case (:content %))
                        (str/lower-case query-str)))
              (map (fn [p] {:source :pattern
                            :id (:pattern-id p)
                            :match (select-keys p [:directory :file
                                                   :pattern-id])})))]
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
        (map #(select-keys % [:pattern-id :directory :file :root]))
        (take limit))))

(defn session-count
  "How many sessions exist?"
  []
  (count (transcript/all-session-summaries)))

(defn pattern-count
  "How many patterns exist in the library?"
  []
  (count (load-patterns)))
