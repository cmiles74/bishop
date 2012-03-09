;;
;; Provides functions to test the functions in the bishop.flow
;; namespace.
;;
(ns com.tnrglobal.bishop.test.flow
  (:use [com.tnrglobal.bishop.core]
        [com.tnrglobal.bishop.flow]
        [clojure.test]))

(def test-request
  {:remote-addr "0:0:0:0:0:0:0:1%0"
   :scheme :http
   :query-params {}
   :form-params {}
   :request-method :get
   :query-string nil
   :content-type nil
   :uri "/"
   :server-name "localhost"
   :params {}
   :headers {"user-agent" "curl/7.21.4 (universal-apple-darwin11.0) libcurl/7.21.4 OpenS\nSL/0.9.8r zlib/1.2.5"
             "accept" "*/*"
             "host" "localhost:8080"}})

(deftest flow

  (testing b13
    (let [res (resource {"text/plain" "testing..."}
                        {:service-available? (fn [request] false)})
          req test-request]
      (= 503 (:status (run req res))))))
