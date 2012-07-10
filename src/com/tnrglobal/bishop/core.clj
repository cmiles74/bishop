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
        [com.tnrglobal.bishop.resource])
  (:require [clojure.string :as string]))


(defn tokenize-uri
  "Returns a sequence of tokens for the provided URI."
  [uri]

  ;; trim all of our tokens and return a vector
  (vec (map #(.trim %)

            ;; filter out empty tokens (i.e. leading "/" or "//")
            (filter #(< 0 (count (.trim %)))

                    ;; split our URI on the slashes
                    (string/split uri #"/")))))

(defn match-route
  "Attempts to match the provided route to the provided URI tokens. If
  the route can be successfully applied to the URI tokens, a map is
  returned that contains the URI tokens, matched route and a map of
  the the route's keyword tokens to their respective URI tokens. If
  the route cannot be applied then nil is returned."
  [uri-tokens route-and-handler]

  ;; we only need the route
  (let [route (first route-and-handler)]

       ;; the route matches if it has the name number of tokens as the
       ;; URI or less tokens than the URI and ends with "*". If we
       ;; have a URI that tokenized to the empty set, it may match
       ;; either the wildcard route ["*"] or the root route [].
       (if (or (= (count route) (count uri-tokens))
               (and (>= (count uri-tokens) (count route))
                    (= "*" (last route)))
               (and (not (seq uri-tokens))
                    (= [] route))
               (and (not (seq uri-tokens))
                    (= ["*"] route)))


         (cond

           ;; the route is an exact match, we don't need to perform
           ;; any more processing
           (= route uri-tokens)
           {:path-tokens uri-tokens}

           ;; the URI is the empty set as is the route, no more
           ;; processing is needed
           (and (not (seq uri-tokens)) (= route []))
           {:path-tokens uri-tokens}

           ;; the URI is the empty set and the route is the wildcard,
           ;; no more processing is needed
           (or (not (seq uri-tokens)) (= route ["*"]))
           {:path-tokens uri-tokens}

           :else
           ;; attempt to match the route to the URI tokens
           (loop [rtoks route utoks (concat uri-tokens [nil]) info {}]

             ;; loop as long as we have an info map and more URI tokens
             (if (and info (seq (rest utoks)))

               (recur

                ;; either the next route token or a sequence containing the
                ;; last route token (if it's a "*", it will match the rest
                ;; of the URI tokens
                (if (seq (rest rtoks))
                  (rest rtoks) [(last rtoks)])
                (rest utoks)

                ;; build up our path map
                (cond

                  ;; if the route token is a keyword then it maps to this
                  ;; URI token
                  (keyword? (first rtoks))
                  (assoc info (first rtoks) (first utoks))

                  ;; if the route and URI tokens match, we can continue to
                  ;; match tokens
                  (= (first rtoks) (first utoks))
                  info

                  ;; if the route token is a start then it's a match to
                  ;; this URI token and we can continue to match tokens
                  (= "*" (first rtoks))
                  info

                  ;; this route token doesn't match this URI token meaning
                  ;; that this route cannot be applied to this URI
                  :else
                  nil))
               (if info
                 {:path-tokens uri-tokens
                  :path-info info})))))))

(defn select-route
  "Returns a path info map containing the route that should handle the
  response for the provided URI tokens, a mapping of route components
  to URI components and the tokenized URI."
  [routing-map uri-tokens]

  ;; the first matching route wins
  (first

   ;; filter out any routes that are nil
   (filter #(not (nil? %))

           ;; map over all of the routs
           (map (fn [route-in]

                  ;; try to match the route
                  (let [path-info (match-route uri-tokens route-in)]

                    ;; if the route matches, return it and it's path
                    ;; info, otherwise nil
                    (if path-info [(first route-in) path-info]
                        nil)))

                routing-map))))

(defn raw-handler
  "Creates a new Bishop handler that will process incoming requests by
  applying them to the provided resource. The provided handler
  presumes that routing has already been handled (i.e. by using
  another middleware such as Moustache."
  [resource]

  ;; return a ring handler function that processes the request
  (fn [request]
    (run request resource)))

(defn handler
  "Creates a new Bishop handler that will route requests to the
  appropriate resource function based on the values in the
  routing-map."
  [routing-map]

  ;; return a ring handler function
  (fn [request]

    ;; tokenize the URL
    (let [route-info (select-route routing-map
                                   (tokenize-uri (:uri request)))
          route (if route-info (routing-map (first route-info)) nil)
          path-info (if route-info (second route-info) nil)]

      (cond

        ;; run the route through the state machine
        (map? route)
        (run (merge request path-info) route)

        ;; we have an invalid route, no resource available
        :else
        {:status 404 :body "Resource not found"}))))

(defn resource
  "Defines a new resource, these come in two different forms:

   1. A map where the keys are different MIME type signatures and
      their values are mapped to a function that will accept the
      request map and return data of that type. These represent
      dynamic responses.

   2. A map where the keys are different MIME type signatures and
      their values are mapped to a result of that type. This could be
      a String, binary data, etc. In general these represent static
      responses.

   In addition to the maps above, you may also pass an optional second
   parameter that contains a map of callback keys and the values for
   these keys. You need not define all the keys, only those that you
   wish to over-ride. Take a look at the com.tnrglobal.bishop.resource
   file and inspect the 'default-handlers' function for a list of the
   valid keys and their default values."
  ([response-map handler-map]
     ;; combine the default handlers with the map provided
     {:handlers (merge (default-handlers)
                       handler-map
                       {:content-types-provided (keys response-map)})
      :response response-map})

  ([response-map]

     ;; use our default set of handlers
     (resource response-map (default-handlers))))

(defn halt-resource
  "Returns a resource that halt processing and returns the specified
  status code. You may optionally provide a response map that will be
  merged into the outgoing response (for instance, if you wanted to
  provide a body)."
  [status & response-map]
  (resource {"*/*" (fn [request] (merge {:status status}
                                        (first response-map)))}))

(defn error-resource
  "Returns a resource that returns a 500 status and the provided term
  as the body."
  [term]
  (resource {"*/*" (fn [request] {:status 500
                                  :body term})}))
