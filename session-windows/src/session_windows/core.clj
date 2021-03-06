(ns session-windows.core
  (:require [clojure.core.async :refer [chan >!! <!! close!]]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.api]))

(def id (java.util.UUID/randomUUID))

(def env-config
  {:zookeeper/address "127.0.0.1:2188"
   :zookeeper/server? true
   :zookeeper.server/port 2188
   :onyx/tenancy-id id})

(def peer-config
  {:zookeeper/address "127.0.0.1:2188"
   :onyx/tenancy-id id
   :onyx.peer/job-scheduler :onyx.job-scheduler/balanced
   :onyx.messaging/impl :aeron
   :onyx.messaging/peer-port 40200
   :onyx.messaging/bind-addr "localhost"})

(def batch-size 10)

(def workflow
  [[:in :identity]
   [:identity :out]])

(def catalog
  [{:onyx/name :in
    :onyx/plugin :onyx.plugin.core-async/input
    :onyx/type :input
    :onyx/medium :core.async
    :onyx/batch-size batch-size
    :onyx/max-peers 1
    :onyx/doc "Reads segments from a core.async channel"}

   {:onyx/name :identity
    :onyx/fn :clojure.core/identity
    :onyx/type :function
    ;; compute sessions over group-by key :id
    :onyx/group-by-key :id
    :onyx/n-peers 1
    :onyx/flux-policy :recover
    :onyx/batch-size batch-size}

   {:onyx/name :out
    :onyx/plugin :onyx.plugin.core-async/output
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/max-peers 1
    :onyx/batch-size batch-size
    :onyx/doc "Writes segments to a core.async channel"}])

(def capacity 1000)

(def input-chan (chan capacity))
(def input-buffer (atom {}))

(def output-chan (chan capacity))

(def input-segments
  [{:event-id 0 :id 1 :event-time #inst "2015-09-13T03:00:00.829-00:00"}
   {:event-id 1 :id 1 :event-time #inst "2015-09-13T03:03:00.829-00:00"}
   {:event-id 2 :id 1 :event-time #inst "2015-09-13T03:07:00.829-00:00"}
   {:event-id 3 :id 2 :event-time #inst "2015-09-13T03:11:00.829-00:00"}
   {:event-id 4 :id 2 :event-time #inst "2015-09-13T03:15:00.829-00:00"}
   {:event-id 5 :id 2 :event-time #inst "2015-09-13T03:35:00.829-00:00"}
   {:event-id 6 :id 1 :event-time #inst "2015-09-13T03:20:00.829-00:00"}])

(doseq [segment input-segments]
  (>!! input-chan segment))

(def env (onyx.api/start-env env-config))

(def peer-group (onyx.api/start-peer-group peer-config))

(def n-peers (count (set (mapcat identity workflow))))

(def v-peers (onyx.api/start-peers n-peers peer-group))

(defn inject-in-ch [event lifecycle]
  {:core.async/buffer input-buffer
   :core.async/chan input-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan output-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(def lifecycles
  [{:lifecycle/task :in
    :lifecycle/calls ::in-calls}
   {:lifecycle/task :in
    :lifecycle/calls :onyx.plugin.core-async/reader-calls}
   {:lifecycle/task :out
    :lifecycle/calls ::out-calls}
   {:lifecycle/task :out
    :lifecycle/calls :onyx.plugin.core-async/writer-calls}])

(def windows
  [{:window/id :collect-segments
    :window/task :identity
    :window/type :session
    :window/aggregation :onyx.windowing.aggregation/conj
    :window/window-key :event-time
    :window/timeout-gap [5 :minutes]}])

(def triggers
  [{:trigger/window-id :collect-segments
    :trigger/id :sync
    :trigger/refinement :onyx.refinements/accumulating
    :trigger/on :onyx.triggers/timer
    :trigger/period [1 :seconds]
    :trigger/sync ::dump-window!}])

(defn dump-window!
  [event window trigger {:keys [lower-bound upper-bound] :as window-data} state]
  (println (format "Window extent [%s - %s] contents: %s"
                   lower-bound upper-bound state)))

(def submission
  (onyx.api/submit-job peer-config
                       {:workflow workflow
                        :catalog catalog
                        :lifecycles lifecycles
                        :windows windows
                        :triggers triggers
                        :task-scheduler :onyx.task-scheduler/balanced}))

;; Sleep until the trigger timer fires.
(Thread/sleep 5000)

(close! input-chan)

(onyx.api/await-job-completion peer-config (:job-id submission))

(def results (take-segments! output-chan 50))

(doseq [v-peer v-peers]
  (onyx.api/shutdown-peer v-peer))

(onyx.api/shutdown-peer-group peer-group)

(onyx.api/shutdown-env env)
