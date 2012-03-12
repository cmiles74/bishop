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

  (testing "B13 Invalid"
    (let [res (resource {"text/plain" "testing..."}
                        {:service-available? (fn [request] false)})
          req test-request]
      (is (= 503 (:status (run req res))) "Service unavailable")))

  (testing "B13 Valid"
    (let [res (resource {"text/plain" "testing..."})
          req test-request]
      (is (= 200 (:status (run req res))))))

  ;; Known method?

  (testing "B12 Invalid"
    (let [res (resource {"text-plain" "testing"})
          req (assoc test-request :request-method :super-get)]
      (is (= 501 (:status (run req res))) "Not implemented")))

  (testing "B12 Valid"
    (let [res (resource {"text-plain" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  ;; URI too long?

  (testing "B11 Invalid"
    (let [res (resource {"text-plain" "testing"}
                        {:uri-too-long? (fn [request] true)})
          req test-request]
      (is (= 414 (:status (run req res))) "Request URI too long")))

  (testing "B11 Valid"
    (let [res (resource {"text-plain" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  ;; Is method allowed on this resource?

  (testing "B10 Invalid"
    (let [res (resource {"text-plain" "testing"}
                        {:allowed-methods (fn [request] [:post])})
          req test-request]
      (let [res-out (run req res)]
        (is (and (= 405 (:status res-out))
                 (some (fn [[head val]]
                         (= "allow" head)) (:headers res-out))) "Method not allowed"))))

  (testing "B10 Valid"
    (let [res (resource {"text-plain" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  ;; Contains "Content-MD5" header?

  (testing "B9 No Header"
    (let [res (resource {"text-plain" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  (testing "B9 Valid"
    (let [res (resource {"text-plain" "testing"})
          req (merge test-request
                     {:headers (conj (:headers test-request)
                                     ["content-md5" "e4e68fb7bd0e697a0ae8f1bb342846d7"])
                      :body (StringBufferInputStream. "Test message.")})]
      (is (= 400 (:status (run req res))))))

  (testing "B9 Invalid"
    (let [res (resource {"text-plain" "testing"})
          req (merge-with concat
                          test-request
                          {:headers ["content-md5" "e4e68fb7bd0e697a0ae8f1bb342846b3"]
                           :body (StringBufferInputStream. "Test message.")})]
      (is (= 200 (:status (run req res))) "Content-MD5 header does not match request body")))

  ;; is authorized?

  (testing "B8 Valid"
    (let [res (resource {"text-plain" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  (testing "B8 Valid"
    (let [res (resource {"text-plain" "testing"}
                        {:is-authorized? (fn [request] false)})
          req test-request]
      (is (= 401 (:status (run req res))) "Unauthorized")))

    (testing "B8 Invalid"
      (let [res (resource {"text-plain" "testing"}
                          {:is-authorized? (fn [request] "Basic")})
            req test-request
            response (run req res)]
        (is (and (= 200 (:status response))
                 (some (fn [[head value]]
                         (and (= "www-authenticate" head)
                              (= "Basic" value)))
                       (:headers response))) "Authenticate")))

    ;; forbidden?

    (testing "B7 Valid"
      (let [res (resource {"text-plain" "testing"})
            req test-request]
        (is (= 200 (:status (run req res))))))

    (testing "B7 Invalid"
      (let [res (resource {"text-plain" "testing"}
                          {:forbidden? (fn [request] true)})
            req test-request]
        (is (= 403 (:status (run req res))) "Forbidden")))
  )
