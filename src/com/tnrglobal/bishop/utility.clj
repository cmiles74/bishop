;;
;; Provides utility functions.
;;
(ns com.tnrglobal.bishop.utility
  (:use [clojure.java.io]
        [clojure.set])
  (:import [org.apache.commons.codec.digest DigestUtils]
           [java.io ByteArrayOutputStream]
           [org.joda.time DateTime DateTimeZone]
           [org.joda.time.format DateTimeFormat])
  (:require [com.tnrglobal.bishop.encoding :as encoding]
            [clojure.string :as string]))

;; date format to use when outputting headers
(def HTTP-DATE-FORMAT (DateTimeFormat/forPattern
                       "EEE, dd MMM yyyy HH:mm:ss 'GMT'"))

;; valid HTTP Date header formats
(def VALID-HTTP-DATE-FORMATS [(DateTimeFormat/forPattern
                               "EEE, dd MMM yyyy HH:mm:ss 'GMT'")
                              (DateTimeFormat/forPattern
                               "EEEE, dd-MMM-yy HH:mm:ss 'GMT'")
                              (DateTimeFormat/forPattern
                               "EEE MMM d HH:mm:ss yyyy")])

(defn key-to-upstring
  "Returns a String containing the uppercase name of the provided
  key."
  [key]
  (.toUpperCase (name key)))

(defn string-to-titlecase
  "Capitalizes the first letter in the provided text."
  [text]
  (apply str (cons (.toUpperCase (str (first text))) (rest text))))

(defn header-to-titlecase
  "Splits the provided String into a sequence and title-cases eeach
  item, then recombines these Strings into one hyphen delimited
  String."
  [header]
  (apply str (interpose "-"
                        (map string-to-titlecase (string/split header #"-")))))

(defn headers-to-titlecase
  "Accepts a map and returns a new map where the keys have been
  converted to Strings in title case. The keys of the map should be
  hyphenated (as HTTP headers are), each word after a hyphen will be
  title-cased."
  [headers]
  (apply merge (map (fn [item]
         {(header-to-titlecase (name (first item))) (second item)})
       headers)))

(defn list-keys-to-upstring
  "Returns a comma separated list of upper-case Strings, each one the
  name of one of the provided keys."
  [keys]
  (apply str (interpose ", " (for [key keys] (key-to-upstring key)))))

(defn parse-accept-header
  "Parses an request's 'accept' header into a vector of maps, each map
  containing details about an acceptable content type."
  [accept-header]

  ;; sort the acceptable content types by their q value
  (sort #(compare (:q %2) (:q %1))

        ;; break up the header by acceptable type
        (let [acceptable-types (->> (string/split (.toLowerCase accept-header)
                                                  #",")
                                    (map #(string/trim %)))]

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
      (.toLowerCase
       (apply str (interpose "/" [(:major type-map) (:minor type-map)])))
      (:major type-map))))

(defn acceptable-type
  "Compares the provided accept-header or map against a sequence of
  content-types and returns the content type that matches or nil if
  there are not valid matches."
  [content-types acceptable]

  ;; parse out the content types being offered and the accept header
  (let [accept-types (if (coll? acceptable)
                       acceptable
                       (parse-accept-header acceptable))]

    ;; return a string representation, not a map
    (content-type-string

     ;; return the first matching content type with a "q" value
     ;; greater than 0
     (some (fn [accept-type]
             (some (fn [content-type]
                     (if (and (content-type-matches?
                               content-type accept-type)
                              (< 0 (:q accept-type)))
                       content-type))
                   (map parse-content-type content-types)))
           accept-types))))

(defn acceptable-content-type
  "Returns the resource's matching content-type for the provided
  accept request header."
  [resource accept-header]
  (acceptable-type (keys (:response resource))
                   accept-header))

(defn acceptable-encoding-type
  "Compares the provided resource encodings against the provided
  client 'accept-encoding' header and returns the name of a provided
  encoding type that will be acceptable to the client. If the client
  doesn't specifically list the 'identity' encoding type then it is
  assumed."
  [encodings accept-encoding]

  ;; fetch the provided resource encodign types and parse the client's
  ;; accept-encoding header
  (let [available-encodings (keys encodings)

        ;; if the client doesn't list the 'identity' encoding, we add
        ;; it with a low priority
        encoding-maps (parse-accept-header
                       (if (re-find #"identity" accept-encoding)
                         accept-encoding
                         (str accept-encoding ",identity;q=0.1")))]

    (acceptable-type available-encodings encoding-maps)))

(defn header-value
  "Returns the value for the specified request header."
  [header headers]
  (some (fn [[header-in value]]

          (if (= header header-in)
            value))
        headers))

(defn variances
  "Returns a sequence of headers that, if different, would result in a
  different (varied) resource being served."
  [request]
  (filter #(not (nil? %))
          [(if (:acceptable-encoding request) "accept-encoding")
           (if (:acceptable-charset request) "accept-charset")
           (if (:acceptable-language request) "accept-language")
           (if (:acceptable-type request) "accept")]))

(defn quoted?
  "Returns true if the content of the provided String is surrounded by
  quotes."
  [text]
  (if (re-matches #"^\"(.*)\"$" text)
    true false))

(defn make-quoted
  "Returns the content of the provided String surrounded by quotes if
  that content is not already surrounded by quotes."
  [text]
  (if (quoted? text)
    text
    (str "\"" text "\"")))

(defn make-unquoted
  "Returns the content of the provided String with the surrounding
  quotation remarks removed, if they are present."  [text]
  (if (quoted? text)
    (apply str (rest (drop-last text)))
    text))

(defn parse-header-date
  "Returns a Date for the provided text. This text should contain a
  date in one of the three valid HTTP date formats."
  [text]
  (let [parsed (first (filter #(not (nil? %))
                 (map (fn [formatter]
                        (try
                          (.parseDateTime formatter text)
                          (catch Exception e)))
                      VALID-HTTP-DATE-FORMATS)))]
    (if parsed parsed
        (throw (Exception. (str "Could not parse date from text '"
                                text "'"))))))

(defn header-date
  "Returns a textual date in the correct format for use in an HTTP
  header."
  [date]
  (if (instance? java.util.Date date)
    (.print HTTP-DATE-FORMAT (.withZone (DateTime. (.getTime date))
                                        (DateTimeZone/forID "GMT")))
    (.print HTTP-DATE-FORMAT (.withZone date
                                        (DateTimeZone/forID "GMT")))))

(defn merge-responses
  "Merges two responses into once complete response. Maps are merged
  into larger maps, nil values are replaced with non-nil values and
  all other values are combined into a sequence."
  [response-1 response-2]
  (cond

    ;; the response is a map, merge it with the response built so far
    (map? response-2)
    (let [mr-out (merge-with (fn [former latter]
                               (cond
                                 (and (map? former) (map? latter))
                                 (merge former latter)

                                 (nil? latter)
                                 former

                                 :else
                                 latter))
                             response-1 response-2)]
      mr-out)

    ;; the response is not a map, treat it as the body for our
    ;; response
    :else
    (assoc response-1 :body response-2)))
