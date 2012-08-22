(defproject tnrglobal/bishop "1.1.5-SNAPSHOT"
  :description "A Webmachine-like REST library"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.clojure/tools.logging "0.2.3"]
                 [ring/ring "1.0.1"]
                 [joda-time "2.1"]
                 [commons-codec "1.5"]
                 [commons-lang "2.6"]]
  :dev-dependencies [[ring/ring-devel "1.0.1"]
                     [net.cgrand/moustache "1.1.0"]]
  :profiles {:dev
             {:dependencies [[ring/ring-devel "1.0.1"]
                             [net.cgrand/moustache "1.1.0"]]
              :plugins [[lein-swank "1.4.4"]]}})
