;;
;; A sample Bishop webservice.
;;
(ns com.tnrglobal.bishop.sample
  (:use [ring.adapter.jetty]
        [ring.middleware.reload]
        [ring.middleware.stacktrace])
  (:require [com.tnrglobal.bishop.core :as bishop])
  (:import [java.util Date]))

;; defines a resource that says hello
(def hello
  (bishop/resource
   {"text/html" (fn [request]
                  (str "<html><body><p>Hello "
                       (:name (:info (:path-info request)))
                       "! at "(Date.)
                       "</p></body></html>\n\n"))
    "text/xml"  (fn [request]
                  (str "<message><text>Hello "
                       (:name (:tokens (:path-info request)))
                       " at "(Date.)
                       "!</text></message>"))}))

;; defines a resource that will handle any un-mapped URI request
(def catchall
  (bishop/resource
   {"text/html" (fn [request]
                  (str "What? What?!"))}))

;; creates a simple Bishop application that routes incoming requests
;; to "/hello" to our hello-resource function
(def routes
  {["hello" :name]  hello ;; sample hello world handler

   ;; a resource that returns a static value
   ["static"] (bishop/resource {"text/html" (str "<html><body><p>"
                                                 "This is a static "
                                                 "response.</p>"
                                                 "</body></html>")
                                "text/xml" (str "<message><text>This"
                                                " is a static "
                                                "response.</text>"
                                                "</message>")})

   ;; a resource that always returns a 403 code
   ["halt"]   (bishop/halt-resource 403)

   ;; a resource that always returns a 500 code
   ["error"]  (bishop/error-resource "An error occurred")

   ;; our catch-all handler
   ["*"]      catchall})

(defn app
  "Creates a new Ring handler representing the Bishop application. In
  addition to the Bishop handler we're also using the
  'wrap-stacktrace' middleware for prettier errors."
  []
  (-> (bishop/handler routes)
      (wrap-stacktrace)))