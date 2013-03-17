(defproject tnrglobal/bishop "1.2.1"
  :description "A Webmachine-like REST library"
  :dependencies [[org.clojure/tools.logging "0.2.3"]
                 [ring/ring "1.1.5"]
                 [joda-time "2.1"]
                 [commons-codec "1.5"]
                 [commons-lang "2.6"]]
  :dev-dependencies [[org.clojure/clojure "1.3.0"]
                     [ring/ring-devel "1.1.5"]
                     [net.cgrand/moustache "1.1.0"
                      :exclusions [org.clojure/clojure]]]
  :profiles {:dev
             {:dependencies [[org.clojure/clojure "1.3.0"]
                             [ring/ring-devel "1.1.5"]
                             [net.cgrand/moustache "1.1.0"
                              :exclusions [org.clojure/clojure]]]
              :plugins [[lein-swank "1.4.4"]]}})
