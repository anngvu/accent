(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.shell :as shell]))

(def lib 'accent)
(def version "")
(def class-dir "target/classes")
(def uber-file "target/accent.jar")
(def prebuilt-graph-file "resources/rdf/prebuilt-graph.ttl")

(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn build-prebuilt-graph
  "Generate the prebuilt graph from RDF sources"
  [_]
  (println "Adding prebuilt knowledge graph...")
  (let [result (shell/sh "clojure" "-M" "-m" "database.arachne" prebuilt-graph-file)]
    (if (zero? (:exit result))
      (println "✓ Prebuilt graph generated successfully at" prebuilt-graph-file)
      (do
        (println "✗ Failed to generate prebuilt graph:")
        (println (:err result))
        (throw (ex-info "Prebuilt graph generation failed" result))))))

(defn prepare
  "Prepare resources including prebuilt graph generation"
  [_]
  (println "Preparing build artifacts...")
  (build-prebuilt-graph nil)
  (println "✓ Preparation complete"))

(defn uber
  "Build uberjar with prebuilt graph"
  [opts]
  (clean nil)
  (prepare nil)
  (println "Building uberjar...")
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :ns-compile '[accent.app]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'accent.app})
  (println "✓ Uberjar built successfully at" uber-file))

(defn dev-build
  "Build without prebuilt graph for development"
  [_]
  (clean nil)
  (println "Building development version (no prebuilt graph)...")
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :ns-compile '[accent.app]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'accent.app})
  (println "✓ Development build complete"))

(defn check-prebuilt-graph
  "Verify the prebuilt graph exists and show stats"
  [_]
  (let [graph-file (java.io.File. prebuilt-graph-file)]
    (if (.exists graph-file)
      (do
        (println "✓ Prebuilt graph found at" prebuilt-graph-file)
        (println "  Size:" (.length graph-file) "bytes")
        (println "  Last modified:" (java.util.Date. (.lastModified graph-file))))
      (println "✗ Prebuilt graph not found at" prebuilt-graph-file))))

(defn rebuild-graph
  "Force rebuild of the prebuilt graph"
  [_]
  (println "Forcing rebuild of prebuilt graph...")
  (when (.exists (java.io.File. prebuilt-graph-file))
    (println "Removing existing prebuilt graph...")
    (.delete (java.io.File. prebuilt-graph-file)))
  (build-prebuilt-graph nil))
