;;
;; Defines a resource and default callback functions.
;;
(ns com.tnrglobal.bishop.resource)

(defn default-handlers
  "Returns a map of all of the supported handlers and default
  implenentations."
  []
  {:resource-exists? true
   :service-available? true
   :known-methods [:get :head :post :put :delete :trace :connect :options]

   ;; default error handler
   :error (fn [code request response state]
            (str "An error occurred while processing your request"))})