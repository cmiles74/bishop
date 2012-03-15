;;
;; Provides support for some default encoding methds
;;
(ns com.tnrglobal.bishop.encoding)

(defn identity
  "Provides a pass-through identity encoding. The content is unchanged."
  [response]
  response)