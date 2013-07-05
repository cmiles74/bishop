;;
;; Provides functions to test the functions in the bishop.flow
;; namespace.
;;
(ns com.tnrglobal.bishop.test.flow
  (:use [com.tnrglobal.bishop.core]
        [com.tnrglobal.bishop.flow]
        [com.tnrglobal.bishop.utility]
        [clojure.test])
  (:require [clojure.java.io :as io]
            [clojure.string :as string])
  (:import [java.io StringBufferInputStream]
           [org.joda.time DateTime]))

(def test-request
  {:remote-addr "0:0:0:0:0:0:0:1%0"
   :scheme :http
   :query-params {}
   :form-params {}
   :request-method :get
   :query-string nil
   :content-type nil
   :uri "/"
   :server-name "localhost"
   :params {}
   :headers {"user-agent" "curl/7.21.4 (universal-apple-darwin11.0) libcurl/7.21.4 OpenSSL/0.9.8r zlib/1.2.5"
             "accept" "*/*"
             "host" "localhost:8080"}})

(deftest test-parse-accept-header
  (is (= (parse-accept-header "da, en-gb;q=0.8, en;q=0.7")
         [{:major "da", :minor nil, :parameters nil, :q 1.0}
          {:major "en-gb", :minor nil, :parameters {"q" "0.8"}, :q 0.8}
          {:major "en", :minor nil, :parameters {"q" "0.7"}, :q 0.7}]))
  (is (= (parse-accept-header "iso-8859-5, unicode-1-1;q=0.8")
         [{:major "iso-8859-5", :minor nil, :parameters nil, :q 1.0}
          {:major "unicode-1-1", :minor nil, :parameters {"q" "0.8"},
           :q 0.8}])))

