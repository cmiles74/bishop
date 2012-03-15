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
   :is-authorized? (fn [request] true)
   :forbidden? (fn [request] false)
   :valid-content-headers? (fn [request] true)
   :known-content-type? (fn [request] true)
   :valid-entity-length? (fn [request] true)
   :options (fn [request] {})
   :languages-provided (fn [reqeust] [])
   :charsets-provided (fn [request] [])

   ;; default error handler
   :error (fn [code request response state])})