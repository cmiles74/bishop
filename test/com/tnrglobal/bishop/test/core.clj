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
   ["greeting" "*"] (resource {"text/plain" "Hola!"})
   [] (resource {"text/plain" "Welcome to Test Resource"})
   ["*"] (halt-resource 404 {:body (str "Dude, bogus page!")})})

(def test-handler-webmachine (handler test-routes-webmachine))

(def test-handler-moustache
  (moustache/app
   ["greet"] (raw-handler
              (resource {"text/plain" "Hello, Somebody Someone!"}))
   ["greet" name] (raw-handler
                   (resource {"text/plain"
                              (fn [r]
                                {:body (str "Hello, " name "!")})}))
   [&] (raw-handler (halt-resource 404))))

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

  (testing "Route Ends with Wildcard"
    (let [response (test-handler-webmachine {:request-method :get
                                             :uri "/greeting/Howard"
                                             :scheme "http"
                                             :headers {"accept" "*/*"}})]
      (is (= "Hola!" (:body response)))))

  (testing "Empty Route (Maps to Root)"
    (let [response (test-handler-webmachine {:request-method :get
                                             :uri "/"
                                             :scheme "http"
                                             :headers {"accept" "*/*"}})]
      (is (= "Welcome to Test Resource" (:body response)))))

  (testing "Random Route (Maps to Wildcard)j"
    (let [response (test-handler-webmachine {:request-method :get
                                             :uri "/somewhere"
                                             :scheme "http"
                                             :headers {"accept" "*/*"}})]
      (is (= "Dude, bogus page!" (:body response))))))

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
      (is (= 404 (:status response))))))

(deftest handler-test
  (testing "invalid route produces valid ring response"
    (let [handler (handler {})
          response-map (handler {:uri ""})]
      (is (= 404 (:status response-map)))
      (is (= {} (:headers response-map)))
      )))