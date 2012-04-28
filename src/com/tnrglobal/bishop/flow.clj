;;
;; Provides the transition table that models the webmachine decision
;; tree.
;;
(ns com.tnrglobal.bishop.flow
  (:use [clojure.java.io]
        [clojure.set])
  (:import [org.apache.commons.codec.digest DigestUtils]
           [java.io ByteArrayOutputStream]
           [java.util Date Locale TimeZone]
           [java.text SimpleDateFormat]
           [org.apache.commons.lang.time DateUtils])
  (:require [clojure.string :as string]))

(def HTTP-DATE-FORMAT (doto
                          (SimpleDateFormat.
                           "EEE, dd MMM yyyy HH:mm:ss zzz" Locale/US)
                        (.setTimeZone (TimeZone/getTimeZone "UTC"))))

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

(defn apply-callback
  "Invokes the provided callback function on the supplied resource."
  [request resource callback]
  ((callback (:handlers resource)) request))

(defn return-code
  "Returns a function that returns a sequence including the response
  code, the request, the response (with the status correctly set to
  the provided code) and a map of state data representing the decision
  flow. This function represents the end of the run."
  [code request response state]
  [code request (assoc response :status code) state])

;; utility methods

(defn response-ok
  "Returns a function that will return a 200 response code and add the
  provided node (a keyword) to the state."
  [request response state node]
  #(return-code 200 request response (assoc state node true)))

(defn response-error
  "Returns a function that will return an error response code and add
  the provide node (a keyword) to the state."
  [code request response state node]
  #(return-code code request response (assoc state node false)))

(defn key-to-upstring
  "Returns a String containing the uppercase name of the provided
  key."
  [key]
  (.toUpperCase (name key)))

(defn list-keys-to-upstring
  "Returns a comma separated list of upper-case Strings, each one the
  name of one of the provided keys."
  [keys]
  (apply str (interpose ", " (for [key keys] (key-to-upstring key)))))

(defn header-value
  "Returns the value for the specified request header."
  [header headers]
  (some (fn [[header-in value]]

          (if (= header header-in)
            value))
        headers))

(defn parse-accept-header
  "Parses an request's 'accept' header into a vector of maps, each map
  containing details about an acceptable content type."
  [accept-header]

  ;; sort the acceptable content types by their q value
  (sort #(compare (:q %2) (:q %1))

        ;; break up the header by acceptable type
        (let [acceptable-types (string/split (.toLowerCase accept-header) #",")]

          ;; break each type into components
          (for [acceptable-type acceptable-types]
            (let [major-minor-seq (string/split acceptable-type #";")
                  major-minor (first (string/split acceptable-type #";"))
                  major (if major-minor (first (string/split major-minor #"/")))
                  minor (if major-minor (second (string/split major-minor #"/")))
                  parameters-all (second (string/split acceptable-type #";"))
                  parameters (if parameters-all
                               (apply hash-map
                                      (string/split parameters-all #"=")))]
              {:major major
               :minor minor
               :parameters parameters

               ;; extract the q parameter, use 1.0 if not present
               :q (if (and parameters (parameters "q"))
                    (Double/valueOf (parameters "q"))
                    1.0)})))))

(defn parse-content-type
  "Parse's a handler's methods content-type into a map of data."
  [content-type]
  (let [major-minor-seq (map #(.trim %)
                             (string/split (.toLowerCase content-type) #"/"))]
    {:major (first major-minor-seq)
     :minor (second major-minor-seq)}))

(defn content-type-matches?
  "Returns true if the provided parsed accept-type matches the
  provided parsed response-type."
  [content-type accept-type]
  (and (or (= (:major content-type) (:major accept-type))
           (= "*" (:major accept-type)))
       (or (= (:minor content-type) (:minor accept-type))
           (= "*" (:minor accept-type)))))

(defn content-type-string
  "Returns the a string representation of the provided content-type
  map, i.e. 'text/plain'."
  [type-map]
  (if type-map
    (if (:minor type-map)
      (.toLowerCase (apply str (interpose "/" (vals type-map))))
      (first (vals type-map)))))

(defn acceptable-type
  "Compares the provided accept-header against a sequence of
  content-types and returns the content type that matches or nil if
  there are not valid matches."
  [content-types accept-header]

  ;; parse out the content types being offered and the accept header
  (let [accept-types (parse-accept-header accept-header)]

    ;; return a string representation, not a map
    (content-type-string

     ;; return the first matching content type
     (some (fn [accept-type]
             (some (fn [content-type]
                     (if (content-type-matches?
                          content-type accept-type)
                       content-type))
                   (map parse-content-type content-types)))
           accept-types))))

(defn acceptable-content-type
  "Returns the resource's matching content-type for the provided
  accept request header."
  [resource accept-header]
  (acceptable-type (keys (:response resource))
                   accept-header))

(defn variances
  "Returns a sequence of headers that, if different, would result in a
  different (varied) resource being served."
  [request]
  (filter #(not (nil? %))
          [(if (:acceptable-encoding request) "accept-encoding")
           (if (:acceptable-charset request) "accept-charset")
           (if (:acceptable-language request) "accept-language")
           (if (:acceptable-type request) "accept")]))

(defn quoted?
  "Returns true if the content of the provided String is surrounded by
  quotes."
  [text]
  (if (re-matches #"^\"(.*)\"$" text)
    true false))

(defn make-quoted
  "Returns the content of the provided String surrounded by quotes if
  that content is not already surrounded by quotes."
  [text]
  (if (quoted? text)
    text
    (str "\"" text "\"")))

(defn make-unquoted
  "Returns the content of the provided String with the surrounding
  quotation remarks removed, if they are present."  [text]
  (if (quoted? text)
    (apply str (rest (drop-last text)))
    text))

(defn parse-header-date
  "Returns a Date for the provided text. This text should contain a
  date in one of the three valid HTTP date formats."
  [text]
  (DateUtils/parseDate text
                       (into-array ["EEE, dd MMM yyyy HH:mm:ss zzz"
                                    "EEEE, dd-MMM-yy HH:mm:ss zzz"
                                    "EEE MMM d HH:mm:ss yyyy"])))

(defn header-date
  "Returns a textual date in the correct format for use in an HTTP
  header."
  [date]
  (.format HTTP-DATE-FORMAT date))

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

(defn merge-responses
  "Merges two responses into once complete response. Maps are merged
  into large maps, nil values are replaced with non-nil values and all
  other values are combined into a sequence."
  [response-1 response-2]
  (merge-with (fn [former latter]
                (cond

                  (and (map? former) (map? latter))
                  (merge former latter)

                  (nil? former)
                  latter

                  :else
                  latter))
              response-1 response-2))

(defn add-body
  "Calculates and appends the body to the provided request."
  [resource request response]
  (if (nil? (:body response))
      ;(not (some #(= :body %) response))

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

(defn respond
  "This function provides an endpoint for our processing pipeline, it
  returns the final response map for the request."
  [[code request response state] resource]

  ;; add the body to all 200 messages if the body isn't already
  ;; present
  (if (= 200 code)
    (assoc (add-body (:response resource) request response) :status code)

    ;; we have an error response code
    (assoc response
      :body (apply str (:body response)
             ((:error (:handlers resource)) code request response state)))))

;; states

;;(response-ok request response state :b11)

(defn o18b
  [resource request response state]
  (if (apply-callback request resource :multiple-representations)
    (response-error 300 request response state :o18b)
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
        (response-error (:status response-out) request response-out state :o18)

        ;; processing continues normally
        #(o18b resource request response-out (assoc state :o18 true))))

    #(o18b resource request response (assoc state :o18 false))))

(defn o20
  [resource request response state]
  (if (nil? (:body response))
    (response-error 204 request response state :o20)
    #(o18 resource request response (assoc state :o20 false))))

(defn p11
  [resource request response state]
  ;; TODO: Should we add the body here?
  (let [response-out (add-body (:response resource) request response)]
    (if (not (some #(= "location" %) (keys (:headers response-out))))
      #(o20 resource request response-out (assoc state :p11 true))
      (response-error 201 request response-out state :p11))))

(defn o14
  [resource request response state]
  (if (apply-callback request resource :is-conflict?)
    (response-error 409 request response state :o14)
    #(p11 resource request response (assoc state :o14 false))))

(defn o16
  [resource request response state]
  (if (= :put (:request-method request))
    #(o14 resource request response (assoc state :o16 true))
    #(o18 resource request response (assoc state :016 false))))

(defn n11
  [resource request response state]
  (let [create (apply-callback request resource :post-is-create?)]
    (cond

      ;; yep, we're creating!
      create
      (let [create-path (apply-callback request resource :create-path)]
        (if (nil? create-path)
          (throw (Exception. (str "Invalid resource, no create-path")))

          ;; redirect to the create path
          (let [url (str (:uri request) "/" create-path)]
            #(response-error 303
                             request
                             (assoc response :headers
                                    (assoc (:headers response)
                                      "location" url))
                             state
                             :n11))))

      ;; not a create
      (= false create)
      (let [process-post (apply-callback request resource :process-post)]
        (cond

          ;; status code returned
          (number? process-post)
          (response-error process-post request response state :n11)

          (= false process-post)
          (throw (Exception. (str "Process post invalid")))

          :else
          #(p11 resource request response (assoc state :n11 false)))))))

(defn n16
  [resource request response state]
  (if (= :post (:request-method request))
    #(n11 resource request response (assoc state :n16 true))
    #(o16 resource request response (assoc state :n16 false))))

(defn m20
  [resource request response state]
  (let [delete-resource (apply-callback request resource :delete-resource)]
    (if delete-resource
      #(o20 resource request response (assoc state :m20 true))
      (response-error 202 request response state :n16))))

(defn m16
  [resource request response state]
  (if (= :delete (:request-method request))
    #(m20 resource request response (assoc state :m16 true))
    #(n16 resource request response (assoc state :m16 false))))

(defn l17
  [resource request response state]
  (let [last-modified (apply-callback request resource :last-modified)
        if-modified-since (:if-modified-since request)]
    (if (> (.getTime last-modified)
           (.getTime if-modified-since))
      (response-error 304 request response state :l17)
      #(m16 resource request response (assoc state :l17 false)))))

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

(defn j18
  [resource request response state]
  (if (or (= :get (:request-method request))
          (= :head (:request-method request)))
    (response-error 304 request response state :j18)
    (response-error 412 request response state :j18)))

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

(defn i7
  [resource request response state]
  (response-ok request response state :i7))

(defn h12
  [resource request response state]
  (let [last-modified (apply-callback request resource :last-modified)
        if-unmodified-since (parse-header-date
                             (header-value "if-unmodified-since"
                                           (:headers request)))]
    (if (and last-modified (> (.getTime if-unmodified-since)
                              (.getTime last-modified)))
      (response-error 412 request response state :h12)
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
    (if (= "*" (make-unquoted if-match-value))
      (response-error 412 request response state :h7)
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
      (response-error 412 request response state :g11))))

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
  (let [hdrs (header-value "accept-encoding" (:headers request))
        enc-maps (parse-accept-header hdrs)
        ;; add identity map, if not already present
        enc-maps (if (some #(= "identity" (:major %)) enc-maps)
                   enc-maps
                   (conj enc-maps {:major "identity"
                                   :minor nil
                                   :parameters {"q" "1.0"}
                                   :q 1.0}))
        accepted-encodings (set (map :major enc-maps))
        available-encodings (set (keys (apply-callback request resource :encodings-provided)))
        encodings (intersection accepted-encodings available-encodings)
        available (->> enc-maps
                       (filter #(encodings (:major %)))
                       (filter #(> (:q %) 0)))]
    (if (empty? available)
      (response-error 406 request response state :f7)
      #(g7 resource request response (assoc state :f7 true)))))

(defn f6
  [resource request response state]
  (if (header-value "accept-encoding" (:headers request))
    #(f7 resource request response (assoc state :f6 true))
    #(g7 resource request response (assoc state :f6 false))))

(defn e6
  [resource request response state]
  (let [acceptable (:acceptable-charset request)]
    (if (and (or (= "*" acceptable)
                 (some #(= acceptable (.toLowerCase %))
                       (apply-callback request resource :charsets-provided))))
      #(f6 resource request response (assoc state :e6 true))
      (response-error 406 request response state :e6))))

(defn e5
  [resource request response state]
  (if (header-value "accept-charset" (:headers request))
    (let [acceptable (acceptable-type
                      (let [charsets (apply-callback request resource
                                                     :charsets-provided)]
                        (if (> 1 (count charsets))
                          (conj charsets "*")
                          charsets))
                      (header-value "accept-charset" (:headers request)))]
      (if acceptable
        #(e6 resource
             (assoc request :acceptable-charset acceptable)
             response
             (assoc state :e5 true))
        (response-error 406 request response state :e5)))
    #(f6 resource request response (assoc state :e5 false))))

(defn d5
  [resource request response state]
  (let [acceptable (:acceptable-language request)]
    (if (and (or (= "*" acceptable)
                 (some #(= acceptable (.toLowerCase %))
                       (apply-callback request resource :languages-provided))))
      #(e5 resource request response (assoc state :d5 true))
      (response-error 406 request response state :d5))))

(defn d4
  [resource request response state]
  (if (header-value "accept-language" (:headers request))
    (let [acceptable (acceptable-type
                      (let [languages (apply-callback request resource
                                                      :languages-provided)]
                        (if (> 1 (count languages))
                          (conj languages "*")
                          languages))
                      (header-value "accept-language" (:headers request)))]
      (if acceptable
        #(d5 resource
             (assoc request :acceptable-language acceptable)
             response
             (assoc state :d4 true))
        (response-error 406 request response state :d4)))
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
      (response-error 406 request response state :c4))))

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
          (response-error 413 request response state :b4)))

(defn b5
  [resource request response state]
  (decide #(apply-callback request resource :known-content-type?)
          true
          #(b4 resource request response (assoc state :b5 true))
          (response-error 415 request response state :b5)))

(defn b6
  [resource request response state]
  (decide #(apply-callback request resource :valid-content-headers?)
          true
          #(b5 resource request response (assoc state :b6 true))
          (response-error 501 request response state :b6)))

(defn b7
  [resource request response state]
  (decide #(apply-callback request resource :forbidden?)
          true
          (response-error 403 request response state :b7)
          #(b6 resource request response (assoc state :b6 true))))

(defn b8
  [resource request response state]
  (let [result (#(apply-callback request resource :is-authorized?))]
    (cond

      (= true result)
      #(b7 resource request response (assoc state :b8 true))

      (instance? String result)
      #(b7 resource request
           (assoc-in response
                     [:headers "www-authenticate"]
                     result)
           (assoc state :b8 result))

      :else
      (response-error 401 request response state :b8))))

(defn b9b
  [resource request response state]
  (decide #(apply-callback request resource :malformed-request?)
          true
          (response-error 400 request response state :b9b)
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

        (response-error 400 request
                        (assoc response :body
                               "Content-MD5 header does not match request body")
                        state :b9a))

      :else
      (response-error 400 request
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
          (response-error
           405 request
           (assoc-in response [:headers "allow"]
                     (list-keys-to-upstring
                      (apply-callback request resource :allowed-methods)))
           state :b10)))

(defn b11
  [resource request response state]
  (decide #(apply-callback request resource :uri-too-long?)
          true
          (response-error 414 request response state :b11)
          #(b10 resource request response (assoc state :b11 false))))

(defn b12
  [resource request response state]
  (decide #(some (fn [method-in]
                   (= (:request-method request) method-in))
                 (apply-callback request resource :known-methods))
          true
          #(b11 resource request response (assoc state :b12 true))
          (response-error 501 request response state :b12)))

(defn b13
  "Is the resource available?"
  [resource request response state]
  (decide #(apply-callback request resource :service-available?)
          true
          #(b12 resource request response (assoc state :b13 true))
          #(response-error 503 request response state :b13)))

(defn start
  "This function is the first stage in our processing tree. It will
  pass the resource and request onto node B13 with an empty response
  and the current machine state."
  [resource request]
  #(b13 resource request {} {}))

;; other functions

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
