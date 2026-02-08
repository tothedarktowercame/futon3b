(ns futon3b.query.transcript
  "Search Claude Code session transcripts (~/.claude/projects/).

   Each session is a JSONL file. Lines contain message objects with
   :role, :content, and tool use records. This namespace indexes
   transcripts for text search and exposes them as core.logic relations."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]))

;;; ============================================================
;;; Transcript Discovery
;;; ============================================================

(def ^:private claude-projects-dir
  (str (System/getProperty "user.home") "/.claude/projects"))

(defn find-transcript-files
  "Find all JSONL transcript files under ~/.claude/projects/.
   Returns seq of {:file File :project-key string :session-id string}."
  ([] (find-transcript-files claude-projects-dir))
  ([base-dir]
   (let [base (io/file base-dir)]
     (when (.exists base)
       (->> (file-seq base)
            (filter #(str/ends-with? (.getName %) ".jsonl"))
            (map (fn [f]
                   {:file f
                    :project-key (.getName (.getParentFile f))
                    :session-id (str/replace (.getName f) ".jsonl" "")})))))))

;;; ============================================================
;;; Transcript Parsing
;;; ============================================================

(defn- safe-parse-json-line [line]
  (try
    (json/read-str line :key-fn keyword)
    (catch Exception _ nil)))

(defn parse-transcript
  "Parse a JSONL transcript file into a realized vector of message maps.
   Each map gets :line-number and :session-id added.

   Fully realizes inside with-open to avoid leaking readers via lazy seqs."
  [{:keys [file session-id]}]
  (with-open [rdr (io/reader file)]
    (into []
          (comp
           (map-indexed (fn [i line]
                          (when-let [parsed (safe-parse-json-line line)]
                            (assoc parsed
                                   :line-number (inc i)
                                   :session-id session-id))))
           (remove nil?))
          (line-seq rdr))))

;;; ============================================================
;;; Text Search
;;; ============================================================

(defn- extract-text
  "Extract searchable text from a parsed message."
  [msg]
  (let [content (:content msg)]
    (cond
      (string? content) content
      (sequential? content) (str/join " " (keep :text content))
      :else "")))

(defn search-transcript
  "Search a single transcript for text matches. Case-insensitive.
   Returns seq of {:session-id :line-number :role :snippet :file}."
  [transcript-info query-text]
  (let [query-lower (str/lower-case query-text)
        messages (parse-transcript transcript-info)]
    (->> messages
         (filter (fn [msg]
                   (let [text (str/lower-case (extract-text msg))]
                     (str/includes? text query-lower))))
         (map (fn [msg]
                (let [text (extract-text msg)
                      idx (or (str/index-of (str/lower-case text) query-lower) 0)
                      start (max 0 (- idx 60))
                      end (min (count text) (+ idx (count query-text) 60))
                      snippet (subs text start end)]
                  {:session-id (:session-id msg)
                   :line-number (:line-number msg)
                   :role (:role msg)
                   :snippet snippet
                   :file (str (:file transcript-info))}))))))

(defn search-all-transcripts
  "Search all transcripts for text matches.
   Returns seq of match maps sorted by recency (newest first)."
  ([query-text] (search-all-transcripts query-text {}))
  ([query-text {:keys [project-key limit] :or {limit 50}}]
   (let [transcripts (find-transcript-files)
         transcripts (if project-key
                       (filter #(= (:project-key %) project-key) transcripts)
                       transcripts)
         ;; Sort by file modification time, newest first
         transcripts (sort-by #(- (.lastModified (:file %))) transcripts)]
     (->> transcripts
          (mapcat #(search-transcript % query-text))
          (take limit)))))

;;; ============================================================
;;; Session Metadata
;;; ============================================================

(defn session-summary
  "Extract summary metadata from a transcript file without full parse.
   Returns {:session-id :project-key :file :size :modified :line-count}."
  [{:keys [file session-id project-key]}]
  {:session-id session-id
   :project-key project-key
   :file (str file)
   :size (.length file)
   :modified (java.util.Date. (.lastModified file))
   :line-count (with-open [rdr (io/reader file)]
                 (count (line-seq rdr)))})

(defn all-session-summaries
  "Get summary metadata for all sessions."
  ([] (all-session-summaries {}))
  ([{:keys [project-key]}]
   (let [transcripts (find-transcript-files)]
     (->> (if project-key
            (filter #(= (:project-key %) project-key) transcripts)
            transcripts)
          (map session-summary)
          (sort-by :modified)
          reverse))))
