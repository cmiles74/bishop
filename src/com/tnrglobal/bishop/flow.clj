;;
;; Provides the transition table that models the webmachine decision
;; tree.
;;
(ns com.tnrglobal.bishop.flow
  (:use [clojure.java.io]
        [com.tnrglobal.bishop.utility])
  (:import [org.apache.commons.codec.digest DigestUtils]
           [java.io ByteArrayOutputStream]
           [java.util Date Locale TimeZone]
           [java.text SimpleDateFormat]
           [org.apache.commons.lang.time DateUtils])
  (:require [com.tnrglobal.bishop.encoding :as encoding]
            [clojure.string :as string]))

(defn apply-callback
  "Invokes the provided callback function on the supplied resource."
  [request resource callback]
  ((callback (:handlers resource)) request))

(defn encoding-function
  "Returns the encoding function with the assigned key for the
  provided request and resource."
  [encoding resource request]
  (second (some #(= encoding (first %))
                (apply-callback request resource :encodings-provided))))

(defn caching-headers
  "Returns a sequence with the appropriate caching headers for the
  provided resource attached."
  [resource request response]
  (let [etag (apply-callback request resource :generate-etag)
        expires (apply-callback request resource :expires)
        modified (apply-callback request resource :last-modified)]
    (merge (:headers response)
           (if etag {"etag" (make-quoted etag)})
           (if expires {"expires" (header-date expires)})
           (if modified {"last-modified" (header-date modified)}))))

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

(defn add-body
  "Calculates and appends the body to the provided request."
  [resource request response]
  (if (nil? (:body response))

    ;; get the body and add it to the response
    (cond

      ;; the resource contains a map of content types and return
      ;; values or functions
      (map? resource)
      (let [responder (resource (:acceptable-type request))]
        (cond

          ;; invoke the response function
          (fn? responder)
          (merge-responses response (responder request))

          ;; merge bishop's response with the provided map
          (map? responder)
          (merge-responses response responder)

          ;; return the response value
          :else
          (assoc response :body responder))))

    ;; return the response as-is
    response))

(defn return-code
  "Returns a function that returns a sequence including the response
  code, the request, the response (with the status correctly set to
  the provided code) and a map of state data representing the decision
  flow. This function represents the end of the run."
  [code request response state]
  [code request (assoc (assoc response :status code)
                  :headers (headers-to-titlecase (:headers response))) state])

(defn response-ok
  "Returns a function that will return a 200 response code and add the
  provided node (a keyword) to the state. If passed a response with an
  existing :status value then that value is sent instead of a 200."
  [request response state node]
  #(return-code (if (:status response) (:status response) 200)
                request
                response
                (assoc state node true)))

(defn response-code
  "Returns a function that will return a response code and add the
  provide node (a keyword) to the state."
  [code request response state node]
  #(return-code code request response (assoc state node false)))

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
  [resource request response state]
  (if (apply-callback request resource :multiple-representations)
    (response-code 300 request response state :o18b)
    (response-ok request response state :o18b)))


