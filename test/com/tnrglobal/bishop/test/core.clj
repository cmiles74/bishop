;;
;; Provides functions to test the functions in the bishop.core
;; namespace.
;;
(ns com.tnrglobal.bishop.test.core
  (:use [com.tnrglobal.bishop.core]
        [com.tnrglobal.bishop.utility]
        [clojure.test])
  (:require [net.cgrand.moustache :as moustache]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(def test-routes-webmachine
  {["greet" :name] (resource {"text/plain"
                              (fn [r]
                                {:body (str "Hello, " (:name (:path-info r))
                                            "!")})})
   ["greet"] (resource {"text/plain" "Hello, Somebody Someone!"})
   ["*"] (resource {"text/plain" "Resources: /greet, /greet/:name"})})

(def test-handler-webmachine (handler test-routes-webmachine))

(def test-handler-moustache
  (moustache/app
   ["greet"] (raw-handler
              (resource {"text/plain" "Hello, Somebody Someone!"}))
   ["greet" name] (raw-handler
                   (resource {"text/plain"
                              (fn [r]
                                {:body (str "Hello, " name "!")})}))
   [&] (raw-handler
        (resource {"text/plain" "Resources: /greet, /greet/:name"}))))

(deftest routing-webmachine

  (testing "Resource and Parameter"
    (let [response (test-handler-webmachine {:request-method :get
                                             :uri "/greet/Emily"
                                             :scheme "http"
                                             :headers {"accept" "*/*"}})]
      (is (= "Hello, Emily!" (:body response)))))

  (testing "Resource Only"
    (let [response (test-handler-webmachine {:request-method :get
                                             :uri "/greet"
                                             :scheme "http"
                                             :headers {"accept" "*/*"}})]
      (is (= "Hello, Somebody Someone!" (:body response)))))

  (testing "No Resource (Matches Root)"
    (let [response (test-handler-webmachine {:request-method :get
                                             :uri "/"
                                             :scheme "http"
                                             :headers {"accept" "*/*"}})]
      (is (= "Resources: /greet, /greet/:name" (:body response))))))

(deftest routing-moustache

  (testing "Resource and Parameter"
    (let [response (test-handler-moustache {:request-method :get
                                            :uri "/greet/Emily"
                                            :scheme "http"
                                            :headers {"accept" "*/*"}})]
      (is (= "Hello, Emily!" (:body response)))))

  (testing "Resource Only"
    (let [response (test-handler-moustache {:request-method :get
                                            :uri "/greet"
                                            :scheme "http"
                                            :headers {"accept" "*/*"}})]
      (is (= "Hello, Somebody Someone!" (:body response)))))

  (testing "No Resource (Matches Root)"
    (let [response (test-handler-moustache {:request-method :get
                                            :uri "/"
                                            :scheme "http"
                                            :headers {"accept" "*/*"}})]
      (is (= "Resources: /greet, /greet/:name" (:body response))))))