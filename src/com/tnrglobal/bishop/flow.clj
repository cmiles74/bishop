;;
;; Provides the transition table that models the webmachine decision
;; tree.
;;
(ns com.tnrglobal.bishop.flow)

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
  [resource callback]
  (callback (:handlers resource)))

(defn return-code
  "Returns a function that returns a sequence including the response
  code, the request, the response (with the status correctly set to
  the provided code) and a map of state data representing the decision
  flow. This function represents the end of the run."
  [code request response state]
  [code request (assoc response :status code) state])

;; states

(defn b12
  [resource request response state]
  (decide #(some (fn [method-in]
                   (= (:request-method request) method-in))
                 (apply-callback resource :known-methods))
          true
          (return-code 200 request response (assoc state :b12 true))
          (return-code 501 request response (assoc state :b12 false))))

(defn b13
  "Is the resource available?"
  [resource request response state]
  (decide #(apply-callback resource :service-available?)
          true
          #(b12 resource request response (assoc state :b13 true))
          (return-code 503 request response (assoc state :b13 false))))

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
