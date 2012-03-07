;;
;; Bishop provides a Webmachine-like framework for REST
;; applications. While I obviously owe a lot to the Webmachine
;; project, I spent a lot of time reading over Sean Cribbs' code for
;; Webmachine Ruby as well.
;;
;; https://github.com/basho/webmachine
;; https://github.com/seancribbs/webmachine-ruby
;;
(ns com.tnrglobal.bishop.core
  (:use [com.tnrglobal.bishop.flow]
        [com.tnrglobal.bishop.resource]
        [ring.middleware.reload]
        [ring.middleware.stacktrace])
  (:require [clojure.string :as string]))


(defn tokenize-uri
  "Returns a sequence of tokens for the provided URI."
  [uri]

  ;; trim all of our tokens
  (map #(.trim %)

       ;; filter out empty tokens (i.e. leading "/" or "//")
       (filter #(< 0 (count (.trim %)))

               ;; split our URI on the slashes
               (string/split uri #"/"))))

(defn handler
  "Creates a new Bishop handler that will route requests to the
  appropriate resource function based on the values in the
  routing-map."
  [routing-map]

  ;; return a ring handler function
  (fn [request]

    ;; spit out the request for debugging
    (print "Request: " (str request))

    ;; tokenize the URL
    (let [uri-tokens (tokenize-uri (:uri request))]
      (print "\n\nURI Tokens: " (apply str (interpose ", " uri-tokens)) "\n\n")


      (cond

        (routing-map uri-tokens)
        (run request (routing-map uri-tokens))

        (routing-map ["*"])
        (run request (routing-map ["*"]))

        :else
        {:status 404 :body "Resource not found"}))))

(defn resource
  "Defines a new resource and, optionally, provides a set of functions
for handling callbacks. The handler map should contain keys named
after a specific content type 'text/html' and the value stored under
that key should be a function that will provide the body response for
the request. This function should be in the form (fn [request
response] ...)"
  ([response-map handler-map]

     ;; combine the default handlers with the map provided
     {:handlers (merge (default-handlers) handler-map)
      :response response-map})

  ([response-map]

     ;; use our default set of handlers
     (resource response-map (default-handlers))))

(defn halt-resource
  "Returns a resource that halt processing and returns the specified
  status code."
  [status]
  (resource [:halt status] nil))

(defn error-resource
  "Returns a resource that returns a 500 status and the provided term
  as the body."
  [term]
  (resource [:error term] nil))

(defn app
  "Creates a new Ring handler representing the Bishop application."
  [routing-map]
  (-> (handler routing-map)
      (wrap-stacktrace)))