;;
;; Defines a resource and default callback functions.
;;
(ns com.tnrglobal.bishop.resource)

(defn default-handlers
  "Returns a map of all of the supported handlers and default
  implenentations."
  []
  {:resource-exists? (fn [request] true)
   :service-available? (fn [request] true)
   :known-methods  (fn [request]
                     [:get :head :post :put :delete :trace :connect :options])
   :uri-too-long? (fn [request] false)
   :allowed-methods (fn [request] [:get :head])
   :malformed-request? (fn [request] false)
   :validate-content-checksum (fn [request] nil)
   :is-authorized? (fn [reques] true)

   ;; default error handler
   :error (fn [code request response state])})