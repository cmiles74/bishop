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
   :resource-available? (fn [request] true)
   :allowed-methods (fn [request] [:get :head])
   :malformed-request? (fn [request] false)
   :validate-content-checksum (fn [request] nil)

   ;; default error handler
   :error (fn [code request response state])})