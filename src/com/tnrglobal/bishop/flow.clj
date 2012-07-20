;;
;; Provides the transition table that models the webmachine decision
;; tree.
;;
(ns com.tnrglobal.bishop.flow
  (:use [clojure.java.io]
        [com.tnrglobal.bishop.utility])
  (:import [org.apache.commons.codec.digest DigestUtils]
           [java.io ByteArrayOutputStream]
           [org.joda.time DateTime]
           [org.joda.time.format DateTimeFormat])
  (:require [com.tnrglobal.bishop.encoding :as encoding]
            [clojure.string :as string]))

(defn apply-callback
  "Invokes the provided callback function on the supplied resource."
  [request resource callback]
  ((callback (:handlers resource)) request))

(defn apply-callback-compat
  "Invokes the provided callback function on the supplied resource."
  [request resource callback]
  (let [result ((callback (:handlers resource)) request)]
    (cond

      ;; return the map
      (map? result) result

      ;; backward compatibilty, an callback function returned [true
      ;; <something>] and is being handled by code that didn't call
      ;; apply-merge-callback
      (and (coll? result) (= true (first result))) (second result)

      ;; return the result
      :else result)))

(defn apply-merge-callback
  "Applies the specified callback to the provided resource. If the
  callback returns a response map then that map is merged with the
  provided response and returned. If the callback returns a sequence,
  the first item in that sequence is our boolean result and the
  remainder is treated as the respons body. If the callback returns
  boolean true or false, it is simply wrapped in a sequence."
  [request resource response callback]
  (let [result ((callback (:handlers resource)) request)]
    (cond

      ;; map result is the same as true
      (and result (map? result))
      [true (merge-responses response result)]

      ;; sequence result, the first item is our result
      (and result (coll? result))
      [(first result) (merge-responses response (second result))]

      (and result (not (map? result)))
      [true response]

      :else
      [false response])))

(defn apply-merge-callback-decide
  "Applies the specified callback to the provided resource. If the
  callback returns true or a response map, the true-fn will be called
  with either the current response map or the current response map
  merged with the result of the callback function. If the callback
  function returns false, the the false-fn will be called with the
  provided response."
  [request resource response callback true-fn false-fn]
  (let [result (apply-merge-callback request resource response callback)]
    (cond

      (and (coll? result) (first result))
      (true-fn (second result))

      (coll? result)
      (false-fn (second result))

      :else
      (false-fn response))))

(defn decide
  "Calls the provided test function (test-fn). If the function's
  return value matches the true-condition value then the true-fn is
  returned. If the test function returns a number (indicating an HTTP
  response code) then that response code is returned. In all other
  cases the false-fn is returned. If no true-condition value is
  supplied then boolean true is used."
  ([test-fn true-condition true-fn false-fn]

     (let [result (test-fn)]
       (cond (= true-condition result)
             true-fn

             (number? result)
             result

             :else
             false-fn)))
  ([test-fn true-fn false-fn]
     (decide test-fn true true-fn false-fn)))

(defn encoding-function
  "Returns the encoding function with the assigned key for the
  provided request and resource."
  [encoding resource request]
  (second (some #(= encoding (first %))
                (apply-callback request resource :encodings-provided))))

(defn get-header
  "Returns the selecte header from the provided response, regardless
  of case."
  [response header]

  ;; compute our lower and title header
  (let [lower-header (.toLowerCase header)
        title-header (header-to-titlecase header)]
    (cond

      ;; we have no response headers
      (not (:headers response))
      nil

      ;; lower-header present
      ((:headers response) lower-header)
      ((:headers response) lower-header)

      ;; title header present or header absent
      :else
      ((:headers response) title-header))))

(defn caching-headers
  "Returns a sequence with the appropriate caching headers for the
  provided resource attached."
  [resource request response]
  (let [etag (apply-callback request resource :generate-etag)
        expires (apply-callback request resource :expires)
        modified (apply-callback request resource :last-modified)
        response-out {:headers (merge (if etag {"etag" (make-quoted etag)})
                                      (if expires {"expires"
                                                   (header-date expires)})
                                      (if modified {"last-modified"
                                                    (header-date modified)}))}]
    (if (not (nil? (:headers response-out)))
      (merge-responses response response-out)
      response)))