(defn o18
  [resource request response state]
  (if (or (= :get (:request-method request))
          (= :head (:request-method request)))

    ;; add our caching headers and the response body to the response
    (let [headers (caching-headers resource request response)
          response-out (assoc
                        (add-body (:response resource) request response)
                        :headers headers)]

      ;; the reponse indicates a specific status code, bail now
      (if (:status response-out)
        (response-code (:status response-out) request response-out state :o18)

        ;; processing continues normally
        #(o18b resource request response-out (assoc state :o18 true))))

    #(o18b resource request response (assoc state :o18 false))))

(defn o20
  [resource request response state]
  (if (nil? (:body response))
    (response-code 204 request response state :o20)
    #(o18 resource request response (assoc state :o20 false))))

(defn p11
  [resource request response state]
  (if (not (some #(= "Location" %) (keys (:headers response))))
    #(o20 resource request response (assoc state :p11 true))
    (response-code 201 request response state :p11)))

(defn p3
  [resource request response state]
  (if (apply-callback request resource :is-conflict?)
    (response-code 409 request response state :p3)
    #(p11 resource
          request
          (add-body (:response resource) request response)
          (assoc state :p3 false))))

(defn o14
  [resource request response state]
  (if (apply-callback request resource :is-conflict?)
    (response-code 409 request response state :o14)
    #(p11 resource
          request
          (add-body (:response resource) request response)
          (assoc state :o14 false))))

(defn o16
  [resource request response state]
  (if (= :put (:request-method request))
    #(o14 resource request response (assoc state :o16 true))
    #(o18 resource request response (assoc state :o16 false))))

(defn n11
  [resource request response state]
  (let [create (apply-callback request resource :post-is-create?)]
    (cond

      ;; yep, we're creating!
      create
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
            #(response-code 303
                            request-out
                            response-out
                            state :n11))))

      ;; not a create
      (= false create)
      (let [process-post (apply-callback request resource :process-post)]
        (cond

          ;; status code returned
          (number? process-post)
          (response-code process-post
                         request
                         response
                         state :n11)

          (or (nil? process-post)
              (= false process-post))
          (throw (Exception. (str "Process post invalid")))

          :else
          #(p11 resource
                request
                (add-body (:response resource)
                          request
                          (if (map? process-post)
                            (merge-responses response process-post)
                            response))
                (assoc state :n11 false)))))))

(defn n16
  [resource request response state]
  (if (= :post (:request-method request))
    #(n11 resource request response (assoc state :n16 true))
    #(o16 resource request response (assoc state :n16 false))))

(defn n5
  [resource request response state]
  (decide #(apply-callback request resource :allow-missing-post?)
          true
          #(n11 resource request response (assoc state :n5 true))
          (response-code 410 request response state :n5)))

(defn m20b
  [resource request response state]
  (let [delete-complete (apply-callback request resource :delete-completed?)]
    (if delete-complete
      #(o20 resource request response (assoc state :m20b true))
      (response-code 202 request response state :m20b))))

(defn m20
  [resource request response state]
  (let [delete-resource (apply-callback request resource :delete-resource)]
    (if delete-resource
      #(m20b resource request response (assoc state :m20 true))
      (response-code 500 request response state :m20))))

(defn m16
  [resource request response state]
  (if (= :delete (:request-method request))
    #(m20 resource request response (assoc state :m16 true))
    #(n16 resource request response (assoc state :m16 false))))

(defn m7
  [resource request response state]
  (decide #(apply-callback request resource :allow-missing-post?)
          true
          #(n11 resource request response (assoc state :m7 true))
          (response-code 404 request response state :m7)))

(defn m5
  [resource request response state]
  (if (= :post (:request-method request))
    #(n5 resource request response (assoc state :m5 true))
    (response-code 410 resource request response :m5)))

(defn l17
  [resource request response state]
  (let [last-modified (apply-callback request resource :last-modified)
        if-modified-since (:if-modified-since request)]
    (if (> (.getTime last-modified)
           (.getTime if-modified-since))
      #(m16 resource request response (assoc state :l17 false))
      (response-code 304 request response state :l17))))

(defn l15
  [resource request response state]
  (if (> (.getTime (:if-modified-since request))
         (.getTime (Date.)))
    #(m16 resource request response (assoc state :l15 true))
    #(l17 resource request response (assoc state :l15 false))))

(defn l14
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
  [resource request response state]
  (if (header-value "if-modified-since" (:headers request))
    #(l14 resource request response (assoc state :l13 true))
    #(m16 resource request response (assoc state :l13 false))))

(defn l7
  [resource request response state]
  (if (= :post (:request-method request))
    #(m7 resource request response (assoc state :l7 true))
    (response-code 404 request response state :l7)))

(defn l5
  [resource request response state]
  (let [moved-temp (apply-callback request resource :moved-temporarily?)]
    (if moved-temp
      (response-code 307
                     request
                     (assoc response :headers
                            (assoc (:headers response)
                              "location" moved-temp))
                     state :l5)
      #(m5 resource request response (assoc state :l5 false)))))

(defn j18
  [resource request response state]
  (if (or (= :get (:request-method request))
          (= :head (:request-method request)))
    (response-code 304 request response state :j18)
    (response-code 412 request response state :j18)))

(defn k13
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
  [resource request response state]
  (let [moved-permanently (apply-callback request resource :moved-permanently?)]
    (if moved-permanently
      (response-code 301
                     request
                     (assoc response :headers
                            (assoc (:headers response)
                              "location" moved-permanently))
                     state :k5)
      #(l5 resource request response (assoc state :k5 false)))))

(defn k7
  [resource request response state]
  (decide #(apply-callback request resource :previously-existed?)
          true
          #(k5 resource request response (assoc state :k7 true))
          #(l7 resource request response (assoc state :k7 false))))

(defn i13
  [resource request response state]
  (let [if-none-match-value (header-value "if-none-match" (:headers request))]
    (if (and if-none-match-value (= "*" if-none-match-value))
      #(j18 resource request response (assoc state :i13 true))
      #(k13 resource request response (assoc state :i13 false)))))

(defn i12
  [resource request response state]
  (if (header-value "if-none-match" (:headers request))
    #(i13 resource request response (assoc state :i12 true))
    #(l13 resource request response (assoc state :i12 false))))

(defn i4
  [resource request response state]
  (let [moved-perm (apply-callback request resource :moved-permanently?)]
    (if moved-perm
      (response-code 301
                     request
                     (assoc response :headers
                            (assoc (:headers response)
                              "location" moved-perm))
                     state :i4)
      #(p3 resource request response (assoc state :i4 false)))))

(defn i7
  [resource request response state]
  (if (= :put (:request-method request))
    #(i4 resource request response (assoc state :17 true))
    #(k7 resource request response (assoc state :i7 false))))

(defn h12
  [resource request response state]
  (let [last-modified (apply-callback request resource :last-modified)
        if-unmodified-since (parse-header-date
                             (header-value "if-unmodified-since"
                                           (:headers request)))]
    (if (and last-modified (> (.getTime last-modified)
                              (.getTime if-unmodified-since)))
      (response-code 412 request response state :h12)
      #(i12 resource request response (assoc state :h12 false)))))

(defn h11
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
  [resource request response state]
  (if (header-value "if-unmodified-since" (:headers request))
    #(h11 resource request response (assoc state :h10 true))
    #(i12 resource request response (assoc state :h10 false))))

(defn h7
  [resource request response state]
  (let [if-match-value (header-value "if-match" (:headers request))]
    (if (and if-match-value
             (= "*" (make-unquoted if-match-value)))
      (response-code 412 request response state :h7)
      #(i7 resource request response (assoc state :h7 false)))))

(defn g11
  [resource request response state]
  (let [if-match-etags (map make-unquoted
                            (string/split (header-value "if-match"
                                                        (:headers request))
                                          #"\s*,\s*"))
        etag (apply-callback request resource :generate-etag)]
    (if (some #(= etag %) if-match-etags)
      #(h10 resource request response (assoc state :g11 true))
      (response-code 412 request response state :g11))))

(defn g9
  [resource request response state]
  (let [if-match-value (header-value "if-match" (:headers request))]
    (if (and if-match-value
             (= "*" if-match-value))
      #(h10 resource request response (assoc state :g9 true))
      #(g11 resource request response (assoc state :g9 false)))))

(defn g8
  [resource request response state]
  (if (header-value "if-match" (:headers request))
    #(g9 resource request response (assoc state :g8 true))
    #(h10 resource request response (assoc state :g8 false))))

(defn g7
  [resource request response state]

  ;; compute our variances now that the headers have been handled, add
  ;; this to our response
  (let [vary (into (apply-callback request resource :variances)
                   (variances request))
        response-varied (assoc response :headers
                               (merge (:headers response)
                                       {"vary" (apply str (interpose ", " vary))}))]

    (decide #(apply-callback request resource :resource-exists?)
            true
            #(g8 resource request response-varied (assoc state :g7 true))
            #(h7 resource request response-varied (assoc state :g7 false)))))

(defn f7
  [resource request response state]
  (let [headers (header-value "accept-encoding" (:headers request))
        acceptable (acceptable-encoding-type
                    (apply-callback request resource :encodings-provided)
                    headers)]
    (if (empty? acceptable)
      (response-code 406 request response state :f7)
      #(g7 resource
           (assoc request :acceptable-encoding acceptable)
           response
           (assoc state :f7 true)))))

(defn f6
  [resource request response state]
  (if (header-value "accept-encoding" (:headers request))
    #(f7 resource request response (assoc state :f6 true))
    #(g7 resource request response (assoc state :f6 false))))

(defn e6
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
      (response-code 406 request response state :e6))))

(defn e5
  [resource request response state]
  (if (header-value "accept-charset" (:headers request))
    #(e6 resource request response (assoc state :e5 true))
    #(f6 resource request response (assoc state :e5 false))))

(defn d5
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
      (response-code 406 request response state :d5))))

(defn d4
  [resource request response state]
  (if (header-value "accept-language" (:headers request))
    #(d5 resource request response (assoc state :d4 true))
    #(e5 resource request response (assoc state :d4 false))))

(defn c4
  [resource request response state]
  (let [acceptable (acceptable-content-type
                    resource (header-value "accept" (:headers request)))]
    (if acceptable
      #(d4 resource
           (assoc request :acceptable-type acceptable)
           response
           (assoc state :c4 true))
      (response-code 406 request response state :c4))))

(defn c3
  [resource request response state]
  (if (header-value "accept" (:headers request))
    #(c4 resource request response (assoc state :c3 false))
    #(d4 resource request response (assoc state :c3 true))))

(defn b3
  [resource request response state]
  (if (= :options (:request-method request))
    (response-ok request
                  (assoc response :headers
                         (concat (:headers response)
                                 (#(apply-callback request resource :options))))
                  state :b3)
    #(c3 resource request response (assoc state :b3 false))))

(defn b4
  [resource request response state]
  (decide #(apply-callback request resource :valid-entity-length?)
          true
          #(b3 resource request response (assoc state :b4 true))
          (response-code 413 request response state :b4)))

(defn b5
  [resource request response state]
  (decide #(apply-callback request resource :known-content-type?)
          true
          #(b4 resource request response (assoc state :b5 true))
          (response-code 415 request response state :b5)))

(defn b6
  [resource request response state]
  (decide #(apply-callback request resource :valid-content-headers?)
          true
          #(b5 resource request response (assoc state :b6 true))
          (response-code 501 request response state :b6)))

(defn b7
  [resource request response state]
  (decide #(apply-callback request resource :forbidden?)
          true
          (response-code 403 request response state :b7)
          #(b6 resource request response (assoc state :b6 true))))

(defn b8
  [resource request response state]
  (let [result (#(apply-callback request resource :is-authorized?))]
    (cond

      (= true result)
      #(b7 resource request response (assoc state :b8 true))

      (instance? String result)
      (response-code 401
                     request
                     (assoc-in response [:headers "WWW-Authenticate"] result)
                     state :b8)

      :else
      (response-code 401 request response state :b8))))

(defn b9b
  [resource request response state]
  (decide #(apply-callback request resource :malformed-request?)
          true
          (response-code 400 request response state :b9b)
          #(b8 resource request response (assoc state :b9b false))))

(defn b9a
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

        (response-code 400 request
                        (assoc response :body
                               "Content-MD5 header does not match request body")
                        state :b9a))

      :else
      (response-code 400 request
                      (assoc :body response
                             "Content-MD5 header does not match request body")
                      state :b9a))))

(defn b9
  [resource request response state]
  (decide #(some (fn [[head]]
                   (= "content-md5" head))
                 (:headers request))
          true
          #(b9a resource request response (assoc state :b9 true))
          #(b9b resource request response (assoc state :b9 false))))

(defn b10
  [resource request response state]
  (decide #(some (fn [method-in]
                   (= (:request-method request) method-in))
                 (apply-callback request resource :allowed-methods))
          true
          #(b9 resource request response (assoc state :b10 true))
          (response-code
           405 request
           (assoc-in response [:headers "Allow"]
                     (list-keys-to-upstring
                      (apply-callback request resource :allowed-methods)))
           state :b10)))

(defn b11
  [resource request response state]
  (decide #(apply-callback request resource :uri-too-long?)
          true
          (response-code 414 request response state :b11)
          #(b10 resource request response (assoc state :b11 false))))

(defn b12
  [resource request response state]
  (decide #(some (fn [method-in]
                   (= (:request-method request) method-in))
                 (apply-callback request resource :known-methods))
          true
          #(b11 resource request response (assoc state :b12 true))
          (response-code 501 request response state :b12)))

(defn b13
  "Is the resource available?"
  [resource request response state]
  (let [available (apply-callback request resource :service-available?)]
    (if (or (not available)
            (map? available))
      (response-code 503
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
