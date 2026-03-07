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
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [futon3.gate.shapes :as shapes]
            [futon3b.query.transcript :as transcript]
            [meme.schema :as meme-schema]
            [meme.core :as meme-core]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;;; ============================================================
;;; Configuration
;;; ============================================================

(def ^:private default-library-roots
  ;; Prefer repo-local patterns, fall back to legacy futon3 library checkout.
  [(str (io/file (System/getProperty "user.dir") "library"))
   (str (io/file (System/getProperty "user.home") "code" "futon3" "library"))])

(defn library-roots
  "Pattern library roots, searched in order.

  Override with FUTON_LIBRARY_ROOTS env var (colon-separated paths)."
  []
  (if-let [env (some-> (System/getenv "FUTON_LIBRARY_ROOTS") str/trim not-empty)]
    (->> (str/split env #":")
         (map str/trim)
         (remove str/blank?)
         vec)
    default-library-roots))

;;; ============================================================
;;; Pattern Library
;;; ============================================================

(defn- file-kind [^java.io.File f]
  (cond
    (str/ends-with? (.getName f) ".flexiarg") :flexiarg
    (str/ends-with? (.getName f) ".multiarg") :multiarg
    :else :unknown))

(defn- header-id
  "Extract a single header id from TEXT using regex RE.
  RE must have one capture group for the id."
  [re text]
  (when (string? text)
    (when-let [m (re-find re text)]
      (second m))))

(defn- split-arg-blocks
  "Split a .multiarg file into @arg blocks. If no @arg blocks exist, return []."
  [text]
  (let [lines (str/split-lines (or text ""))]
    (loop [remaining lines
           current []
           in-arg? false
           blocks []]
      (if-let [line (first remaining)]
        (let [starts-arg? (str/starts-with? (str/trim line) "@arg ")]
          (cond
            (and starts-arg? in-arg?)
            (recur (rest remaining) [line] true (conj blocks (str/join "\n" current)))

            starts-arg?
            ;; Start a new @arg block; ignore any file preamble before the first @arg.
            (recur (rest remaining) [line] true blocks)

            :else
            (recur (rest remaining)
                   (if in-arg? (conj current line) current)
                   in-arg?
                   blocks)))
        (if in-arg?
          (conj blocks (str/join "\n" current))
          blocks)))))

(defn- pattern-files
  "Find all .flexiarg and .multiarg files under ROOTS.

  Returns seq of {:file File :root string}."
  ([] (pattern-files (library-roots)))
  ([roots]
   (->> (or roots [])
        (map str/trim)
        (remove str/blank?)
        (map (fn [root]
               (let [base (io/file root)]
                 (when (.exists base)
                   (->> (file-seq base)
                        (filter #(.isFile ^java.io.File %))
                        (filter (fn [^java.io.File f]
                                  (case (file-kind f)
                                    (:flexiarg :multiarg) true
                                    false)))
                        (map (fn [f] {:file f :root root})))))))
        (remove nil?)
        (mapcat identity))))

(defn- file->pattern-entries
  "Convert one file into one-or-more pattern entries.

  - .flexiarg => one entry (pattern id from @flexiarg or filename)
  - .multiarg => one entry for @multiarg (or filename) + one entry per @arg block"
  [{:keys [^java.io.File file root]}]
  (let [kind (file-kind file)
        filename (.getName file)
        directory (.getName (.getParentFile file))
        path (str file)
        content (slurp file)]
    (case kind
      :flexiarg
      (let [pid (or (header-id #"(?m)^[ \t]*@flexiarg\s+(\S+)" content)
                    (str/replace filename ".flexiarg" ""))]
        [{:file path
          :filename filename
          :pattern-id pid
          :directory directory
          :root root
          :source/kind :flexiarg
          :content content}])

      :multiarg
      (let [multi-pid (or (header-id #"(?m)^[ \t]*@multiarg\s+(\S+)" content)
                          (str/replace filename ".multiarg" ""))
            whole-entry {:file path
                         :filename filename
                         :pattern-id multi-pid
                         :directory directory
                         :root root
                         :source/kind :multiarg
                         :content content}
            arg-entries
            (->> (split-arg-blocks content)
                 (keep (fn [block]
                         (when-let [pid (header-id #"(?m)^[ \t]*@arg\s+(\S+)" block)]
                           {:file path
                            :filename filename
                            :pattern-id pid
                            :directory directory
                            :root root
                            :source/kind :multiarg-arg
                            :content block})))
                 vec)]
        (into [whole-entry] arg-entries))

      [])))

(defn- find-pattern-entries
  "Find all pattern entries across ROOTS, deduping by :pattern-id (first root wins)."
  [roots]
  (->> (pattern-files roots)
       (mapcat file->pattern-entries)
       (reduce (fn [{:keys [seen patterns] :as acc} p]
                 (if (contains? seen (:pattern-id p))
                   acc
                   {:seen (conj seen (:pattern-id p))
                    :patterns (conj patterns p)}))
               {:seen #{} :patterns []})
       :patterns
       vec))

(defonce ^:private pattern-cache (atom nil))
(defonce ^:private pattern-cache-checked-at (atom 0))

(defn- load-patterns
  "Load patterns, re-scanning at most every 5 seconds."
  []
  (let [now (System/currentTimeMillis)]
    ;; Re-check at most every 5 seconds
    (if (and @pattern-cache
             (< (- now @pattern-cache-checked-at) 5000))
      @pattern-cache
      (let [patterns (find-pattern-entries (library-roots))]
        (reset! pattern-cache patterns)
        (reset! pattern-cache-checked-at now)
        patterns))))

(defn invalidate-pattern-cache!
  "Force pattern cache to reload on next access."
  []
  (reset! pattern-cache nil)
  (reset! pattern-cache-checked-at 0))

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
;;; Core.logic Relations: Meme Store
;;; ============================================================

(defn- meme-ds
  "Get the meme store datasource. Returns nil if meme.db doesn't exist."
  []
  (let [path (meme-schema/db-path)]
    (when (.exists (io/file path))
      (meme-schema/datasource path))))

(defn memeo
  "Relate entity-id and entity-meta for meme store entities matching query-str.
   query-str must be a ground string. Searches entity name and description."
  [entity-id entity-meta query-str]
  (fn [a]
    (if-not (string? query-str)
      () ;; fail: query-str must be ground
      (if-let [ds (meme-ds)]
        (let [like-pat (str "%" query-str "%")
              results (jdbc/execute! ds
                        ["SELECT * FROM entities WHERE name LIKE ? OR description LIKE ?"
                         like-pat like-pat]
                        {:builder-fn rs/as-unqualified-maps})]
          (l/to-stream
           (for [r results
                 :let [meta {:name (:name r)
                             :kind (:kind r)
                             :description (:description r)
                             :id (:id r)}
                       a' (-> a
                              (l/unify entity-id (:id r))
                              (l/unify entity-meta meta))]
                 :when a']
             a')))
        ()))))

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
        (l/== entity-id (:pattern-id meta))))]
   ;; Search meme store
   [(l/== source :meme)
    (memeo entity-id match query-str)]))

;;; ============================================================
;;; Convenience: run queries and return Clojure data
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
                                                   :pattern-id])})))
         meme-results
         (when-let [ds (meme-ds)]
           (let [like-pat (str "%" query-str "%")]
             (->> (jdbc/execute! ds
                    ["SELECT * FROM entities WHERE name LIKE ? OR description LIKE ?"
                     like-pat like-pat]
                    {:builder-fn rs/as-unqualified-maps})
                  (map (fn [e] {:source :meme
                                :id (:id e)
                                :match (select-keys e [:name :kind :description])})))))]
     ;; Fair share from each source to avoid one dominating
     (let [per-source (max 1 (quot limit 3))
           remainder (- limit (* 3 per-source))]
       (concat (take (+ per-source remainder) pattern-results)
               (take per-source meme-results)
               (take per-source transcript-results))))))

