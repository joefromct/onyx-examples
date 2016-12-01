(defproject aggregation "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 ^{:voom {:repo "git@github.com:onyx-platform/onyx.git" :branch "master"}}
                 [org.onyxplatform/onyx "0.9.14"]]
  :jvm-opts ^:replace ["-Xmx4g" 
                       "-Dclojure.core.async.pool-size=8"]
  :plugins [[lein-update-dependency "0.1.2"]])