(deftest states

  ;; Available?

  (testing "B13 Invalid"
    (let [res (resource {"text/html" "testing..."}
                        {:service-available? (fn [request] false)})
          req test-request]
      (is (= 503 (:status (run req res))) "Service unavailable")))

  (testing "B13 Invalid, Try Again Later"
    (let [res (resource {"text/html" "testing..."}
                        {:service-available?
                         (fn [request] {:headers {"Retry-After" "30"}})})
          req test-request
          response (run req res)]
      (is (and (= 503 (:status response))
               (= "30" ((:headers response) "Retry-After"))))))

  (testing "B13 Valid"
    (let [res (resource {"text/html" "testing..."})
          req test-request]
      (is (= 200 (:status (run req res))))))

  (testing "B13 Valid, Override Content-Type"
    (let [res (resource {"text/html"
                         (fn [r]
                           {:headers {"content-type" "text/something"}
                            :body "testing"})})
          req test-request]
      (let [response (run req res)]
        (is (and (= 200 (:status response))
                 (= "text/something; charset=utf-8" ((:headers response)
                                                     "Content-Type")))))))

  ;; Known method?

  (testing "B12 Invalid"
    (let [res (resource {"text/html" "testing"})
          req (assoc test-request :request-method :super-get)]
      (is (= 501 (:status (run req res))) "Not implemented")))

  (testing "B12 Valid"
    (let [res (resource {"text/html" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  ;; URI too long?

  (testing "B11 Invalid"
    (let [res (resource {"text/html" "testing"}
                        {:uri-too-long? (fn [request] true)})
          req test-request]
      (is (= 414 (:status (run req res))) "Request URI too long")))

  (testing "B11 Invalid with Body"
    (let [res (resource {"text/html" "testing"}
                        {:uri-too-long?
                         (fn [request] {:body "Way too long."})})
          req test-request]
      (let [result (run req res)]
        (is (and (= 414 (:status result))
                 (= "Way too long." (:body result)))
            "Request URI too long"))))

  (testing "B11 Valid"
    (let [res (resource {"text/html" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  ;; Is method allowed on this resource?

  (testing "B10 Invalid"
    (let [res (resource {"text/html" "testing"}
                        {:allowed-methods (fn [request] [:post])})
          req test-request]
      (let [res-out (run req res)]
        (is (and (= 405 (:status res-out))
                 (some (fn [[head val]]
                         (= "Allow" head)) (:headers res-out)))
            "Method not allowed"))))

  (testing "B10 Valid"
    (let [res (resource {"text/html" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  ;; Malformed request

  (testing "B9B Malformed Request"
    (let [res (resource {"text/html" "testing"}
                        {:malformed-request? (fn [request] true)})
          req test-request]
      (let [response (run req res)]
        (is (= 400 (:status response))))))

  (testing "B9B Malformed Request with Body"
    (let [res (resource {"text/html" "testing"}
                        {:malformed-request?
                         (fn [request] {:body "Yucky request received"})})
          req test-request]
      (let [response (run req res)]
        (is (and (= 400 (:status response))
                 (= "text/plain; charset=utf-8" ((:headers response)
                                                "Content-Type"))
                 (= "Yucky request received" (:body response)))))))

  ;; Contains "Content-MD5" header?

  (testing "B9 No Header"
    (let [res (resource {"text/html" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  (testing "B9 Valid"
    (let [res (resource {"text/html" "testing"})
          req (merge test-request
                     {:headers (conj (:headers test-request)
                                     ["content-md5"
                                      "e4e68fb7bd0e697a0ae8f1bb342846d7"])
                      :body (StringBufferInputStream. "Test message.")})]
      (is (= 400 (:status (run req res))))))

  (testing "B9 Invalid"
    (let [res (resource {"text/html" "testing"})
          req (merge-with concat
                          test-request
                          {:headers ["content-md5"
                                     "e4e68fb7bd0e697a0ae8f1bb342846b3"]
                           :body (StringBufferInputStream. "Test message.")})]
      (is (= 200 (:status (run req res)))
          "Content-MD5 header does not match request body")))

  ;; is authorized?

  (testing "B8 Valid"
    (let [res (resource {"text/html" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  (testing "B8 Not Valid"
    (let [res (resource {"text/html" "testing"}
                        {:is-authorized? (fn [request] false)})
          req test-request]
      (is (= 401 (:status (run req res))) "Unauthorized")))

  (testing "B8 Specifies Authorization Method"
    (let [res (resource {"text/html" "testing"}
                        {:is-authorized? (fn [request] "Basic")})
          req test-request
          response (run req res)]
      (is (and (= 401 (:status response))
               (some (fn [[head value]]
                       (and (= "WWW-Authenticate" head)
                            (= "Basic" value)))
                     (:headers response))) "Authenticate")))

  (testing "B8 Authorized"
    (let [res (resource {"text/html" "testing"}
                        {:is-authorized? (fn [request] true)})
          req test-request]
      (is (= 200 (:status (run req res))) "Authorized")))

  (testing "B8 Not Authorized Specifies Response Map"
    (let [res (resource {"text/html" "testing"}
                        {:is-authorized? (fn [request] [false {:headers {"www-authenticate" "Basic"}
                                                               :body "Unauthorized"}])})
          req test-request
          response (run req res)]
      (is (= 401 (:status response)))
      (is (= "Unauthorized" (:body response)))
      (is (some (fn [[head value]]
                  (and (= "Www-Authenticate" head)
                    (= "Basic" value)))
            (:headers response)) "Authenticate")))

  ;; forbidden?

  (testing "B7 Valid"
    (let [res (resource {"text/html" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  (testing "B7 Invalid"
    (let [res (resource {"text/html" "testing"}
                        {:forbidden? (fn [request] true)})
          req test-request]
      (is (= 403 (:status (run req res))) "Forbidden")))

  (testing "B7 Invalid with Body"
    (let [res (resource {"text/html" "testing"}
                        {:forbidden?
                         (fn [request]
                           {:body "Oh no you don't."})})
          req test-request]
      (let [response (run req res)]
        (is (and (= 403 (:status (run req res)))
                 (= "Oh no you don't." (:body response)))
            "Forbidden"))))

  ;; valid content headers?

  (testing "B6 Valid"
    (let [res (resource {"text/html" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  (testing "B6 Invalid"
    (let [res (resource {"text/html" "testing"}
                        {:valid-content-headers? (fn [request] false)})
          req test-request]
      (is (= 501 (:status (run req res))) "Not implemented")))

  (testing "B6 Invalid with Body"
    (let [res (resource {"text/html" "testing"}
                        {:valid-content-headers?
                         (fn [request]
                           [false
                            {:body "That's some wack content type!"}])})
          req test-request]
      (let [response (run req res)]
        (is (and (= 501 (:status (run req res)))
                 (= "That's some wack content type!" (:body response)))
            "Not implemented"))))

  ;; known content type?

  (testing "B5 Valid"
    (let [res (resource {"text/html" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  (testing "B5 Invalid"
    (let [res (resource {"text/html" "testing"}
                        {:known-content-type? (fn [request] false)})
          req test-request]
      (is (= 415 (:status (run req res))) "Unsupported media type")))

  (testing "B5 Invalid with Body"
    (let [res (resource {"text/html" "testing"}
                        {:known-content-type?
                         (fn [request]
                           [false
                            {:body "I don't play MP3s."
                             :headers {"content-type" "text/gobbeldy"}}])})
          req test-request]
      (let [response (run req res)]
        (is (and (= 415 (:status response))
                 (= "I don't play MP3s." (:body response))
                 (= "text/gobbeldy; charset=utf-8" ((:headers response)
                                                "Content-Type")))
            "Unsupported media type"))))

  ;; valid entity length?

  (testing "B4 Valid"
    (let [res (resource {"text/html" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  (testing "B4 Invalid"
    (let [res (resource {"text/html" "testing"}
                        {:valid-entity-length? (fn [request] false)})
          req test-request]
      (is (= 413 (:status (run req res))) "Request entity too large")))

  (testing "B4 Invalid with Body"
    (let [res (resource {"text/html" "testing"}
                        {:valid-entity-length?
                         (fn [request]
                           [false
                            {:body "Dude, totally too long."}])})
          req test-request]
      (let [response (run req res)]
        (is (and (= 413 (:status (run req res)))
                 (= "Dude, totally too long." (:body response)))
            "Request entity too large"))))

  ;; options?

  (testing "B3 Valid"
    (let [res (resource {"text/html" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  (testing "B3 Options"
    (let [res (resource {"text/html" "testing"}
                        {:allowed-methods (fn [request] [:get :head :options])
                         :options (fn [request]
                                    {"allow" "GET, HEAD, OPTIONS"})})
          req (assoc test-request :request-method :options)]
      (let [response (run req res)]
        (is (some (fn [[header value]]
                    (= "Allow" header))
                  (:headers response))))))

  ;; acceptable content type?

  (testing "C4 Valid"
    (let [res (resource {"text/html" (fn [r] {:body (r :acceptable-type)})})
          req test-request]
      (let [response (run req res)]
        (is (and (= 200 (:status response))
                 (= "text/html" (:body response)))))))

  (testing "C4 Invalid"
    (let [res (resource {"text/plain" "testing"})
          req (assoc-in test-request [:headers "accept"]
                        "text/html,application/xhtml+xml,application/xml;q=0.9")]
      (let [response (run req res)]
        (is (and (= 406 (:status response))
                 (= "text/plain; charset=utf-8" (:body response)))
            "Not Acceptable"))))

  ;; acceptable language?

  (testing "D4 Unspecified"
    (let [res (resource {"text/html"
                         (fn [r] {:body (:acceptable-language r)})})
          req test-request]
      (let [response (run req res)]
        (is (and (= 200 (:status response))
                 (= nil (:body response)))))))

  (testing "D4 Valid"
    (let [res (resource {"text/html"
                         (fn [r] {:body (:acceptable-language r)})})
          req1 (assoc-in test-request [:headers "accept-language"]
                         "en,*;q=0.8")
          req2 (assoc-in test-request [:headers "accept-language"]
                         "en-us,en;q=0.5")]
      (let [response1 (run req1 res)
            response2 (run req2 res)]
        (is (= 200 (:status response1)))
        (is (= 200 (:status response2))))))

  (testing "D4 Invalid"
    (let [res (resource {"text/html" "testing"})
          req (assoc-in test-request [:headers "accept-language"]
                        "da;q=0.8")]
      (is (= 406 (:status (run req res))) "Not Acceptable")))

  ;; acceptable language available?

  (testing "D5 Unspecified"
    (let [res (resource {"text/html" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  (testing "D5 Available"
    (let [res (resource {"text/html" (fn [r] {:body (:acceptable-language r)})}
                        {:languages-provided (fn [r] ["en"])})
          req (assoc-in test-request [:headers "accept-language"]
                        "da,en;q=0.8")]
      (let [response (run req res)]
        (is (= 200 (:status response))))))

  (testing "D5 Not Available"
    (let [res (resource {"text/html" "testing"})
          req1 (assoc-in test-request [:headers "accept-language"]
                         "da;q=0.8")
          req2 (assoc-in test-request [:headers "accept-language"]
                         "en;q=0.0")]
      (is (= 406 (:status (run req1 res))) "Not Acceptable")
      (is (= 406 (:status (run req2 res))) "Not Acceptable")))

  ;; acceptable charset available?

  (testing "E6 Unspecified"
    (let [res (resource {"text/html" "testing"})
          req test-request
          response (run req res)]
      (is (and (= 200 (:status response))
               (= "text/html; charset=utf-8"
                  ((:headers response) "Content-Type"))))))

  (testing "E6 Available"
    (let [res (resource {"text/html" (fn [r] nil)})
          req1 (assoc-in test-request [:headers "accept-charset"]
                         "utf-8,iso-8859-1;q=0.8")
          req2 (assoc-in test-request [:headers "accept-charset"]
                         "*")]
      (is (and (= 200 (:status (run req1 res)))))
      (is (and (= 200 (:status (run req2 res)))))))

  (testing "E6 Not Acceptable"
    (let [res (resource {"text/html" "testing"})
          req (assoc-in test-request [:headers "accept-charset"]
                        "utf8;q=0,iso-8859-1;q=0.8")]
      (is (= 406 (:status (run req res))) "Not Acceptable")))

  ;; acceptable encoding?

  (testing "F6 Unspecified"
    (let [res (resource {"text/html"
                         (fn [r] {:body (:acceptable-encoding r)})})
          req test-request]
      (let [response (run req res)]
        (is (and (= 200 (:status response))
                 (= nil (:body response)))))))

  (testing "F6 Valid"
    (let [res (resource {"text/html"
                         (fn [r] {:body (:acceptable-encoding r)})})
          req (assoc-in test-request [:headers "accept-encoding"]
                        "identity,*;q=0.8")]
      (let [response (run req res)]
        (is (= 200 (:status response))))))

  ;; acceptable encoding available?

  (testing "F7 Unspecified"
    (let [res (resource {"text/html" "testing"})
          req test-request]
      (is (= 200 (:status (run req res))))))

  (testing "F7 Available"
    (let [res (resource {"text/html" (fn [r]
                                       {:body (:acceptable-encoding r)})})
          req (assoc-in test-request [:headers "accept-encoding"]
                        "huzzah;q=0.8")]
      (let [response (run req res)]
        (is (and (= 200 (:status response))
                 (= "identity" (:body response)))))))

  (testing "F7 Invalid"
    (let [res (resource {"text/html" "testing"})
          req (assoc-in test-request [:headers "accept-encoding"]
                        "huzzah;q=0.8,identity;q=0")]
      (is (= 406 (:status (run req res))) "Not Acceptable")))

  ;; vary header

  (testing "G7 No Header"
    (let [res (resource {"text/html" "testing"})
          req test-request]
      (let [response (run req res)]
        (is (and (= 200 (:status response))
                 (= "accept-charset, accept"
                    ((:headers response) "Vary")))))))

  (testing "G7 Header"
    (let [res (resource {"text/html" "testing"})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"accept-encoding" "huzzah,*;q=0.8"}
                             {"accept-charset" "utf-8,*;q=0.8"}
                             {"accept-language" "en,*;q=0.8"}
                             {"accept"
                              "text/html,application/xhtml+xml;q=0.8"}))]
      (let [response (run req res)]
        (is (and (= 200 (:status response))
                 (= "accept-encoding, accept-charset, accept-language, accept"
                    ((:headers response) "Vary")))))))

  ;; if-match etag

  (testing "G8 No If-Match"
    (let [res (resource {"text/html" "testing"})
          req test-request]
      (let [response (run req res)]
        (is (and (= 200 (:status response)))))))

  (testing "G9 If-Match *"
    (let [res (resource {"text/html" "testing"})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-match" "*"}))]
      (let [response (run req res)]
        (is (= 200 (:status response))))))

  (testing "G11 E-Tag Matches"
    (let [res (resource {"text/html" "testing"}
                        {:generate-etag (fn [r] "testing")})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-match" "\"testing\", \"testing-ish\""}))]
      (let [response (run req res)]
        (is (and (= 200 (:status response)))))))

  (testing "G11 E-Tag Does Not Match"
    (let [res (resource {"text/html" "testing"}
                        {:generate-etag (fn [r] "testing")})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-match" "\"not testing\", \"production\""}))]
      (let [response (run req res)]
        (is (= 412 (:status response))))))

  ;; if-match is a quoted astrisk

  (testing "H7 If-Match Quoted *"
    (let [res (resource {"text/html" "testing"})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-match" "\"*\""}))]
      (let [response (run req res)]
        (is (and (= 412 (:status response)))))))

  ;; if-unmodified-since

  (testing "H11 If-Unmodified-Since, Format #1"
    (let [res (resource {"text/html"
                         (fn [r] {:body (r :if-unmodified-since)})})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-unmodified-since"
                              "Fri, 31 Dec 1999 23:59:59 GMT"}))]
      (let [response (run req res)]
        (is (and (= 200 (:status response))
                 (= (.compareTo (.toLocalDate (DateTime. 946684799000))
                                (.toLocalDate (response :body))) 0))))))

  (testing "H11 If-Unmodified-Since, Format #2"
    (let [res (resource {"text/html" (fn [r] {:body (r :if-unmodified-since)})})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-unmodified-since"
                              "Friday, 31-Dec-99 23:59:59 GMT"}))]
      (let [response (run req res)]
        (is (and (= 200 (:status response))
                 (= (.compareTo (.toLocalDate (DateTime. 946684799000))
                                (.toLocalDate (response :body))) 0))))))

  (testing "H11 If-Unmodified-Since, Format #3"
    (let [res (resource {"text/html"
                         (fn [r] {:body (r :if-unmodified-since)})})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-unmodified-since"
                              "Fri Dec 31 23:59:59 1999"}))]
      (let [response (run req res)]
        (is (and (= 200 (:status response))
                 (= (.compareTo (.toLocalDate (DateTime. 946684799000))
                                (.toLocalDate (response :body))) 0))))))

  (testing "H11 If-Unmodified-Since, Invalid"
    (let [res (resource {"text/html"
                         (fn [r] {:body (r :if-unmodified-since)})})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-unmodified-since" "I like ice cream!"}))]
      (let [response (run req res)]
        (is (and (= 200 (:status response))
                 (nil? (response :body)))))))

  (testing "H12 If-Unmodified-Since, True"
    (let [res (resource {"text/html" "testing"}
                        {:last-modified (fn [r] (DateTime.))})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-unmodified-since"
                              "Fri, 31 Dec 1999 23:59:59 GMT"}))]
      (let [response (run req res)]
        (is (= 412 (:status response))))))

  (testing "H12 If-Unmodified-Since, False"
    (let [res (resource {"text/html" "testing"}
                        {:last-modified (fn [r] (DateTime. 139410000000))})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-unmodified-since"
                              "Fri, 31 Dec 1999 23:59:59 GMT"}))]
      (let [response (run req res)]
        (is (= 200 (:status response))))))

  (testing "I12 No If-None-Match Header"
    (let [res (resource {"text/html" "testing"})
          req test-request]
      (let [response (run req res)]
        (is (= 200 (:status response))))))

  (testing "I13 GET, If-None-Match = *, True"
    (let [res (resource {"text/html" "testing"})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-none-match" "*"}))]
      (let [response (run req res)]
        (is (= 304 (:status response))))))

  (testing "I13 POST, If-None-Match = *, True"
    (let [res (resource {"text/html" "testing"}
                        {:allowed-methods (fn [request] [:post])})
          req (assoc (assoc test-request :headers
                            (concat (:headers test-request)
                                    {"if-none-match" "*"}))
                :request-method :post)]
      (let [response (run req res)]
        (is (= 412 (:status response))))))

  (testing "K13 ETag not in If-None-Match"
    (let [res (resource {"text/html" "testing"}
                        {:generate-etag
                         (fn [request] "eb54d63b7351fb3a92bf008179cdacd2")})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-none-match"
                              "\"ba51d0516daf8d09919af69e8fc8145d\""}))]
      (let [response (run req res)]
        (is (= 200 (:status response))))))

  (testing "G8, H10, L13 No If-Modified-Since"
    (let [res (resource {"text/html" "testing"})
          req test-request]
      (let [response (run req res)]
        (is (= 200 (:status response))))))

  (testing "L14 If-Modified-Since, Valid"
    (let [res (resource {"text/html" (fn [request]
                                       {:body (:if-modified-since request)})}
                        {:last-modified (fn [request] (DateTime. ))})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-modified-since"
                              "Fri, 31 Dec 1999 23:59:59 GMT"}))]
      (let [response (run req res)]
        (is (:body response)))))

  (testing "L14 If-Modified-Since, Invalid"
    (let [res (resource {"text/html" (fn [request]
                                       {:body (:if-modified-since request)})})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-modified-since"
                              "Booyah!"}))]
      (let [response (run req res)]
        (is (not (:body response))))))

  (testing "L17 Last-Modified > If-Modified-Since, True"
    (let [res (resource {"text/html" "testing"}
                        {:last-modified (fn [request] (DateTime.))})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-modified-since"
                              "Fri, 31 Dec 1969 23:59:59 GMT"}))]
      (let [response (run req res)]
        (is (= 200 (:status response))))))

  (testing "L17 Last-Modified > If-Modified-Since, False"
    (let [res (resource {"text/html" "testing"}
                        {:last-modified
                         (fn [request] (DateTime. 946684799000))})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-modified-since"
                              "Fri, 31 Dec 2011 23:59:59 GMT"}))]
      (let [response (run req res)]
        (is (= 304 (:status response))))))

  (testing "M20 DELETE If-Modified-Since, True"
    (let [res (resource {"text/html" "testing"}
                        {:allowed-methods (fn [request] [:delete])
                         :last-modified (fn [request] (DateTime.))
                         :delete-resource (fn [request] true)})
          req (assoc (assoc test-request :request-method :delete)
                :headers (concat (:headers test-request)
                                 {"if-modified-since"
                                  "Fri, 31 Dec 2011 23:59:59 GMT"}))]
      (let [response (run req res)]
        (is (= 204 (:status response))))))

  (testing "M20 DELETE If-Modified-Since, True But No Delete"
    (let [res (resource {"text/html" "testing"}
                        {:allowed-methods (fn [request] [:delete])
                         :last-modified (fn [request] (DateTime.))
                         :delete-resource (fn [request] false)})
          req (assoc (assoc test-request :request-method :delete)
                :headers (concat (:headers test-request)
                                 {"if-modified-since"
                                  "Fri, 31 Dec 2011 23:59:59 GMT"}))]
      (let [response (run req res)]
        (is (= 500 (:status response))))))

  (testing "M20B Delete Incomplete"
    (let [res (resource {"text/html" "testing"}
                        {:allowed-methods (fn [request] [:delete])
                         :last-modified (fn [request] (DateTime.))
                         :delete-resource (fn [request] true)
                         :delete-completed? (fn [request] false)})
          req (assoc (assoc test-request :request-method :delete)
                :headers (concat (:headers test-request)
                                 {"if-modified-since"
                                  "Fri, 31 Dec 2011 23:59:59 GMT"}))]
      (let [response (run req res)]
        (is (= 202 (:status response))))))

  (testing "M20B Delete Incomplete, With Body"
    (let [res (resource {"text/html" "testing"}
                        {:allowed-methods (fn [request] [:delete])
                         :last-modified (fn [request] (DateTime.))
                         :delete-resource (fn [request] true)
                         :delete-completed? (fn [request]
                                              [false
                                               {:body "Still thinking..."}])})
          req (assoc (assoc test-request :request-method :delete)
                :headers (concat (:headers test-request)
                                 {"if-modified-since"
                                  "Fri, 31 Dec 2011 23:59:59 GMT"}))]
      (let [response (run req res)]
        (is (and (= 202 (:status response))
                 (= "Still thinking..." (:body response)))))))

  (testing "M20 DELETE If-Modified-Since, False"
    (let [res (resource {"text/html" "testing"}
                        {:allowed-methods (fn [request] [:delete])
                         :last-modified (fn [request]
                                          (DateTime. 946684799000))
                         :delete-resource (fn [request] true)})
          req (assoc (assoc test-request :request-method :delete)
                :headers (concat (:headers test-request)
                                 {"if-modified-since"
                                  "Fri, 31 Dec 2011 23:59:59 GMT"}))]
      (let [response (run req res)]
        (is (= 304 (:status response))))))

  (testing "O18 Multiple-Representations, False"
    (let [res (resource {"text/html" "testing"}
                        {:last-modified (fn [request] (DateTime. ))})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-modified-since"
                              "Fri, 31 Dec 2010 23:59:59 GMT"}))]
      (let [response (run req res)]
        (is (= 200 (:status response))))))

  (testing "O18 Multiple-Representations, True"
    (let [res (resource {"text/html" "testing"}
                        {:last-modified (fn [request] (DateTime.))
                         :multiple-representations (fn [request] true)})
          req (assoc test-request :headers
                     (concat (:headers test-request)
                             {"if-modified-since"
                              "Fri, 31 Dec 2010 23:59:59 GMT"}))]
      (let [response (run req res)]
        (is (= 300 (:status response))))))

  (testing "O16 PUT Conflict"
    (let [res (resource {"text/html" "testing"}
                        {:allowed-methods (fn [request] [:put])
                         :is-conflict? (fn [request] true)})
          req (assoc test-request :request-method :put)]
      (let [response (run req res)]
        (is (= 409 (:status response))))))

  (testing "O16 PUT New Resource"
    (let [res (resource {"text/html" (fn [request]
                                       {:body "testing"
                                        :headers {"Location"
                                                  "/testing/1209"}})}
                        {:allowed-methods (fn [request] [:put])
                         :resource-exists? (fn [request] false)})
          req (assoc test-request :request-method :put)]
      (let [response (run req res)]
        (is (= 201 (:status response))))))

  (testing "O16 PUT, Not New Resource"
    (let [res (resource {"text/html" (fn [request]
                                       {:body "testing"})}
                        {:allowed-methods (fn [request] [:put])})
          req (assoc test-request :request-method :put)]
      (let [response (run req res)]
        (is (= 200 (:status response))))))

  (testing "N11, Post is Create"
    (let [res (resource {"text/html" (fn [request]
                                       {:body "testing"})}
                        {:allowed-methods (fn [request] [:post])
                         :allow-missing-post? (fn [request] true)
                         :post-is-create? (fn [request] true)
                         :create-path (fn [request] "testing/new")})
          req (assoc test-request :request-method :post)]
      (let [response (run req res)]
        (is (and (= 303 (:status response))
                 (= "/testing/new" ((:headers response) "Location")))))))

  (testing "N11, Post is Create with Conflict"
    (let [res (resource {"text/html" (fn [request]
                                       {:body "testing"})}
                        {:allowed-methods (fn [request] [:post])
                         :allow-missing-post? (fn [request] true)
                         :post-is-create? (fn [request] true)
                         :is-conflict? (fn [request] true)
                         :create-path (fn [request] "testing/new")})
          req (assoc test-request :request-method :post)]
      (let [response (run req res)]
        (is (= 409 (:status response))))))

  (testing "N11, Post is Create, Bad Post, Body from Callback"
    (let [res (resource {"text/html" (fn [request]
                                       {:status 422
                                        :body "testing"
                                        :headers {"content-type" "text/plain"}})}
                        {:allowed-methods (fn [request] [:post])
                         :post-is-create? (fn [request] true)})
          req (assoc test-request :request-method :post)]
      (let [response (run req res)]
        (is (and (= 422 (:status response))
                 (= "text/plain; charset=utf-8"
                    ((:headers response) "Content-Type")))))))

  (testing "N11, Post is Not Create, 'Location' Header"
    (let [res (resource {"text/html" (fn [request]
                                       {:body "testing for realz"})}
                        {:allowed-methods (fn [request] [:post])
                         :post-is-create? (fn [request] false)
                         :process-post (fn [request]
                                         {:headers {"Location"
                                                    "/testing/21"}})})
          req (assoc test-request :request-method :post)]
      (let [response (run req res)]
        (is (= 201 (:status response))))))

  (testing "N11, Post is Not Create, No Body"
    (let [res (resource {"text/html" (fn [request]
                                       {:body "testing"})}
                        {:allowed-methods (fn [request] [:post])
                         :post-is-create? (fn [request] false)
                         :process-post (fn [request] true)})
          req (assoc test-request :request-method :post)]
      (let [response (run req res)]
        (is (= 204 (:status response))))))

  (testing "N11, Post is Not Create, No Body, Conflict"
    (let [res (resource {"text/html" (fn [request]
                                       {:body "testing"})}
                        {:allowed-methods (fn [request] [:post])
                         :post-is-create? (fn [request] false)
                         :is-conflict? (fn [request] true)
                         :process-post (fn [request] true)})
          req (assoc test-request :request-method :post)]
      (let [response (run req res)]
        (is (= 409 (:status response))))))

  (testing "N11, Post is Not Create, Body"
    (let [res (resource {"text/html" (fn [request]
                                       {:body "testing"})}
                        {:allowed-methods (fn [request] [:post])
                         :post-is-create? (fn [request] false)
                         :process-post (fn [request]
                                         {:body "POST handled!"})})
          req (assoc test-request :request-method :post)]
      (let [response (run req res)]
        (is (= 200 (:status response))))))

  (testing "N11, Post is Not Create, Body, Handle Form"
    (let [res (resource {"text/html" (fn [request]
                                       {:body "testing"})}
                        {:allowed-methods (fn [request] [:post])
                         :post-is-create? (fn [request] false)
                         :process-post (fn [request]
                                         {:body (pr-str (:params request))})})
          req (merge test-request
                     {:request-method :post
                      :params {:field-1 "Hello!" :field-2 "My name is Simon"}
                      :body "field-1=Hello!&field-2=My+name+is+Simon"})]
      (let [response (run req res)]
        (is (and (= 200 (:status response))
                 (= "{:field-1 \"Hello!\", :field-2 \"My name is Simon\"}"
                    (:body response)))))))

  (testing "N11, Post is Not Create, Body, Handle Form, HTML Response"
    (let [res (resource {"text/html" (fn [request]
                                       {:body "testing"})}
                        {:allowed-methods (fn [request] [:post])
                         :post-is-create? (fn [request] false)
                         :process-post
                         (fn [request]
                           {:status 400
                            :headers {"Content-Type" "text/html"}
                            :body "<html><body>Response!</body></html>"})})

          req (merge test-request
                     {:request-method :post
                      :params {:field-1 "Hello!" :field-2 "My name is Simon"}
                      :body "field-1=Hello!&field-2=My+name+is+Simon"})]
      (let [response (run req res)]
        (is (and (= 400 (:status response))
                 (= "text/html; charset=utf-8" ((:headers response) "Content-Type"))
                 (= "<html><body>Response!</body></html>" (:body response)))))))

  (testing "L7, Not Post"
    (let [res (resource {"text/html" (fn [request] {:body "testing"})}
                        {:resource-exists? (fn [request] false)})
          req test-request]
      (let [response (run req res)]
        (is (= 404 (:status response))))))

  (testing "M7, Post to Missing Resource, Not Allowed"
    (let [res (resource {"text/html" (fn [request] {:body "testing"})}
                        {:resource-exists? (fn [request] false)
                         :allowed-methods (fn [request] [:post])})
          req (assoc test-request :request-method :post)]
      (let [response (run req res)]
        (is (= 404 (:status response))))))

  (testing "M7, Post to Missing Resource, Allowed"
    (let [res (resource {"text/html" (fn [request] {:body "testing"})}
                        {:resource-exists? (fn [request] false)
                         :allowed-methods (fn [request] [:post])
                         :allow-missing-post? (fn [request] true)
                         :process-post (fn [request] true)})
          req (assoc test-request :request-method :post)]
      (let [response (run req res)]
        (is (= 204 (:status response))))))

  (testing "K5, Moved Permanently"
    (let [res (resource {"text/html" (fn [request] {:body "testing"})}
                        {:resource-exists? (fn [request] false)
                         :previously-existed? (fn [request] true)
                         :moved-permanently? (fn [request] "/testing/29292")})
          req test-request]
      (let [response (run req res)]
        (is (= 301 (:status response))))))

  (testing "L5, Moved Temporarily"
    (let [res (resource {"text/html" (fn [request] {:body "testing"})}
                        {:resource-exists? (fn [request] false)
                         :previously-existed? (fn [request] true)
                         :moved-temporarily? (fn [request] "/testing/29292")})
          req test-request]
      (let [response (run req res)]
        (is (= 307 (:status response))))))

  (testing "M5, Gone"
    (let [res (resource {"text/html" (fn [request] {:body "testing"})}
                        {:resource-exists? (fn [request] false)
                         :previously-existed? (fn [request] true)})
          req test-request]
      (let [response (run req res)]
        (is (= 410 (:status response))))))

  (testing "N5, POST to Missing Resource, Not Allowed"
    (let [res (resource {"text/html" (fn [request] {:body "testing"})}
                        {:allowed-methods (fn [request] [:post])
                         :allow-missing-post? (fn [request] false)
                         :resource-exists? (fn [request] false)
                         :previously-existed? (fn [request] true)})
          req (assoc test-request :request-method :post)]
      (let [response (run req res)]
        (is (= 410 (:status response))))))

  (testing "N5, POST to Missing Resource, Not Allowed, Body Present"
    (let [res (resource {"text/html" (fn [request] {:body "testing"})}
                        {:allowed-methods (fn [request] [:post])
                         :allow-missing-post? (fn [request]
                                                [false
                                                 {:headers {"content-type"
                                                            "text/plain"}
                                                  :body "No way, buddy!"}])
                         :resource-exists? (fn [request] false)
                         :previously-existed? (fn [request] true)})
          req (assoc test-request :request-method :post)]
      (let [response (run req res)]
        (is (and (= 410 (:status response))
                 (= "No way, buddy!" (:body response)))))))

  (testing "N5, POST to Missing Resource, Not Allowed"
    (let [res (resource {"text/html" (fn [request] {:body "testing"})}
                        {:allowed-methods (fn [request] [:post])
                         :allow-missing-post? (fn [request] false)
                         :resource-exists? (fn [request] false)
                         :previously-existed? (fn [request] true)})
          req (assoc test-request :request-method :post)]
      (let [response (run req res)]
        (is (= 410 (:status response))))))

  (testing "I4, PUT to Moved Permanently"
    (let [res (resource {"text/html" (fn [request] {:body "testing"})}
                        {:allowed-methods (fn [request] [:put])
                         :resource-exists? (fn [request] false)
                         :moved-permanently? (fn [request] "/testing/29292")})
          req (assoc test-request :request-method :put)]
      (let [response (run req res)]
        (is (= 301 (:status response))))))

  (testing "P3, PUT, Conflict"
    (let [res (resource {"text/html" (fn [request] {:body "testing"})}
                        {:allowed-methods (fn [request] [:put])
                         :resource-exists? (fn [request] false)
                         :is-conflict? (fn [request] true)})
          req (assoc test-request :request-method :put)]
      (let [response (run req res)]
        (is (= 409 (:status response))))))

  (testing "P3, PUT, Conflict, 409 Body"
    (let [res (resource {"text/html" (fn [request] {:body "testing"})}
                        {:allowed-methods (fn [request] [:put])
                         :resource-exists? (fn [request] false)
                         :is-conflict?
                         (fn [request]
                           {:headers {"content-type" "text/plain"}
                            :body "Doh! We have one of those already."})})
          req (assoc test-request :request-method :put)]
      (let [response (run req res)]
        (is (and (= 409 (:status response))
                 (= "text/plain; charset=utf-8"
                    ((:headers response) "Content-Type"))
                 (= "Doh! We have one of those already."
                    (:body response)))))))

  (testing "G7, Resource Does Not Exist"
    (let [res (resource {"text/html" (fn [request] {:body "testing"})}
                        {:resource-exists? (fn [request] false)})
          req test-request]
      (let [response (run req res)]
        (is (= 404 (:status response))))))

  (testing "G7, Resource Does Not Exist with Body"
    (let [res (resource {"text/html" (fn [request] {:body "testing"})}
                        {:resource-exists? (fn [request]
                                             [false
                                              {:body "I'm not sure what to say."
                                               :headers {"content-type"
                                                         "text/plain"}}])})
          req test-request]
      (let [response (run req res)]
        (is (and (= 404 (:status response))
                 (= "I'm not sure what to say." (:body response)))))))

  (testing "G7, Resource Does Not Exist with Body and Status Code"
    (let [res (resource {"text/html" (fn [request] {:body "testing"})}
                        {:resource-exists? (fn [request]
                                             [false
                                              {:status 404
                                               :body "I'm not sure what to say."
                                               :headers {"content-type"
                                                         "text/plain"}}])})
          req test-request]
      (let [response (run req res)]
        (is (and (= 404 (:status response))
                 (= "I'm not sure what to say." (:body response))))))))

  (testing "Response returns a String"
    (let [res (resource {"text/html" (fn [request] "testing")})
          req test-request]
      (let [response (run req res)]
        (is (and (= 200 (:status response))
                 (= "testing" (:body response)))))))

  (testing "Response returns a Integer"
    (let [res (resource {"text/html" (fn [request] 42)})
          req test-request]
      (let [response (run req res)]
        (is (and (= 200 (:status response))
                 (= 42 (:body response)))))))