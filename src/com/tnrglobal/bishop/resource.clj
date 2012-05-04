;;
;; Defines a resource and default callback functions.
;;
(ns com.tnrglobal.bishop.resource
  (:require [com.tnrglobal.bishop.encoding :as encoding]))

(defn default-handlers
  "Returns a map of all of the supported handlers and default
  implenentations."
  []
  {

   ;; Return false to indicate the requested resource does not
   ;; exist; A "404 Not Found" message will be provided to the client.
   :resource-exists? (fn [request] true)

   ;; Return false if the requested service is not available; a "503
   ;; Service Not Available" message will be provided to the
   ;; cluent. If the resource is only temporarily unavailable, suppy a
   ;; map containing a "Retry-After" header.
   :service-available? (fn [request] true)

   ;; Returning anything other than true will send a "401
   ;; Unauthorized" to the client. Returning a String will cause that
   ;; value to be added to the response in the "WWW-Authenticate"
   ;; header.
   :is-authorized? (fn [request] true)

   ;; If true, sends a "403 Forbidden" response to the client.
   :forbidden? (fn [request] false)

   ;; If true, indicates that the resource accepts POST requests to
   ;; non-existant resources.
   :allow-missing-post? (fn [request] false)

   ;; Should return true if the provided request is malformed;
   ;; returning true sends a "400 Malformed Request" response to the
   ;; client.
   :malformed-request? (fn [request] false)

   ;; Should return true if the URI of the provided request is too
   ;; long to be processed. Returning true sends a "414 Request URI
   ;; Too Long" response to the client.
   :uri-too-long? (fn [request] false)

   ;; Should return false if the "Content-Type" of the provided
   ;; request is unknown (normally a PUT or POST). Returning false
   ;; will send a "415 Unsupported Media" response to the client.
   :known-content-type? (fn [request] true)

   ;; Should return false if the provided request contains and invalid
   ;; "Content-*" headers. Returning false sends a "501 Not
   ;; Implemented" response to the client.
   :valid-content-headers? (fn [request] true)

   ;; Should return false if the entity length of the provided request
   ;; (normally a PUT or POST) is invalid. Returning false sends a
   ;; "413 Request Entity Too Large" response.
   :valid-entity-length? (fn [request] true)

   ;; If the OPTIONS method is supported and used by the resource,
   ;; this method should return a hashmap of headers that will appear
   ;; in the response.
   :options (fn [request] {})

   ;; Returns an sequence of keywords representing the HTTP methods
   ;; supported by the resource.
   :allowed-methods (fn [request] [:get :head])

   ;; Returns a sequence of keywords representing all of the HTTP
   ;; methods known to the resource.
   :known-methods  (fn [request]
                     [:get :head :post :put :delete :trace :connect :options])

   ;; This function is called when a DELETE request should be enacted,
   ;; it should return true if the deletion was successful.
   :delete-resource (fn [request] false)

   ;; This function is called after a sucessful call to
   ;; :delete-resource and should return false if the deletionwas
   ;; accepted but cannot be guaranteed to have completed.
   :delete-completed? (fn [request] true)

   ;; Returns true if POST requests should be treated as a request to
   ;; PUT content into a (potentically new) resource as opposed to a
   ;; generic submission for processing. If true is returned, the
   ;; :create-path function will be called and the rest of the request
   ;; will be treated like a PUT to that path.
   :post-is-create? (fn [request] false)

   ;; Will be called on a POST request if :post-is-create? returns
   ;; true. The path returned should be a valid URI part following the
   ;; dispatcher prefix; that path will replace the path under the
   ;; requests :uri key for all subsequent resource function calls.
   :create-path (fn [request] false)

   ;; Called after :create-path but before setting the "Location"
   ;; response header; determins the root URI of the new resource. If
   ;; nil, uses the URI of the request as the base.
   :base-uri (fn [request] nil)

   :validate-content-checksum (fn [request] nil)

   :languages-provided (fn [reqeust] [] ["en"])
   :charsets-provided (fn [request] ["utf8"])
   :encodings-provided (fn [request] {"identity" encoding/identity-enc})
   :variances (fn [request] [])
   :generate-etag (fn [request] nil)
   :last-modified (fn [request] nil)

   :expires (fn [request] nil)
   :content-types-provided (fn [request] ["text/html"])
   :multiple-representations (fn [request] false)
   :is-conflict? (fn [request] false)

   :process-post (fn [request] nil)
   :is-redirect? (fn [request] false)
   :redirect (fn [request] nil)

   :previously-existed? (fn [request] false)

   :moved-permanently? (fn [request] false)
   :moved-temporarily? (fn [request] false)})
