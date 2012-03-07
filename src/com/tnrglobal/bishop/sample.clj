;;
;; A sample Bishop webservice.
;;
(ns com.tnrglobal.bishop.sample
  (:use [ring.adapter.jetty])
  (:require [com.tnrglobal.bishop.core :as bishop]))

;; defines a resource that says hello
(def hello
  (bishop/resource
   {"text/html" (fn [request]
                  (str "Hello!\n"))}))

;; defines a resource that will handle any un-mapped URI request
(def catchall
  (bishop/resource
   {"text/html" (fn [request]
                  (str "What? What?!"))}))

;; creates a simple Bishop application that routes incoming requests
;; to "/hello" to our hello-resource function
(def hello-app
  (bishop/app {["hello"]  hello
               ["static"] (bishop/resource {"text/html" "This is a static response."})
               ["halt"]   (bishop/halt-resource 403)
               ["error"]  (bishop/error-resource "An error occurred")
               ["*"]      catchall}))