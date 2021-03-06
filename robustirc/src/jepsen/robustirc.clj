(ns jepsen.robustirc
  (:require
    [clojure.tools.logging    :refer [debug info warn]]
    [clojure.java.io          :as io]
    [clojure.string           :as str]
    [clojure.core.async       :as async]
    [clj-http.client :as httpclient]
    [digest :as digest]
    [jepsen
      [client :as client]
      [core :as jepsen]
      [db :as db]
      [tests :as tests]
      [control :as c :refer [|]]
      [checker :as checker]
      [nemesis :as nemesis]
      [generator :as gen]]
    [jepsen.os.debian :as debian]
    [cheshire.core            :as json])
  (:import
    [java.net URL]))

(defn db []
  "RobustIRC."
  (reify db/DB
    (setup! [this test node]
      (c/su
        (c/ssh* {:cmd "killall robustirc"})
	(try (c/exec :dpkg-query :-l :golang-go)
	     (catch RuntimeException _
	       (info "Installing golang-go")
	       (c/exec :apt-get :install :-y :golang-go)))
	(try (c/exec :dpkg-query :-l :mercurial)
	     (catch RuntimeException _
	       (info "Installing mercurial")
	       (c/exec :apt-get :install :-y :mercurial)))
	(c/exec :env "GOPATH=~/gocode"
	        :go :get :-u "github.com/robustirc/robustirc")

        ; generated by resources/gencert.go
        (c/upload (.getFile (io/resource "cert.pem")) "/tmp/cert.pem")
        (c/upload (.getFile (io/resource "key.pem")) "/tmp/key.pem")
        (c/ssh* {:cmd "rm -rf /var/lib/robustirc"})
        (c/ssh* {:cmd "mkdir -p /var/lib/robustirc"})
        (jepsen/synchronize test)

        (let [cmd (str
                "/sbin/start-stop-daemon --start --background --exec ~/gocode/bin/robustirc --"
                " -listen=" (name node) ":13001"
                " -network_password=secret"
                " -network_name=jepsen"
                " -tls_cert_path=/tmp/cert.pem"
                " -tls_ca_file=/tmp/cert.pem"
                " -tls_key_path=/tmp/key.pem"
                " -singlenode")]
          (if-not (= node (jepsen/primary test))
                (Thread/sleep 1000)
                (do
                  (info node (str "running: " cmd))
                  (c/ssh* {:cmd cmd})
                  (Thread/sleep 5000))))
        (jepsen/synchronize test)
        (let [cmd (str
                "/sbin/start-stop-daemon --start --background --exec ~/gocode/bin/robustirc --"
                " -listen=" (name node) ":13001"
                " -network_password=secret"
                " -network_name=jepsen"
                " -tls_cert_path=/tmp/cert.pem"
                " -tls_ca_file=/tmp/cert.pem"
                " -tls_key_path=/tmp/key.pem"
                " -join=" (name (jepsen/primary test)) ":13001")]
          (if (= node (jepsen/primary test))
                (Thread/sleep 100)
                (do
                  (info node (str "running: " cmd))
                  (c/ssh* {:cmd cmd})
                  (Thread/sleep 5000))))
        (jepsen/synchronize test)
        (info node "setup done")))

    (teardown! [this test node]
      (c/su
        (c/ssh* {:cmd "killall robustirc"}))
      )))

(defn with-nemesis
  "Wraps a client generator in a nemesis that induces failures and eventually
  stops."
  [client]
  (gen/phases
    (gen/phases
      (->> client
           (gen/nemesis
             (gen/seq (cycle [(gen/sleep 0)
                              {:type :info, :f :start}
                              (gen/sleep 10)
                              {:type :info, :f :stop}])))
           (gen/time-limit 30))
      (gen/nemesis (gen/once {:type :info, :f :stop}))
      (gen/sleep 5))))

(defn create-session
  [node]
  (assoc (json/parse-string
    (get
      (httpclient/post (str "https://" (name node) ":13001/robustirc/v1/session")
        {:insecure? true})
      :body))
    :node node))

(defn post-message
  [session ircmessage]
  (let [msgid (bit-or (rand-int Integer/MAX_VALUE)
                      (Long/parseLong (subs (digest/md5 ircmessage) 17) 16))]
    (httpclient/post
                (str "https://" (name (get session :node)) ":13001/robustirc/v1/" (get session "Sessionid") "/message")
      {:headers {"X-Session-Auth" (get session "Sessionauth")}
       :insecure? true
       :content-type :json
       :form-params {:Data ircmessage
                     :ClientMessageId msgid}})))

(defn read-all
  [session timeoutmsec]
  (let [out (atom [])]
    (jepsen.util/timeout
      timeoutmsec
      @out
      (doseq [msg (json/parsed-seq (io/reader (get (httpclient/get
                  (str "https://" (name (get session :node)) ":13001/robustirc/v1/" (get session "Sessionid") "/messages")
                  {:headers {"X-Session-Auth" (get session "Sessionauth")}
                   :query-params {:lastseen "0.0"}
                   :insecure? true
                   :as :stream}) :body)))]
        (swap! out conj msg)))))

; XXX: use a proper IRC parser for filter-topic and extract-topic
(defn filter-topic
  [msg]
  (let [s (str/split (get msg "Data") #" ")]
    (and
      (> (count s) 1)
      (= (nth s 1) "TOPIC"))))

(defn extract-topic
  [msg]
  (let [s (str/split (get msg "Data") #":")]
    (Integer/parseInt (nth s (- (count s) 1)))))

(defrecord SetClient [node session]
  client/Client

  (setup! [this test node]
    (info node "creating robustsession")
    (let [session (create-session node)]
      (info node (str "session " (get session "Sessionid")))
      (post-message session (str "NICK " (name node)))
      (post-message session "USER j j j j")
      (post-message session "JOIN #jepsen")
      (assoc this :node node
                  :session session)))

  (invoke! [this test op]
    (try
      (case (:f op)
        :add (try
                 (do
                   (post-message (-> session) (str "TOPIC #jepsen :" (:value op)))
                   (assoc op :type :ok))
                 (catch Exception e
                  (assoc op :type :fail :value :node-failure)))
        :read (let [msgs (read-all session 1000)
                    filtered (filter filter-topic msgs)
                    converted (map extract-topic filtered)]
                (assoc op
                  :type :ok
                  :value (set converted))))))

  (teardown! [this test]
    (info node "teardown code goes here")))

(defn set-client
  [node]
  (SetClient. nil nil))

(defn basic-test
  [opts]
  (merge tests/noop-test
         {:name (str "robustirc " (:name opts))
          :os   debian/os
          :db   (db)
          :nemesis (nemesis/partition-random-halves)}
         (dissoc opts :name :version)))

(defn sets-test
  [version]
  (basic-test
    {:name "set"
     :version version
     :client (set-client nil)
     :generator (gen/phases
                  (->> (range)
                       (map (partial array-map
                                     :type :invoke
                                     :f :add
                                     :value))
                       gen/seq
                       (gen/delay 1/10)
                       with-nemesis)
                  (->> {:type :invoke, :f :read, :value nil}
                       gen/once
                       gen/clients))
     :checker (checker/compose
                 {:perf (checker/perf)
                  :set   checker/set})}))


