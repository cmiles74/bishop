;;
;; Defines a resource and default callback functions.
;;
(ns com.tnrglobal.bishop.resource
  (:require [com.tnrglobal.bishop.encoding :as encoding]))

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
   :languages-provided (fn [reqeust] [] ["en"])
   :charsets-provided (fn [request] [])
   :encodings-provided (fn [request] {"identity" encoding/identity-enc})
   :variances (fn [request] [])
   :generate-etag (fn [request] nil)
   :last-modified (fn [request] nil)
   :delete-resource (fn [request] false)
   :expires (fn [request] nil)
   :content-types-provided (fn [request] ["text/html"])
   :multiple-representations (fn [request] false)
   :is-conflict? (fn [request] false)
   :post-is-create? (fn [request] false)
   :base-uri (fn [request] nil)
   :process-post (fn [request] nil)
   :is-redirect? (fn [request] false)
   :redirect (fn [request] nil)
   :create-path (fn [request] false)

   ;; default error handler
   :error (fn [code request response state])})