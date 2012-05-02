;;
;; Provides support for some default encoding methds
;;
(ns com.tnrglobal.bishop.encoding
  (:import [java.io PrintWriter InputStreamReader ByteArrayInputStream
            ByteArrayOutputStream]
           [java.util.zip GZIPInputStream GZIPOutputStream]))

(defn identity-enc
  "Provides a pass-through identity encoding. The content is unchanged."
  [response]
  response)

(defmulti gzip class)

(defmethod gzip String [response]

  ;; setup a byte array output stream wrapped around our gzip encoder
  ;; and a writer
  (with-open [byte-out (ByteArrayOutputStream.)
              gzip-out (GZIPOutputStream. byte-out)
              writer (PrintWriter. gzip-out)]

    ;; write the data
    (.write writer response)
    (.flush writer)
    (.finish gzip-out)

    ;; return an input stream wrapping our output
    (ByteArrayInputStream. (.toByteArray byte-out))))

(defmethod gzip :default [response]
  (gzip (str response)))

(defn get-encoding-function
  "Returns the appropriate encoding function for the provided String
  encoding name."
  [encoding]
  (cond

    (= "identity" encoding)
    identity-enc

    (= "gzip" encoding)
    gzip))