(defn sessions
  "List all known sessions. Returns seq of metadata maps."
  ([] (sessions {}))
  ([{:keys [limit] :or {limit 100}}]
   (take limit (transcript/all-session-summaries))))

(defn patterns
  "List all known patterns. Returns seq of {:pattern-id :directory :file :root :source/kind}."
  ([] (patterns {}))
  ([{:keys [limit] :or {limit 200}}]
   (->> (load-patterns)
        (map #(select-keys % [:pattern-id :directory :file :root :source/kind]))
        (take limit))))

(defn session-count
  "How many sessions exist?"
  []
  (count (transcript/all-session-summaries)))

(defn pattern-count
  "How many patterns exist in the library?"
  []
  (count (load-patterns)))

(defn pattern-exists?
  "Does a pattern with the given pattern-id exist in the library?"
  [pattern-id]
  (boolean (some #(= (:pattern-id %) pattern-id) (load-patterns))))

(defn pattern-ids
  "Return the set of all known pattern-ids."
  []
  (into #{} (map :pattern-id) (load-patterns)))

;;; ============================================================
;;; Mission Registry
;;; ============================================================

(def ^:private default-missions-file
  (str (io/file (System/getProperty "user.dir") "data" "missions.edn")))

(defn missions-file
  "Path to the missions EDN registry. Override with FUTON_MISSIONS_FILE."
  []
  (or (System/getenv "FUTON_MISSIONS_FILE")
      default-missions-file))

(defn load-missions
  "Load missions from the EDN registry file.
   Returns a map of mission-ref → mission metadata."
  []
  (let [f (io/file (missions-file))]
    (if (.exists f)
      (edn/read-string (slurp f))
      {})))

;;; ============================================================
;;; Proof Path Store
;;; ============================================================

(def ^:private default-proof-path-dir
  (str (io/file (System/getProperty "user.dir") "data" "proof-paths")))

(defn proof-path-dir
  "Directory for proof-path EDN files. Override with FUTON_PROOF_PATH_DIR."
  []
  (or (System/getenv "FUTON_PROOF_PATH_DIR")
      default-proof-path-dir))

(defn append-proof-path!
  "Append a proof-path + evidence map to an EDN log file.
   Returns {:ok true :path/id <id> :file <path>}.
   Each line in the file is one complete EDN map."
  [{:keys [proof-path evidence]}]
  (shapes/validate! shapes/ProofPath proof-path)
  (let [dir (io/file (proof-path-dir))
        _ (.mkdirs dir)
        path-id (:path/id proof-path)
        file (io/file dir (str path-id ".edn"))
        record {:path/id path-id
                :proof-path proof-path
                :evidence evidence
                :persisted-at (str (java.time.Instant/now))}]
    (spit file (prn-str record))
    {:ok true :path/id path-id :file (str file)}))

(defn load-proof-paths
  "Load all proof-path EDN files from the proof-path directory.
   Returns seq of maps."
  []
  (let [dir (io/file (proof-path-dir))]
    (when (.exists dir)
      (->> (file-seq dir)
           (filter #(str/ends-with? (.getName %) ".edn"))
           (map (fn [f]
                  (try
                    (edn/read-string (slurp f))
                    (catch Exception _ nil))))
           (remove nil?)))))

(defn search-proof-paths
  "Search proof-paths for text matches. Case-insensitive."
  [query-str]
  (let [query-lower (str/lower-case query-str)]
    (->> (load-proof-paths)
         (filter (fn [pp]
                   (str/includes?
                    (str/lower-case (pr-str pp))
                    query-lower))))))

;;; ============================================================
;;; Core.logic Relations: Proof Paths
;;; ============================================================

(defn proof-patho
  "Relate a logic var to proof-path records from the proof-path store."
  [proof-path-meta]
  (fn [a]
    (let [paths (load-proof-paths)]
      (l/to-stream
       (for [pp paths
             :let [a' (l/unify a proof-path-meta pp)]
             :when a']
         a')))))