(defn suggested-content-type
  "Returns the content-type that is most similar to what the client is
  requesting. If the negotiation of the content type and character set
  have completed, then they will be used to construct the returned
  content-type. If this has not completed or if a content-type could
  not be negotiated, this function will return a content type most
  similar to what the client is requesting. If there is no common
  ground between the client request and the resource, the first
  content-type and the first character set supported by the resource
  will be used.

  The behavior of this function may be customized with the following
  keys.

    :override-content Content type instead of negotiated; nil
    :override-charset Character set to use instead of negotiatied; nil
    :default-content Content type to use if one can't be chosen; first
      content-type provided by the resource
    :default-charset Character set to use if one can't be chosen; first
      character set provided by the resource"
  [resource request response & {:keys [override-content override-charset
                                       default-content default-charset]
                                :or {override-content nil
                                     override-charset nil
                                     default-content nil
                                     default-charset nil}}]
  (let [header-content-type (get-header response "content-type")
        acceptable-type (cond

                          ;; if the content type header has been
                          ;; manually set, it takes precedence
                          (and header-content-type
                               (first (string/split
                                       header-content-type
                                       #";")))
                          (first (string/split
                                  header-content-type #";"))

                          ;; the negotiated type or the override is
                          ;; the next best
                          (:acceptable-type request)
                          (if override-content
                            override-content
                            (:acceptable-type request))

                          ;; we're desperate, use the first content
                          ;; type offered or the provided default
                          :else
                          (if default-content
                            default-content
                            (first (keys (:response resource)))))

        acceptable-charset (cond

                             ;; if the content type header has a
                             ;; character set, use that
                             (and (:headers response)
                                  header-content-type
                                  (re-find #"charset=(.+);?"
                                           header-content-type))
                             (second (re-find #"charset=(.+);?"
                                              header-content-type))

                             ;; the negotiated charset or the override
                             ;; is the next best
                             (:acceptable-charset request)
                             (if override-charset
                               override-charset
                               (:acceptable-charset request))

                             ;; use the first provided character set
                             ;; or the provided default
                             :else
                             (if default-charset
                               default-charset
                               (first (apply-callback request
                                                      resource
                                                      :charsets-provided))))]
    (str acceptable-type "; charset=" acceptable-charset)))

(defn ensure-content-type
  "Calculates and sets the content type for the provided request and
  response, a new response map is returned. If the negotiation of the
  content type hasn't yet been determined, then \"text/plain\" is
  used. If the negotiation of the character set has not yet been
  determined then the first character set provided by the resource is
  used."
  [resource request response]
  (let [suggested (suggested-content-type resource request response
                                          :default-content "text/plain")]
    (merge-responses (assoc response :headers
                            (dissoc (:headers response) "Content-Type"))
                     {:headers {"content-type" suggested}})))

(defn add-body
  "Calculates and appends the body to the provided request."
  [resource request response]
  (if (nil? (:body response))

    ;; get the body and add it to the response
    (cond

      ;; the resource contains a map of content types and return
      ;; values or functions
      (map? resource)
      (let [responder (resource (:acceptable-type request))

            ;; associate our content-type and character set with the
            ;; outgoing response
            response-out (ensure-content-type resource request response)]

        (cond

          ;; invoke the response function
          (fn? responder)
          (merge-responses response-out
                           (responder request))

          ;; merge bishop's response with the provided map
          (map? responder)
          (merge-responses response-out
                           responder)

          ;; return the response value
          :else
          (merge-responses response-out
                 {:body responder}))))

    ;; return the response as-is
    response))

(defn return-code
  "Returns a function that returns a sequence including the response
  code, the request, the response (with the status correctly set to
  the provided code) and a map of state data representing the decision
  flow. This function represents the end of the run."
  [code request response state]
  [code
   request
   (assoc (assoc response :status code)
     :headers (headers-to-titlecase (:headers response)))
   state])

(defn response-ok
  "Returns a function that will return a 200 response code and add the
  provided node (a keyword) to the state. If passed a response with an
  existing :status value then that value is sent instead of a 200."
  [resource request response state node]
  #(return-code (if (:status response) (:status response) 200)
                request
                (ensure-content-type resource request response)
                (assoc state node true)))

(defn response-code
  "Returns a function that will return a response code and add the
  provided node (a keyword) to the state."
  [resource code request response state node]
  #(return-code code
                request
                (ensure-content-type resource request response)
                (assoc state node false)))

(defn respond
  "This function provides an endpoint for our processing pipeline, it
  returns the final response map for the request. If the body isn't
  yet attached it will be attached here."
  [[code request response state] resource]

  (if (and (:acceptable-encoding request)
           (not (= "identity" (:acceptable-encoding request))))

    (let [encoder (encoding-function (:acceptable-encoding request)
                                     resource
                                     request)
          encoded-body (encoder (:body response))]
      (merge-responses response
                       {:body encoded-body
                        :headers {"Content-Encoding"
                                  (:acceptable-encoding request)}
                        :status code}))

    (assoc response :status code)))

;; states

(defn o18b
  "Test if there are multiple choices for this resource"
  [resource request response state]
  (apply-merge-callback-decide
   request resource response :multiple-representations
   #(response-code resource 300 request % state :o18b)
   #(response-ok resource request % state :o18b)))


(defn o18
  "Test if there are multiple representations for this resource"
  [resource request response state]
  (if (or (= :get (:request-method request))
          (= :head (:request-method request)))

    ;; add our caching headers and the response body to the response
    (let [headers (caching-headers resource request response)
          response-out (merge-responses
                        (add-body (:response resource) request response)
                        headers)]

      ;; the reponse indicates a specific status code, bail now
      (if (:status response-out)
        (response-code resource
                       (:status response-out)
                       request
                       response-out
                       state
                       :o18)

        ;; processing continues normally
        #(o18b resource request response-out (assoc state :o18 true))))

    #(o18b resource request response (assoc state :o18 false))))

(defn o20
  "Test if response includes an entity"
  [resource request response state]
  (if (nil? (:body response))
    (response-code resource 204 request response state :o20)
    #(o18 resource request response (assoc state :o20 false))))

(defn p11
  "Test if this is a new resource"
  [resource request response state]
  (if (not (some #(= "Location" %) (keys (:headers response))))
    #(o20 resource request response (assoc state :p11 true))
    (response-code resource 201 request response state :p11)))

(defn p3
  "Test if there is a conflict"
  [resource request response state]
  (apply-merge-callback-decide
   request resource response :is-conflict?
   #(response-code resource 409 request % state :p3)
   #(p11 resource
         request
         (add-body (:response resource) request %)
         (assoc state :p3 false))))

(defn o14
  "Test if there is a conflict"
  [resource request response state]
  (apply-merge-callback-decide
   request resource response :is-conflict?
   #(response-code resource 409 request % state :o14)
   #(p11 resource
         request
         (add-body (:response resource) request %)
         (assoc state :o14 false))))

(defn o16
  "Test if this is an HTTP PUT request"
  [resource request response state]
  (if (= :put (:request-method request))
    #(o14 resource request response (assoc state :o16 true))
    #(o18 resource request response (assoc state :o16 false))))

(defn n11
  "Test if this is a redirect"
  [resource request response state]
  (let [create (apply-callback request resource :post-is-create?)]
    (cond

      ;; yep, we're creating!
      create
      (try

        (let [create-path (apply-callback request resource :create-path)
              base-uri (apply-callback request resource :base-uri)]
          (if (nil? create-path)
            (throw (Exception. (str "Invalid resource, no create-path")))

            ;; redirect to the create path
            (let [uri (str (if (nil? base-uri) (:uri request) base-uri)
                           create-path)
                  request-out (merge request
                                     {:uri uri
                                      :request-method :put})
                  response-out (add-body (:response resource)
                                         request-out
                                         (merge-responses
                                          response
                                          {:headers {"Location" uri}}))]

              ;; return a 303 unless the response contains a status code
              #(response-code resource
                              (if (:status response-out)
                                (:status response-out) 303)

                              request-out

                              ;; if we aren't returning a 303, remove
                              ;; the location header
                              (if (and (:status response-out)
                                       (not= 303 (:status response-out)))
                                (assoc response-out :headers
                                       (dissoc (:headers response-out)
                                               "Location"))
                                response-out)

                              state :n11))))
        (catch Exception e

          ;; don't return a 303, we caught an exception
          #(response-code resource
                          500
                          request
                          {:body (.getMessage e)}
                          state :n11)))

      ;; not a create
      (= false create)
      (let [process-post (apply-callback request resource :process-post)]
        (cond

          ;; status code returned
          (number? process-post)
          (response-code resource
                         process-post
                         request
                         response
                         state :n11)

          ;; boolean true returned
          (= true process-post)
          (response-code resource
                         204
                         request
                         response
                         state :n11)

          ;; map returned
          (map? process-post)
          (cond

            ;; we have a status code
            (:status process-post)
            (response-code resource
                           (:status process-post)
                           request
                           process-post
                           state n11)

            :else
            #(p11 resource
                  request
                  process-post
                  (assoc state :n11 false)))

          :else
          (throw (Exception. (str "Process post invalid"))))))))

(defn n16
  "Test if this is an HTTP POST request"
  [resource request response state]
  (if (= :post (:request-method request))
    #(n11 resource request response (assoc state :n16 true))
    #(o16 resource request response (assoc state :n16 false))))

(defn n5
  "Test if POST to missing resource is allowed"
  [resource request response state]
  (apply-merge-callback-decide
   request resource response :allow-missing-post?
   #(n11 resource request % (assoc state :n5 true))
   #(response-code resource 410 request % state :n5)))

(defn m20b
  "Test if the DELETE complete"
  [resource request response state]
  (apply-merge-callback-decide
   request resource response :delete-completed?
   #(o20 resource request % (assoc state :m20b true))
   #(response-code resource 202 request % state :m20b)))

(defn m20
  "Test if the delete was successfully enacted immediately"
  [resource request response state]
  (let [delete-resource (apply-callback request resource :delete-resource)]
    (if delete-resource
      #(m20b resource request response (assoc state :m20 true))
      (response-code resource 500 request response state :m20))))

(defn m16
  "Test if this is an HTTP DELETE request"
  [resource request response state]
  (if (= :delete (:request-method request))
    #(m20 resource request response (assoc state :m16 true))
    #(n16 resource request response (assoc state :m16 false))))

(defn m7
  "Test if POST to missing resource is allowed"
  [resource request response state]
  (apply-merge-callback-decide
   request resource response :allow-missing-post?
   #(n11 resource request % (assoc state :m7 true))
   #(response-code resource 404 request % state :m7)))

(defn m5
  "Test if this is an HTTP POST request"
  [resource request response state]
  (if (= :post (:request-method request))
    #(n5 resource request response (assoc state :m5 true))
    (response-code resource 410 resource request response :m5)))

(defn l17
  "Test if Last-Modified is later than If-Modified-Since"
  [resource request response state]
  (let [last-modified-in (apply-callback request resource :last-modified)
        last-modified (if (instance? java.util.Date last-modified-in)
                        (DateTime. (.getTime last-modified-in))
                        last-modified-in)
        if-modified-since (:if-modified-since request)]
    (if (> (.compareTo (.toLocalDateTime last-modified)
                       (.toLocalDateTime if-modified-since))
           0)
      #(m16 resource request response (assoc state :l17 false))
      (response-code resource 304 request response state :l17))))

(defn l15
  "Test if If-Modified-Since is later than now"
  [resource request response state]
  (if (> (.compareTo (.toLocalDateTime (:if-modified-since request))
                     (.toLocalDateTime (DateTime.))) 0)
    #(m16 resource request response (assoc state :l15 true))
    #(l17 resource request response (assoc state :l15 false))))

(defn l14
  "Test if If-Modified-Since is valid date"
  [resource request response state]
  (try
    (let [date (parse-header-date (header-value "if-modified-since"
                                                (:headers request)))]
      #(l15 resource
            (assoc request :if-modified-since date)
            response
            (assoc state l14 true)))
    (catch Exception exception
      #(m16 resource request response (assoc state :l14 false)))))

(defn l13
  "Test if If-Modified-Since header exists"
  [resource request response state]
  (if (header-value "if-modified-since" (:headers request))
    #(l14 resource request response (assoc state :l13 true))
    #(m16 resource request response (assoc state :l13 false))))

(defn l7
  "Test if this is an HTTP POST request"
  [resource request response state]
  (if (= :post (:request-method request))
    #(m7 resource request response (assoc state :l7 true))
    (response-code resource 404 request response state :l7)))

(defn l5
  "Test if resource moved temporarily"
  [resource request response state]
  (let [moved-temp (apply-callback request resource :moved-temporarily?)]
    (if moved-temp
      (response-code resource 307
                     request
                     (assoc response :headers
                            (merge (:headers response)
                                   {"location" moved-temp}))
                     state :l5)
      #(m5 resource request response (assoc state :l5 false)))))

(defn j18
  "Test if this is an HTTP GET or HEAD request"
  [resource request response state]
  (if (or (= :get (:request-method request))
          (= :head (:request-method request)))
    (response-code resource 304 request response state :j18)
    (response-code resource 412 request response state :j18)))

(defn k13
  "Test if Etag is in If-None-Match header"
  [resource request response state]
  (let [if-none-match-etags
        (map make-unquoted
             (string/split (header-value "if-none-match" (:headers request))
                           #"\s*,\s*"))
        etag (apply-callback request resource :generate-etag)]
    (if (some #(= etag %) if-none-match-etags)
      #(j18 resource request response (assoc state :k13 true))
      #(l13 resource request response (assoc state :k13 false)))))

(defn k5
  "Test if resource Moved permanently"
  [resource request response state]
  (let [moved-permanently (apply-callback request resource :moved-permanently?)]
    (if moved-permanently
      (response-code resource
                     301
                     request
                     (assoc response :headers
                            (merge (:headers response)
                                   {"location" moved-permanently}))
                     state :k5)
      #(l5 resource request response (assoc state :k5 false)))))

(defn k7
  "Test if resource previously existed"
  [resource request response state]
  (decide #(apply-callback request resource :previously-existed?)
          true
          #(k5 resource request response (assoc state :k7 true))
          #(l7 resource request response (assoc state :k7 false))))

(defn i13
  "Test if 'If-None-Match: *' header value exists"
  [resource request response state]
  (let [if-none-match-value (header-value "if-none-match" (:headers request))]
    (if (and if-none-match-value (= "*" if-none-match-value))
      #(j18 resource request response (assoc state :i13 true))
      #(k13 resource request response (assoc state :i13 false)))))

(defn i12
  "Test if If-None-Match header exists"
  [resource request response state]
  (if (header-value "if-none-match" (:headers request))
    #(i13 resource request response (assoc state :i12 true))
    #(l13 resource request response (assoc state :i12 false))))

(defn i4
  "Test if resource moved permanently, then apply PUT to different URI"
  [resource request response state]
  (let [moved-perm (apply-callback request resource :moved-permanently?)]
    (if moved-perm
      (response-code resource
                     301
                     request
                     (assoc response :headers
                            (merge (:headers response)
                                   {"location" moved-perm}))
                     state :i4)
      #(p3 resource request response (assoc state :i4 false)))))

(defn i7
  "Test if this is an HTTP PUT request"
  [resource request response state]
  (if (= :put (:request-method request))
    #(i4 resource request response (assoc state :17 true))
    #(k7 resource request response (assoc state :i7 false))))

(defn h12
  "Test if Last-Modified is later than If-Unmodified-Since"
  [resource request response state]
  (let [last-modified-in (apply-callback request resource :last-modified)
        last-modified (if (instance? java.util.Date last-modified-in)
                        (DateTime. (.getTime last-modified-in))
                        last-modified-in)
        if-unmodified-since (parse-header-date
                             (header-value "if-unmodified-since"
                                           (:headers request)))]
    (if (and last-modified
             (> (.compareTo (.toLocalDateTime last-modified)
                            (.toLocalDateTime if-unmodified-since))
                0))
      (response-code resource 412 request response state :h12)
      #(i12 resource request response (assoc state :h12 false)))))

(defn h11
  "Test if If-Unmodified-Since is valid date"
  [resource request response state]
  (try
    (let [date (parse-header-date (header-value "if-unmodified-since" (:headers request)))]
      #(h12 resource
            (assoc request :if-unmodified-since date)
            response
            (assoc state h11 true)))
    (catch Exception exception
      #(i12 resource request response (assoc state :h11 false)))))

(defn h10
  "Test if If-Unmodified-Since header exists"
  [resource request response state]
  (if (header-value "if-unmodified-since" (:headers request))
    #(h11 resource request response (assoc state :h10 true))
    #(i12 resource request response (assoc state :h10 false))))

(defn h7
  "Test if If-Match header exists"
  [resource request response state]
  (let [if-match-value (header-value "if-match" (:headers request))]
    (if (and if-match-value
             (= "*" (make-unquoted if-match-value)))
      (response-code resource 412 request response state :h7)
      #(i7 resource request response (assoc state :h7 false)))))

(defn g11
  "Test if Etag in If-Match header"
  [resource request response state]
  (let [if-match-etags (map make-unquoted
                            (string/split (header-value "if-match"
                                                        (:headers request))
                                          #"\s*,\s*"))
        etag (apply-callback request resource :generate-etag)]
    (if (some #(= etag %) if-match-etags)
      #(h10 resource request response (assoc state :g11 true))
      (response-code resource 412 request response state :g11))))

(defn g9
  "Test if 'If-Match *' header value exists"
  [resource request response state]
  (let [if-match-value (header-value "if-match" (:headers request))]
    (if (and if-match-value
             (= "*" if-match-value))
      #(h10 resource request response (assoc state :g9 true))
      #(g11 resource request response (assoc state :g9 false)))))

(defn g8
  "Test if If-Match header exists"
  [resource request response state]
  (if (header-value "if-match" (:headers request))
    #(g9 resource request response (assoc state :g8 true))
    #(h10 resource request response (assoc state :g8 false))))

(defn g7
  "Test if resource exists"
  [resource request response state]
  ;; compute our variances now that the headers have been handled, add
  ;; this to our response
  (let [vary (into (apply-callback request resource :variances)
                   (variances request))
        response-varied (merge-responses
                         response
                         {:headers {"vary" (apply str (interpose ", " vary))}})]

    (apply-merge-callback-decide
     request resource response-varied :resource-exists?
     #(g8 resource request % (assoc state :g7 true))
     #(h7 resource request % (assoc state :g7 false)))))

(defn f7
  "Test if acceptable encoding is available"
  [resource request response state]
  (let [headers (header-value "accept-encoding" (:headers request))
        acceptable (acceptable-encoding-type
                    (apply-callback request resource :encodings-provided)
                    headers)]
    (if (empty? acceptable)
      (response-code resource 406 request
                     (assoc response :body
                            (suggested-content-type resource
                                                       request
                                                       response))
                     state :f7)
      #(g7 resource
           (assoc request :acceptable-encoding acceptable)
           response
           (assoc state :f7 true)))))

(defn f6
  "Test if Accept-Encoding header exists"
  [resource request response state]
  (if (header-value "accept-encoding" (:headers request))
    #(f7 resource request response (assoc state :f6 true))
    #(g7 resource request response (assoc state :f6 false))))

(defn e6
  "Test if acceptable charset is available"
  [resource request response state]
  (let [acceptable (acceptable-type
                    (let [charsets (apply-callback request resource
                                                   :charsets-provided)]
                      (if (> 1 (count charsets))
                        (conj charsets "*")
                        charsets))
                    (header-value "accept-charset" (:headers request)))]
    (if acceptable
      #(f6 resource
           (assoc request :acceptable-charset acceptable)
           response
           (assoc state :e6 true))
      (response-code resource 406 request
                     (assoc response :body
                            (suggested-content-type resource
                                                       request
                                                       response))
                     state :e6))))

(defn e5
  "Test if Accept-Charset header exists"
  [resource request response state]
  (if (header-value "accept-charset" (:headers request))
    #(e6 resource request response (assoc state :e5 true))

    ;; the client hasn't provided an accept-charset header, we'll use
    ;; the first character set provided by the resource
    #(f6 resource
         (assoc request :acceptable-charset
                (first (apply-callback request resource :charsets-provided)))
         response
         (assoc state :e5 false))))

(defn d5
  "Test if acceptable language is available"
  [resource request response state]
  (let [acceptable (acceptable-type
                    (let [languages (apply-callback request resource
                                                    :languages-provided)]
                      languages)
                    (header-value "accept-language" (:headers request)))]
    (if acceptable
      #(e5 resource
           (assoc request :acceptable-language acceptable)
           response
           (assoc state :d5 true))
      (response-code resource 406 request
                     (assoc response :body
                            (suggested-content-type resource
                                                       request
                                                       response))
                     state :d5))))

(defn d4
  "Test if Accept-Language header exists"
  [resource request response state]
  (if (header-value "accept-language" (:headers request))
    #(d5 resource request response (assoc state :d4 true))
    #(e5 resource request response (assoc state :d4 false))))

(defn c4
  "Test if acceptable media type is available"
  [resource request response state]
  (let [acceptable (acceptable-content-type
                    resource (header-value "accept" (:headers request)))]
    (if acceptable
      #(d4 resource
           (assoc request :acceptable-type acceptable)
           response
           (assoc state :c4 true))
      (response-code resource 406 request
                     (assoc response :body
                            (suggested-content-type resource
                                                       request
                                                       response))
                     state :c4))))

(defn c3
  "Test if Accept header exists"
  [resource request response state]
  (if (header-value "accept" (:headers request))
    #(c4 resource request response (assoc state :c3 false))
    #(d4 resource request response (assoc state :c3 true))))

(defn b3
  "Test if OPTIONS header exists"
  [resource request response state]
  (if (= :options (:request-method request))
    (response-ok resource
                 request
                  (assoc response :headers
                         (merge (:headers response)
                                (#(apply-callback request resource :options))))
                  state :b3)
    #(c3 resource request response (assoc state :b3 false))))

(defn b4
  "Test if request entity is too large"
  [resource request response state]
  (apply-merge-callback-decide
   request resource response :valid-entity-length?
   #(b3 resource request % (assoc state :b4 true))
   #(response-code resource 413 request % state :b4)))

(defn b5
  "Test if the request content type is a known content type"
  [resource request response state]
  (apply-merge-callback-decide
   request resource response :known-content-type?
   #(b4 resource request % (assoc state :b5 true))
   #(response-code resource 415 request % state :b5)))

(defn b6
  "Test if the Content-* headers are valid"
  [resource request response state]
  (apply-merge-callback-decide
   request resource response :valid-content-headers?
   #(b5 resource request % (assoc state :b6 true))
   #(response-code resource 501 request % state :b6)))

(defn b7
  "Test if this resource is forbidden"
  [resource request response state]
  (apply-merge-callback-decide
   request resource response :forbidden?
   #(response-code resource 403 request % state :b7)
   #(b6 resource request % (assoc state :b6 true))))

(defn b8
  "Test if this request is authorized to access this resource"
  [resource request response state]
  (let [result (apply-callback request resource :is-authorized?)]
    (cond

      ;; they are authorized, merge in the response map
      (map? result)
      #(b7 resource request (merge-responses response result)
           (assoc state :b8 true))

      ;; they are authorized
      (= true result)
      #(b7 resource request response (assoc state :b8 true))

      ;; not authorized, the String response is our authenticate header
      (instance? String result)
      (response-code resource
                     401
                     request
                     (assoc-in response
                               [:headers "WWW-Authenticate"]
                               result)
                     state :b8)

      ;; not authorized, assume the authenticate header was added
      (and (coll? result)
           (= false (first result)))
      (response-code resource 401 request
                     (merge-responses response (second response)) state :b8)

      ;; not authorized and no authenticate header
      :else
      (response-code resource 401 request response state b8))))

(defn b9b
  "Test if this request is Malformed"
  [resource request response state]
  (apply-merge-callback-decide
   request resource response :malformed-request?
   #(response-code resource 400 request % state :b9b)
   #(b8 resource request % (assoc state :b9b false))))

(defn b9a
  "Test if the Content-MD5 is valid"
  [resource request response state]
  (let [valid (apply-callback request resource :validate-content-checksum)]

    (cond

      valid
      #(b9b resource request response (assoc state :b9a true))

      (nil? valid)
      (if (= (header-value "content-md5" (:headers request))
             (DigestUtils/md5Hex (with-open [reader-this (reader (:body request))
                                             buffer (ByteArrayOutputStream.)]
                                   (copy reader-this buffer)
                                   (.toByteArray buffer))))

        #(b9b resource request response (assoc state :b9a true))

        (response-code resource 400 request
                        (assoc response :body
                               "Content-MD5 header does not match request body")
                        state :b9a))

      :else
      (response-code resource 400 request
                      (assoc :body response
                             "Content-MD5 header does not match request body")
                      state :b9a))))

(defn b9
  "Test if the Content-MD5 header exists"
  [resource request response state]
  (decide #(some (fn [[head]]
                   (= "content-md5" head))
                 (:headers request))
          true
          #(b9a resource request response (assoc state :b9 true))
          #(b9b resource request response (assoc state :b9 false))))

(defn b10
  "Test if the request method is allowed"
  [resource request response state]
  (decide #(some (fn [method-in]
                   (= (:request-method request) method-in))
                 (apply-callback request resource :allowed-methods))
          true
          #(b9 resource request response (assoc state :b10 true))
          (response-code
           resource
           405 request
           (assoc-in response [:headers "Allow"]
                     (list-keys-to-upstring
                      (apply-callback request resource :allowed-methods)))
           state :b10)))

(defn b11
  "Test if the request URI is too long"
  [resource request response state]
  (apply-merge-callback-decide
   request resource response :uri-too-long?
   #(response-code resource 414 request % state :b11)
   #(b10 resource request % (assoc state :b11 false))))

(defn b12
  "Test if the request method is a known method"
  [resource request response state]
  (decide #(some (fn [method-in]
                   (= (:request-method request) method-in))
                 (apply-callback request resource :known-methods))
          true
          #(b11 resource request response (assoc state :b12 true))
          (response-code resource 501 request response state :b12)))

(defn b13
  "Is the resource available?"
  [resource request response state]
  (let [available (apply-callback request resource :service-available?)]
    (if (or (not available)
            (map? available))
      (response-code resource
                     503
                     request
                     (if (not available)
                       response
                       (merge-responses response available))
                     state
                     :b13)
      #(b12 resource request response (assoc state :b13 true)))))

(defn start
  "This function is the first stage in our processing tree. It will
  pass the resource and request onto node B13 with an empty response
  and the current machine state."
  [resource request]
  #(b13 resource request {} {}))

(defn run
  "Applies the provided request and resource to our flow state
  machine. At the end of processing, a map will be returned containing
  the response object for the client."
  [request resource]

  (respond

   ;; apply the resource and request to our state machine yeilding a
   ;; sequence containing the response code, request, response and
   ;; machine state
   (trampoline #(start resource request))

   resource))
