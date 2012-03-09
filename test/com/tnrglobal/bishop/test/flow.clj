;;
;; Provides functions to test the functions in the bishop.flow
;; namespace.
;;
(ns com.tnrglobal.bishop.test.flow
  (:use [com.tnrglobal.bishop.core]
        [com.tnrglobal.bishop.flow]
        [clojure.test])
  (:require [clojure.java.io :as io])
  (:import [java.io StringBufferInputStream]))

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

(deftest states

  ;; Available?

  (testing b13
    (let [res (resource {"text/plain" "testing..."}
                        {:service-available? (fn [request] false)})
          req test-request]
      (is (= 503 (:status (run req res))) "Service unavailable")))

  (testing b13
    (let [res (resource {"text/plain" "testing..."})
          req test-request]
      (is (= 200 (:status (run req res))))))

  ;; Known method?

  (testing b12
    (let [res (resource {"text-plain" "testing"})
          req (assoc test-request :request-method :super-get)]
      (is (= 501 (:status (run req res))) "Not implemented")))

  (testing b12
    (let [res (resource {"text-plain" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  ;; URI too long?

  (testing b11
    (let [res (resource {"text-plain" "testing"}
                        {:uri-too-long? (fn [request] true)})
          req test-request]
      (is (= 414 (:status (run req res))) "Request URI too long")))

  (testing b11
    (let [res (resource {"text-plain" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  ;; Is method allowed on this resource?

  (testing b10
    (let [res (resource {"text-plain" "testing"}
                        {:allowed-methods (fn [request] [:post])})
          req test-request]
      (is (= 405 (:status (run req res))) "Method not allowed")))

  (testing b10
    (let [res (resource {"text-plain" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  ;; Contains "Content-MD5" header?

  (testing b9
    (let [res (resource {"text-plain" "testing"})
          req (merge-with concat
                          test-request
                          {:headers ["content-md5" "e4e68fb7bd0e697a0ae8f1bb342846b3"]
                           :body (StringBufferInputStream. "Test message.")})]
      (is (= 400 (:status (run req res))) "Content-MD5 header does not match request body")))

  (testing b9
    (let [res (resource {"text-plain" "testing"})
          req (merge test-request
                     {:headers (conj (:headers test-request)
                                     ["content-md5" "e4e68fb7bd0e697a0ae8f1bb342846d7"])
                      :body (StringBufferInputStream. "Test message.")})]
      (is (=  (:status (run req res))))))

  (testing b9
    (let [res (resource {"text-plain" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))
  )
