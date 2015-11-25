(ns sossity.core
  (:require [clojure.test :refer :all]
            [sossity.core :refer :all]
            [loom.graph :refer :all]
            [loom.alg :refer :all]
            [loom.io :refer :all]
            [loom.attr :refer :all]
            [cheshire.core :refer :all]
            [clojure.tools.cli :refer [parse-opts]]
            [traversy.lens :as t :refer :all :exclude [view update combine]])
  (:gen-class))

(def sr-prefix "google_container_replica_controller")
(def df-prefix "google_dataflow")
(def pl-prefix "google_pubsub_topic")
(def container-oauth-scopes {:oauth_scopes ["https://www.googleapis.com/auth/compute"
                                            "https://www.googleapis.com/auth/devstorage.read_only"
                                            "https://www.googleapis.com/auth/logging.write"
                                            "https://www.googleapis.com/auth/monitoring"
                                            "https://www.googleapis.com/auth/cloud-platform"]})

(defn build-items [g items]
  (reduce #(add-attr %1 (key items) (key %2) (val %2)) g (val items)))

(defn build-annot [g item-set]
  (reduce #(build-items %1 %2) g item-set))

;tag sources and sinks

;NEED ERROR CHECKING - make sure all sources and sinks are in execution graph, etc
;CASE-SENSITIVE

(defn topic-name [topic] (str topic "_in"))
(defn source-topic-name [topic] (str topic "_out"))
(defn error-topic-name [topic] (str topic "_err"))

(defn topic
  [node project]
  (str "projects/" project "/topics/" node))

(defn non-source-pipes
  [node]
  ((juxt #(topic-name %) #(error-topic-name %)) node))

(defn source-pipes
  [node]
  ((juxt #(source-topic-name %) #(error-topic-name %)) node))

(defn create-pubsubs
  [g]                                                 ;out and error for all sources and pipelines, just error for sinks. nodes with cardinality? of 1 have out/error, 0 have error
  (let [t (bf-traverse g)
        connected (filter #(> (in-degree g %1) 0) t)
        ends (filter #(= (in-degree g %1) 0)  t)]
    (into [] (flatten [(map #(non-source-pipes %) connected)
                       (map #(source-pipes %) ends)]))))

(defn non-sink-successors
  [g node]
  (filter #(> (out-degree g %1) 0) (successors g node)))

(defn predecessor-sources
  [g node a-graph]
  (let [preds (set (predecessors g node))
        sources (filter preds (set (-> (:sources a-graph) (t/view all-keys))))
        source-names (mapv #(str sr-prefix "." % "-source") sources)]
    source-names))

(defn predecessor-depends
  "Determine which predecessors of a node are sources vs. normal jobs"
  [g node a-graph]
  (let [preds (set (predecessors g node))
        jobs (filter preds (set (-> (:pipelines a-graph) (t/view all-keys))))
        source-names (predecessor-sources g node a-graph)
        job-names (mapv #(str df-prefix "." %)  jobs)]
    (conj source-names job-names)))

(defn create-container-cluster
  [a-graph]
  (let [zone (get-in a-graph [:opts :zone])
        output (assoc (assoc (:cluster a-graph) :zone zone) :node_config container-oauth-scopes)]
    output))

(defn create-sink-container [item a-graph]
  (let [node (key item)
        item_name (str node "-sink")
        docker_image "gcr.io/hx-test/bearcub-sink"
        sink_retry 3
        sink_batch 100
        sink_proj (get-in a-graph [:provider :project])
        sink_sub (str node "_sub")
        container_name "${google_container_cluster.hx_fstack_cluster.name}"
        sink_bucket (:bucket (val item))
        zone (get-in a-graph [:opts :zone])
        output {item_name {:name item_name :docker_image docker_image :container_name container_name :zone zone :optional_args {:SINK_RETRY sink_retry :SINK_BATCH sink_batch :SINK_PROJ sink_proj
                                                                                                                                :SINK_SUB   sink_sub :SINK_BUCKET sink_bucket}}}]

    output))

(defn create-sub [item a-graph]
  (let [node (key item)
        name (str node "_sub")
        topic (topic-name node)
        output {name {:name name :topic topic}}]
    output))

(defn create-bucket [item]
  (let [name (:bucket (val item))
        force_destroy true
        location "EU"
        output {name {:name name :force_destroy force_destroy :location location}}]
    output))

;NOTE: need to create some kind of multiplexer job to make it so multiple jobs can read from multiple sources? ugh

(defn create-source-container
  "Creates a rest endpont and a single pubsub -- the only time we restrict to a single output"
  [item a-graph]
  (let [node (key item)
        post_route (str "/" node "/post")
        health_route (str "/" node "/health")
        item_name (str node "-source")
        docker_image "gcr.io/hx-test/prod-test"
        external_port "8080"
        stream_name (topic (source-topic-name node) (get-in a-graph [:provider :project]))
        container_name "${google_container_cluster.hx_fstack_cluster.name}"
        zone (get-in a-graph [:opts :zone])
        output {item_name {:name item_name :docker_image docker_image :external_port external_port :container_name container_name :zone zone :optional_args {:post_route post_route :health_route health_route :stream_name stream_name}}}]
    output))

(defn determine-input-topic
  [g node a-graph]
  (if (empty? (predecessor-sources g node a-graph))
    (topic-name node)
    (source-topic-name (first (predecessors g node)))))

(defn create-dataflow-item                                  ;build the right classpath, etc. composer should take all the jars in the classpath and glue them together like the transform-graph?
  [g node a-graph]
  (if (or (= nil (attr g node :type)) (= "cdf" (attr g node :type)))
    (let [project (get-in a-graph [:provider :project])
          output-topics (map #(topic-name %) (successors g node))
          input-topic (determine-input-topic g node a-graph)
          error-topic (error-topic-name node)
          ancestor-depends (predecessor-depends g node a-graph)
          name node
          class "com.acacia.dataflow.Main"      ;need to make tshis a smart default
          output-depends (map #(str pl-prefix "." %) output-topics)
          input-depends (str pl-prefix "." input-topic)
          depends-on (flatten [(flatten [output-depends ancestor-depends]) (str pl-prefix "." error-topic) input-depends])
          cli-map (dissoc (:opts a-graph) :classpaths)
          classpath (clojure.string/join (interpose ":" (get-in a-graph [:opts :classpaths]))) ;classpath has only one dash!
          opt-map {:project project :pubsubTopic (topic input-topic project) :pipelineName name :outputTopics
                   (clojure.string/join (interpose "," (map #(topic % project) output-topics)))}
          optional-args (merge cli-map opt-map)]
      {name {:name name :classpath classpath :class class :depends_on depends-on :project project :optional_args optional-args}})))

(defn create-dataflow-jobs [g a-graph]
  (let [t (bf-traverse g)                                   ;filter out anything in soruces or sinks without type cdf
        jobs (filter (comp not nil?) (map #(create-dataflow-item g % a-graph) t))]
    jobs))

(defn output-provider
  [provider-map]
  {:google (assoc (:provider provider-map) :region (get-in provider-map [:opts :zone]))})

(defn output-pubsub
  [pubsub-map]
  (map
   #(assoc-in  {} ["resource" "google_pubsub_topic" % "name"] %)
   pubsub-map))

(defn output-source [producer-map]
  {:google_container_replica_controller {(clojure.string/replace (:name producer-map) "-" "_") producer-map}})

(defn output-sink [sink-map]
  {:google_container_replica_controller {(clojure.string/replace (:name sink-map) "-" "_") sink-map}})

(defn output-sub [sub-map]
  {:google_pubsub_subscription sub-map})

(defn output-bucket [bucket-map]
  {:google_storage_bucket {(clojure.string/replace (:name bucket-map) "-" "_") bucket-map}})


(defn output-pubsub [pubsub-coll]
  (map #(assoc-in {} [% :name] %) pubsub-coll)
  )

(defn create-sources [a-graph]
  (map #(create-source-container % a-graph) (:sources a-graph)))

(defn create-sinks [a-graph]
  (map #(create-sink-container % a-graph) (:sinks a-graph)))

(defn create-subs [a-graph]
  (map #(create-sub % a-graph) (:sinks a-graph)))

(defn create-buckets [a-graph]
  (map #(create-bucket %) (:sinks a-graph)))

(defn output-container-cluster
  [a-graph]
  {:hx_fstack_cluster (create-container-cluster a-graph)})

(defn create-dag
  [a-graph]
  (let [g (digraph (into {} (map (juxt :origin :targets) (:edges a-graph))))]
    ;decorate nodes
    (-> (build-annot g (:pipelines a-graph))
        (build-annot  (:sources a-graph))
        (build-annot  (:sinks a-graph))
        #_(add-attr-to-nodes :type :source (get-submembers-keys a-graph :sources))
        #_(add-attr-to-nodes :type :sink (get-submembers-keys a-graph :sinks))))                                    ;return the graph?
)

(defn create-terraform-json                                 ;gotta be a better way to clean up these 'apply merges'
  [a-graph]
  (let [g (create-dag a-graph)
        provider (output-provider a-graph)
        pubsubs {:google_pubsub_topic (apply merge (output-pubsub (create-pubsubs g)))}
        subscriptions {:google_pubsub_subscription (apply merge (create-subs a-graph))}
        buckets {:google_storage_bucket (apply merge (create-buckets a-graph))}
        dataflows {:google_dataflow (apply merge (create-dataflow-jobs g a-graph))}
        container-cluster {:google_container_cluster (output-container-cluster a-graph)}
        sources (apply merge (create-sources a-graph))
        sinks (apply merge (create-sinks a-graph))
        controllers {:google_container_replica_controller (apply merge sources sinks)}
        combined {:provider provider :resource (merge pubsubs subscriptions container-cluster controllers buckets dataflows)}
        out (clojure.string/trim (generate-string combined {:pretty true}))]
    (str "{" (subs out 1 (- (count out) 2)) "}")))                        ;trim first [ and last ] from json


(defn output-terraform-file
  [a-graph file]
  (spit file (create-terraform-json a-graph) :create true :append false :truncate true))                          ;NOTE -- need to remove first [ and last ]


(defn read-and-create
  [input output]
  (output-terraform-file (read-string (slurp input)) output))

(def cli-options
  [["-c" "--config CONFIG" "path to .clj config file for pipeline"]
   ["-o" "--output OUTPUT" "path to output terraform file"]])

(defn -main
  "Entry Point"
  [& args]
  (let [opts (:options (clojure.tools.cli/parse-opts args cli-options))]
    (read-and-create (:config opts) (:output opts))))