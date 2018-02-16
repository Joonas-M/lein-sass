(ns leiningen.sass
  (:require [clojure.set :refer [rename-keys]]
            [clojure.java.io :as io]
            [clojure.pprint]
            [clojure.string :as s])
  (:import
    java.io.File
    [java.nio.file FileSystems Paths StandardWatchEventKinds]
    [javax.script Invocable ScriptEngineManager]))

(def compiled? (atom false))
(defonce engine (let [e (.getEngineByName (ScriptEngineManager.) "nashorn")]
                  (.eval e "function setTimeout(f) {f();};")
                  (.eval e (io/reader (io/resource "sass.sync.js")))
                  (.eval e "var source = '';")
                  (.eval e "var options = '';")
                  (.eval e "var output = '';")
                  (.eval e "var output_formatted = '';")
                  (.eval e "var output_map = '';")
                  (.eval e "var output_text = '';")
                  (.eval e "var output_column = '';")
                  (.eval e "var output_line = '';")
                  (.eval e "var output_status = '';")
                  (.eval e "var output_file = '';")
                  (.eval e "var input_relative_path = '';")
                  (.eval e "function setSource(input) {source = input;};")
                  (.eval e (str "function setOptions(input_path, output_name) {"
                                "input_relative_path = input_path;"
                                "options = {inputPath: input_path, outputPath: output_name};"
                                "};"))
                  (.eval e (str "function setOutput(result) {"
                                "output = result;"
                                "output_formatted = result.formatted;"
                                "output_map = result.map;"
                                "delete output_map.sourcesContent;"
                                "delete output_map.sourceRoot;"
                                "output_map.sources[0] = input_relative_path;"
                                "output_text = result.text;"
                                "output_column = result.column;"
                                "output_line = result.line;"
                                "output_status = result.status;"
                                "output_file = result.file;"
                                "};"))
                  e))

(defn register-events! [dir watch-service]
  (.register dir
             watch-service
             (into-array
               [StandardWatchEventKinds/ENTRY_CREATE
                StandardWatchEventKinds/ENTRY_MODIFY
                StandardWatchEventKinds/ENTRY_DELETE
                StandardWatchEventKinds/OVERFLOW])
             (into-array [(com.sun.nio.file.SensitivityWatchEventModifier/HIGH)])))

(defn watch-loop [watch-service handler]
  (while true
    (when-let [k (.take watch-service)]
      (when-let [events (not-empty (.pollEvents k))]
        (handler (first events))
        (.pollEvents k)
        (.reset k)))))

(defn watch [path handler]
  (let [dir (-> path (io/file) (.toURI) (Paths/get))]
    (with-open [watch-service (.newWatchService (FileSystems/getDefault))]
      (register-events! dir watch-service)
      (watch-loop watch-service handler))))

(defn watch-thread [path handler]
  (println "watching for changes in" path)
  (doto
    (Thread. #(watch path handler))
    (.start)
    (.join)))

(defn compile-file [file relative-input-path output-file-name]
  (let [source (slurp file)]
    (.invokeFunction (cast Invocable engine) "setSource" (into-array [source]))
    (.invokeFunction (cast Invocable engine) "setOptions" (into-array [relative-input-path output-file-name]))
    (.eval engine "Sass.compile(source, options, setOutput)")))

(defn find-assets [f ext]
  (when f
    (if (.isDirectory f)
      (->> f
           file-seq
           (filter (fn [file] (-> file .getName (.endsWith ext)))))
      [f])))

(defn ext-sass->css [file-name]
  (str (subs file-name 0 (.lastIndexOf file-name ".")) ".css"))

(defn relative-path [dir-path file-path] ;; TODO reimplement it
  (let [prefix (->> (s/split dir-path #"/")
                    (drop 1)
                    (map (fn [_] ".."))
                    (s/join "/"))]
    (str prefix file-path)))

(defn compile-assets [files target]
  (doseq [file files]
    (let [file-name           (.getName file)
          _                   (println "compiling" file-name)
          output-file-name    (ext-sass->css file-name)
          output-file-path    (str target File/separator output-file-name)
          relative-input-path (relative-path target (.getPath file))
          _                   (compile-file file relative-input-path output-file-name)
          formatted           (.eval engine "output_formatted")
          map                 (.eval engine "JSON.stringify(output_map)")
          text                (.eval engine "output_text")
          column              (.eval engine "output_column")
          line                (.eval engine "output_line")
          status              (.eval engine "output_status")]
      (if (pos? status)
        (do
          (println (format "%s:%s:%s failed to compile:" file-name line column))
          (clojure.pprint/pprint formatted))
        (do
          (println "compiled successfully")
          (let [map-file-path (str output-file-path ".map")]
            (io/make-parents output-file-path)
            (spit output-file-path text)
            (spit map-file-path map)))))))

(defn sass [{{:keys [source target]} :sass} & opts]
  (let [files (concat (find-assets (io/file source) ".sass")
                      (find-assets (io/file source) ".scss"))]
    (if (some #{"watch"} opts)
      (do
        (compile-assets files target)
        (watch-thread source (fn [e] (compile-assets files target))))
      (when (not @compiled?)
        (compile-assets files target)
        (reset! compiled? true)))))
