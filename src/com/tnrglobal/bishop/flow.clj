;;
;; Provides the transition table that models the webmachine decision
;; tree.
;;
(ns com.tnrglobal.bishop.flow
  (:use [clojure.java.io])
  (:import [org.apache.commons.codec.digest DigestUtils]
           [java.io ByteArrayOutputStream])
  (:require [clojure.string :as string]))

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

(defn response-200
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

;; states

;;(response-200 request response state :b11)

(defn g7
  [resource request response state]
  (response-200 request response state :g7))

(defn f7
  [resource request response state]
  (let [acceptable (:acceptable-encoding request)]
    (if (and (or (= "*" acceptable)
                 (some #(= acceptable (.toLowerCase %))
                       (keys (apply-callback request resource :encodings-provided)))))
      #(g7 resource request response (assoc state :f7 true))
      (response-error 406 request response state :f7))))

(defn f6
  [resource request response state]
  (if (header-value "accept-encoding" (:headers request))
    (let [acceptable (acceptable-type
                      (let [encodings (keys (apply-callback request resource
                                                            :encodings-provided))]
                        (if (> 1 (count encodings))
                          (conj encodings "*")
                          encodings))
                      (header-value "accept-encoding" (:headers request)))]
      (if acceptable
        #(f7 resource
             (assoc request :acceptable-encoding acceptable)
             response
             (assoc state :f6 true))
        (response-error 406 request response state :f6)))
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
    (response-200 request
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

(defn respond-error
  "This function provides an endpoint for our processing pipeline, it
  returns the final error response map for the request."
  [[code request response state] resource]

  (assoc response :body (pr-str state)))

(defn respond
  "This function provides an endpoint for our processing pipeline, it
  returns the final response map for the request."
  [[code request response state] resource]

  (if (= 200 code)

    ;; get a handle on our response
    (let [resource-this (:response resource)]

      (cond

        ;; the resource contains a map of content types and return
        ;; values or functions
        (map? resource-this)
        (let [responder (resource-this "text/html")]

          (cond

            ;; invoke the response function
            (fn? responder)
            (assoc response :body (responder request))

            ;; return the response value
            :else
            (assoc response :body responder)))

        ;; the resource is a halt
        (and (coll? resource-this) (= :halt (first resource-this)))
        (assoc response :status (second resource-this))

        ;; the resource is an error
        (and (coll? resource-this) (= :error (first resource-this)))
        (merge response {:status 500
                         :body (second resource-this)})

        ;; we can't handle this resource
        :else
        (assoc response
          :body ((:error (:handlers resource)) code request response state))))

    ;; we have an error response code
    (assoc response
      :body ((:error (:handlers resource)) code request response state))))

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
