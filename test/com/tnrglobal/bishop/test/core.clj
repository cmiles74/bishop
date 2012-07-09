;;
;; Provides functions to test the functions in the bishop.core
;; namespace.
;;
(ns com.tnrglobal.bishop.test.core
  (:use [com.tnrglobal.bishop.core]
        [com.tnrglobal.bishop.utility]
        [clojure.test])
  (:require [clojure.java.io :as io]
            [clojure.string :as string]))

(def test-routes
  {["greet" :name] (resource {"text/plain"
                              (fn [r]
                                {:body (str "Hello, " (:name (:path-info r))
                                            "!")})})
   ["greet"] (resource {"text/plain" "Hello, Somebody Someone!"})
   ["*"] (resource {"text/plain" "Resources: /greet, /greet/:name"})})

(def test-handler (handler test-routes))

(deftest routing

  (testing "Resource and Parameter"
    (let [response (test-handler {:request-method :get
                                  :uri "/greet/Emily"
                                  :scheme "http"
                                  :headers {"accept" "*/*"}})]
      (is (= "Hello, Emily!" (:body response)))))

  (testing "Resource Only, Ending with Slash"
    (let [response (test-handler {:request-method :get
                                  :uri "/greet/"
                                  :scheme "http"
                                  :headers {"accept" "*/*"}})]
      (is (= "Hello, Somebody Someone!" (:body response)))))

  (testing "Resource Only"
    (let [response (test-handler {:request-method :get
                                  :uri "/greet/"
                                  :scheme "http"
                                  :headers {"accept" "*/*"}})]
      (is (= "Hello, Somebody Someone!" (:body response)))))

  (testing "No Resource (Matches Root)"
    (let [response (test-handler {:request-method :get
                                  :uri "/"
                                  :scheme "http"
                                  :headers {"accept" "*/*"}})]
      (is (= "Resources: /greet, /greet/:name" (:body response))))))