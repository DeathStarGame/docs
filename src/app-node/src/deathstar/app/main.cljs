(ns deathstar.app.main
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string :as str]
   [cljs.core.async.interop :refer-macros [<p!]]
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [cljs.reader :refer [read-string]]

   [cljctools.csp.op.spec :as op.spec]
   [cljctools.cljc.core :as cljc.core]

   [cljctools.rsocket.spec :as rsocket.spec]
   [cljctools.rsocket.chan :as rsocket.chan]
   [cljctools.rsocket.impl :as rsocket.impl]
   [cljctools.rsocket.examples]

   [deathstar.app.spec :as app.spec]
   [deathstar.app.chan :as app.chan]

   [cljctools.peernode.spec :as peernode.spec]
   [cljctools.peernode.chan :as peernode.chan]

   [deathstar.scenario-api.spec :as scenario-api.spec]
   [deathstar.scenario-api.chan :as scenario-api.chan]

   [deathstar.ui.spec :as ui.spec]
   [deathstar.ui.chan :as ui.chan]))

(defonce fs (js/require "fs"))
(defonce path (js/require "path"))
(defonce axios (.-default (js/require "axios")))
(defonce puppeteer (js/require "puppeteer-core"))
(defonce OrbitDB (js/require "orbit-db"))
(defonce IpfsClient (js/require "ipfs-http-client"))

(defonce channels (merge
                   (app.chan/create-channels)
                   (ui.chan/create-channels)
                   (peernode.chan/create-channels)))

(defonce channels-rsocket-peernode (rsocket.chan/create-channels))
(defonce channels-rsocket-ui (rsocket.chan/create-channels))
(defonce channels-rsocket-scenario (rsocket.chan/create-channels))
(defonce channels-rsocket-player (rsocket.chan/create-channels))

(pipe (::peernode.chan/ops| channels) (::rsocket.chan/ops| channels-rsocket-peernode))
(pipe (::rsocket.chan/requests| channels-rsocket-peernode) (::app.chan/ops| channels))
(defonce rsocket-peernode (rsocket.impl/create-proc-ops
                       channels-rsocket-peernode
                       {::rsocket.spec/connection-side ::rsocket.spec/initiating
                        ::rsocket.spec/host "peernode"
                        ::rsocket.spec/port 7000
                        ::rsocket.spec/transport ::rsocket.spec/websocket}))

(pipe (::ui.chan/ops| channels) (::rsocket.chan/ops| channels-rsocket-ui))
(go (loop []
      (when-let [value (<! (::rsocket.chan/requests| channels-rsocket-ui))]
        (let [{:keys [::op.spec/op-key]} value]
          (cond
            (isa? op-key ::app.chan/op) (put! (::app.chan/ops| channels) value)
            (isa? op-key ::scenario-api.chan/op) (put! (::rsocket.chan/ops| channels-rsocket-scenario) value)))
        (recur))))

(defonce rsocket-ui (rsocket.impl/create-proc-ops
                 channels-rsocket-ui
                 {::rsocket.spec/connection-side ::rsocket.spec/accepting
                  ::rsocket.spec/host "0.0.0.0"
                  ::rsocket.spec/port 7001
                  ::rsocket.spec/transport ::rsocket.spec/websocket}))

(pipe (::rsocket.chan/requests| channels-rsocket-player) (::rsocket.chan/ops| channels-rsocket-scenario))
(defonce rsocket-scenario (rsocket.impl/create-proc-ops
                       channels-rsocket-scenario
                       {::rsocket.spec/connection-side ::rsocket.spec/accepting
                        ::rsocket.spec/host "0.0.0.0"
                        ::rsocket.spec/port 7002
                        ::rsocket.spec/transport ::rsocket.spec/websocket}))

(go (loop []
      (when-let [value (<! (::rsocket.chan/requests| channels-rsocket-scenario))]
        (let [{:keys [::op.spec/op-key]} value]
          (cond
            (isa? op-key ::app.chan/op) (put! (::app.chan/ops| channels) value)
            :else (put! (::rsocket.chan/ops| channels-rsocket-player) value)))
        (recur))))

(defonce rsocket-player (rsocket.impl/create-proc-ops
                     channels-rsocket-player
                     {::rsocket.spec/connection-side ::rsocket.spec/accepting
                      ::rsocket.spec/host "0.0.0.0"
                      ::rsocket.spec/port 7003
                      ::rsocket.spec/transport ::rsocket.spec/websocket}))

(defonce ctx {::app.spec/state*
              (atom
               {::app.spec/peer-id nil
                ::app.spec/tournaments {}
                ::app.spec/games {}
                ::app.spec/scenarios {}
                ::app.spec/peer-metas {}})
              
              ::app.spec/tournaments* (atom {})
              ::app.spec/games* (atom {})
              ::app.spec/scenarios* (atom {})

              ::app.spec/app-eventlog* (atom {::eventlog nil
                                              ::eventlog-prev-hash nil})
              ::app.spec/browser* (atom nil)
              ::app.spec/ipfs* (atom nil)
              ::app.spec/orbitdb* (atom nil)
              ::app.spec/TOPIC-ID "deathstar-1a58070"})

(defonce _ (add-watch (::app.spec/state* ctx) ::watch
                      (fn [k atom-ref oldstate newstate]
                        (ui.chan/op
                         {::op.spec/op-key ::ui.chan/update-state
                          ::op.spec/op-type ::op.spec/fire-and-forget}
                         channels
                         newstate))))


(comment

  (js/Object.keys ipfs)
  (js/Object.keys ipfs.pubsub)

  (go
    (let [id (<p! (daemon._ipfs.id))]
      (println (js-keys id))
      (println (.-id id))
      (println (format "id is %s" id))))


  (def orbitdb @(::app.spec/orbitdb* ctx))
  (go
    (def eventlog (<p! (.eventlog orbitdb "foo")))
    (<p! (.load eventlog)))
  (go
    (println (<p! (.add eventlog (pr-str {::app.spec/peer-id (::app.spec/peer-id @(::app.spec/state* ctx))
                                          ::random-int 1  #_(rand-int 1000)})))))
  (-> eventlog
      (.iterator  #js {"limit" -1})
      (.collect)
      (.map (fn [e]
              (println (js-keys e))
              (println (js-keys (.-payload e)))
              (println (.-hash e))
              (println (.-next e))
              (read-string (.-value (.-payload e)))))
      #_(first)
      (println))

  (go
    (<p! (.drop eventlog)))

  (empty? #js [])



  ;;
  )

(declare init-puppeteer)

(defn create-proc-ops
  [channels ctx opts]
  (let [{:keys [::app.chan/ops|]} channels
        {:keys [::app.spec/state*
                ::app.spec/ipfs*
                ::app.spec/orbitdb*
                ::app.spec/tournaments*
                ::app.spec/games*
                ::app.spec/scenarios*
                ::app.spec/app-eventlog*
                ::app.spec/TOPIC-ID]} ctx]
    (go
      (loop []
        (when-let [[value port] (alts! [ops|])]
          (condp = port
            ops|
            (condp = (select-keys value [::op.spec/op-key ::op.spec/op-type ::op.spec/op-orient])

              {::op.spec/op-key ::app.chan/init
               ::op.spec/op-type ::op.spec/fire-and-forget}
              (let [{:keys []} value]
                (println ::init)
                (try
                  (let [ipfs (IpfsClient "http://ipfs:5001")]
                    (reset! ipfs* ipfs)
                    (swap! state* assoc ::app.spec/peer-id (.-id (<p! (.id ipfs))))
                    (reset! orbitdb* (<p! (.createInstance OrbitDB ipfs (clj->js {"directory" "/root/.orbitdb"}))))
                    (swap! app-eventlog* assoc ::eventlog (<p! (.eventlog @orbitdb*
                                                                          TOPIC-ID
                                                                          (clj->js {"accessController"
                                                                                    {"write" ["*"]}})))))
                  (let [app-eventlog (::eventlog @app-eventlog*)
                        done| (chan 1)]
                    #_(<p! (.drop (::eventlog @app-eventlog*)))
                    (<p! (.load app-eventlog))
                    (-> app-eventlog
                        (.iterator  #js {"limit" -1
                                         "reverse" true})
                        (.collect)
                        (.map (fn [e]
                                (let [value (read-string (.-value (.-payload e)))]
                                  (put! ops| value))
                                (when (empty? (.-next e))
                                  (swap! app-eventlog*
                                         assoc
                                         ::eventlog-prev-hash
                                         (.-hash e))
                                  (close! done|)))))
                    (<! done|)
                    (.on (.-events app-eventlog)
                         "replicated"
                         (fn [address]
                           (-> app-eventlog
                               (.iterator  #js {"gt" (::eventlog-prev-hash @app-eventlog*)})
                               (.collect)
                               (.map (fn [e]
                                       (let [value (read-string (.-value (.-payload e)))]
                                         (put! ops| value))
                                       (when (empty? (.-next e))
                                         (swap! app-eventlog*
                                                assoc
                                                ::eventlog-prev-hash
                                                (.-hash e)))))))))
                  (catch js/Error err (println err)))
                (let [ipfs @ipfs*
                      id (.-id (<p! (.id ipfs)))
                      text-encoder (js/TextEncoder.)
                      text-decoder (js/TextDecoder.)]
                  (.subscribe (.-pubsub ipfs)
                              TOPIC-ID
                              (fn [msg]
                                (when-not (= id (.-from msg))
                                  (do
                                    (swap! state* assoc-in [::app.spec/peer-metas (.-from msg)]
                                           (merge
                                            (read-string (.decode text-decoder  (.-data msg)))
                                            {::app.spec/peer-id (.-from msg)
                                             ::app.spec/received-at (.now js/Date)}))
                                    #_(println (format "id: %s" id))
                                    #_(println (format "from: %s" (.-from msg)))
                                    #_(println (format "data: %s" (.decode text-decoder  (.-data msg))))
                                    #_(println (format "topicIDs: %s" msg.topicIDs))))))
                  (go (loop [counter 0]
                        (<! (timeout 2000))
                        (.publish (.-pubsub ipfs)
                                  TOPIC-ID
                                  (-> text-encoder
                                      (.encode  (pr-str {::app.spec/peer-id id
                                                         ::app.spec/counter counter}))))
                        (recur (inc counter))))
                  (go (loop []
                        (<! (timeout 4000))
                        (doseq [[peer-id {:keys [::app.spec/received-at]
                                          :as peer-meta}] (::app.spec/peer-metas   @state*)
                                :when (> (- (.now js/Date) received-at) 8000)]
                          (println ::removing-peer)
                          (swap! state* update-in [::app.spec/peer-metas] dissoc peer-id))
                        (recur)))))

              #_(let [{:keys []} value]
                  (println ::init)
                  (try
                    (let [options (clj->js {"accessController"
                                            {"write" ["*"] #_[(.. orbitdb -identity -publicKey)]}})]
                      (set! ipfs (IpfsClient "http://ipfs:5001"))
                      (set! orbitdb (<p! (.createInstance OrbitDB ipfs (clj->js {"directory" "/root/.orbitdb"}))))
                      (set! dblog (<p! (.log orbitdb TOPIC-ID options)))
                      (<p! (.load dblog))
                      (set! dbdocs (<p! (.docs orbitdb "foo")))
                      (<p! (.load dbdocs))
                      (do
                        #_(println (.. orbitdb -identity -publicKey))
                        #_(println (.-address dblog))
                        (println (.toString (.-address dblog)))
                        #_(println (.. dblog -identity -publicKey))
                        #_(println (.. dbdocs -identity -publicKey)))
                      (let [id (.-id (<p! (ipfs.id)))]
                        (go (loop []
                              (<! (timeout 2000))
                              (.add dblog (pr-str {::app.spec/peer-id id
                                                   ::locale-time-string (.toLocaleTimeString (js/Date.))
                                                   ::random-int (rand-int 1000)}))
                              (recur))))
                      (.on (.-events dblog) "replicated" (fn [address]
                                                           (-> dblog
                                                               (.iterator  #js {"limit" 1})
                                                               (.collect)
                                                               (.map (fn [e] (.-value (.-payload e))))
                                                               (println)))))
                    (catch js/Error err (println err)))



                  #_(<! (init-puppeteer))


                  #_(go (loop []
                          (<! (timeout 2000))
                          #_(swap! state* update ::app.spec/counter inc)
                          (ui.chan/op
                           {::op.spec/op-key ::ui.chan/update-state
                            ::op.spec/op-type ::op.spec/fire-and-forget}
                           channels
                           @state*)
                          (recur)))
                  #_(go
                      (let [out| (chan 64)]
                        (peernode.chan/op
                         {::op.spec/op-key ::peernode.chan/request-pubsub-stream
                          ::op.spec/op-type ::op.spec/request-stream
                          ::op.spec/op-orient ::op.spec/request}
                         channels
                         out|)
                        (loop []
                          (when-let [value  (<! out|)]
                            (println ::request-pubsub-stream)
                            (println value)
                            (recur)))))
                  #_(go (loop []
                          (<! (timeout (* 1000 (+ 1 (rand-int 2)))))
                          (peernode.chan/op
                           {::op.spec/op-key ::peernode.chan/pubsub-publish
                            ::op.spec/op-type ::op.spec/fire-and-forget}
                           channels
                           {::some ::value})
                          (recur))))

              {::op.spec/op-key ::app.chan/request-state-update
               ::op.spec/op-type ::op.spec/fire-and-forget}
              (let [{:keys []} value]
                (ui.chan/op
                 {::op.spec/op-key ::ui.chan/update-state
                  ::op.spec/op-type ::op.spec/fire-and-forget}
                 channels
                 @state*))

              {::op.spec/op-key ::app.chan/create-tournament
               ::op.spec/op-type ::op.spec/fire-and-forget}
              (let [{:keys [::app.spec/peer-name]
                     :or {peer-name (str "peer" (rand-int 1000))}} value
                    frequency (str (cljc.core/rand-uuid))
                    peer-id (get @state* ::app.spec/peer-id)]
                #_(.add (::eventlog @app-eventlog*)
                        (pr-str (merge
                                 {::op.spec/op-key ::app.chan/created-tournament
                                  ::op.spec/op-type ::op.spec/fire-and-forget}
                                 {::app.spec/tournament tournament})))
                #_(app.chan/op
                   {::op.spec/op-key ::app.chan/created-tournament
                    ::op.spec/op-type ::op.spec/fire-and-forget}
                   channels
                   {::app.spec/tournament tournament})
                (app.chan/op
                 {::op.spec/op-key ::app.chan/joined-tournament
                  ::op.spec/op-type ::op.spec/fire-and-forget}
                 channels
                 {::app.spec/frequency frequency
                  ::app.spec/peer-name peer-name
                  ::app.spec/peer-id peer-id
                  ::app.spec/host-id peer-id}))

              {::op.spec/op-key ::app.chan/join-tournament
               ::op.spec/op-type ::op.spec/fire-and-forget}
              (let [{:keys [::app.spec/frequency
                            ::app.spec/peer-name]} value
                    peer-id (get @state* ::app.spec/peer-id)]
                (println ::join-tournament)
                (app.chan/op
                 {::op.spec/op-key ::app.chan/joined-tournament
                  ::op.spec/op-type ::op.spec/fire-and-forget}
                 channels
                 {::app.spec/frequency frequency
                  ::app.spec/peer-name peer-name
                  ::app.spec/peer-id peer-id}))

              {::op.spec/op-key ::app.chan/joined-tournament
               ::op.spec/op-type ::op.spec/fire-and-forget}
              (let [{:keys [::app.spec/frequency
                            ::app.spec/peer-name
                            ::app.spec/host-id
                            ::app.spec/peer-id]} value]
                (println ::joined-tournament)
                (when-not (get @tournaments* frequency)
                  (let [tournament (merge {::app.spec/frequency frequency
                                           ::app.spec/peer-metas {}}
                                          (select-keys value [::app.spec/host-id]))
                        ipfs @ipfs*
                        eventlog (<p! (.eventlog @orbitdb*
                                                 frequency
                                                 (clj->js {"accessController"
                                                           {"write" ["*"] #_[(.. orbitdb -identity -publicKey)]}})))
                        done| (chan 1)
                        close-emit-meta| (chan 1)
                        close-iterate-metas| (chan 1)
                        release
                        (fn []
                          (go
                            (close! close-emit-meta|)
                            (close! close-iterate-metas|)
                            (.unsubscribe (.-pubsub ipfs) frequency)
                            (<p! (.drop eventlog))
                            (<p! (.close eventlog))))
                        tournanment* (atom {::eventlog nil
                                            ::eventlog-prev-hash nil
                                            ::release release})]
                    (<p! (.load eventlog))
                    (-> eventlog
                        (.iterator  #js {"limit" -1
                                         "reverse" true})
                        (.collect)
                        (.map (fn [e]
                                (let [value (read-string (.-value (.-payload e)))]
                                  (put! ops| value))
                                (when (empty? (.-next e))
                                  (swap! tournanment*
                                         assoc
                                         ::eventlog-prev-hash
                                         (.-hash e))
                                  (close! done|)))))
                    (<! done|)
                    (swap! state* update ::app.spec/tournaments assoc frequency tournament)
                    (swap! tournanment* assoc ::eventlog eventlog)
                    (swap! tournaments* assoc frequency tournanment*)
                    (<p! (.add (::eventlog @app-eventlog*)
                               (pr-str (merge
                                        value
                                        {::op.spec/op-key ::app.chan/joined-tournament
                                         ::op.spec/op-type ::op.spec/fire-and-forget}))))
                    (<p! (.add eventlog
                               (pr-str (merge
                                        value
                                        {::op.spec/op-key ::app.chan/joined-tournament
                                         ::op.spec/op-type ::op.spec/fire-and-forget}))))
                    (.on (.-events eventlog)
                         "replicated"
                         (fn [address]
                           (-> eventlog
                               (.iterator  #js {"gt" (::eventlog-prev-hash @tournanment*)})
                               (.collect)
                               (.map (fn [e]
                                       (let [value (read-string (.-value (.-payload e)))]
                                         (put! ops| value))
                                       (when (empty? (.-next e))
                                         (swap! tournanment*
                                                assoc
                                                ::eventlog-prev-hash
                                                (.-hash e))))))))
                    (let [{:keys [::app.spec/frequency
                                  ::app.spec/peer-name
                                  ::app.spec/host-id
                                  ::app.spec/peer-id]} value
                          peer-id (get @state* ::app.spec/peer-id)
                          text-encoder (js/TextEncoder.)
                          text-decoder (js/TextDecoder.)]
                      (.subscribe (.-pubsub ipfs)
                                  frequency
                                  (fn [msg]
                                    (when-not (= peer-id (.-from msg))
                                      (let [peer-id (.-from msg)]
                                        (swap! state* update-in
                                               [::app.spec/tournaments frequency ::app.spec/peer-metas peer-id]
                                               merge (merge
                                                      (read-string (.decode text-decoder  (.-data msg)))
                                                      {::app.spec/peer-id peer-id
                                                       ::app.spec/received-at (.now js/Date)})))
                                      #_(do
                                          (println (format "id: %s" id))
                                          (println (format "from: %s" (.-from msg)))
                                          (println (format "data: %s" (.decode text-decoder  (.-data msg))))
                                          (println (format "topicIDs: %s" msg.topicIDs))))))
                      (go (loop [counter 0]
                            (.publish (.-pubsub ipfs)
                                      frequency
                                      (-> text-encoder
                                          (.encode  (pr-str {::app.spec/peer-id peer-id
                                                             ::app.spec/counter counter}))))
                            (alt!
                              [(timeout 2000)]
                              ([value c|]
                               (recur (inc counter)))
                              [close-emit-meta|]
                              ([_ _]
                               (do nil)))))
                      (go (loop []
                            (alt!
                              [(<! (timeout 4000))]
                              ([value c|]
                               (doseq [[peer-id {:keys [::app.spec/received-at]
                                                 :as peer-meta}] (get-in @state* [::app.spec/tournaments frequency ::app.spec/peer-metas])
                                       :when (> (- (.now js/Date) received-at) 8000)]
                                 (println ::removing-peer-from-tournament)
                                 (swap! state* update-in
                                        [::app.spec/tournaments frequency ::app.spec/peer-metas]
                                        dissoc peer-id))
                               (recur))
                              [close-iterate-metas|]
                              ([_ _]
                               (do nil))))))

                    #_(ipfs.pubsub.subscribe
                       frequency
                       (fn [msg]
                         (when-not (= peer-id (.-from msg))
                           (do
                             (put! pubsub| (merge
                                            (read-string (.decode text-decoder  (.-data msg)))
                                            {::app.spec/peer-id (.-from msg)
                                             ::app.spec/received-at (.now js/Date)}))))))))
                (let [{:keys [::app.spec/frequency
                              ::app.spec/peer-name
                              ::app.spec/peer-id]}  value]
                  (swap! state* update-in
                         [::app.spec/tournaments frequency ::app.spec/peer-metas]
                         assoc peer-id {::app.spec/peer-name peer-name
                                        ::app.spec/peer-id peer-id})))


              {::op.spec/op-key ::app.chan/leave-tournament
               ::op.spec/op-type ::op.spec/fire-and-forget}
              (let [{:keys [::app.spec/frequency]} value]
                (println ::leave-tournament)
                (println value)
                (when (get @tournaments* frequency)
                  (let [{:keys [::release
                                ::eventlog]} @(get @tournaments* frequency)
                        peer-id (get @state* ::app.spec/peer-id)]
                    (<p! (.add eventlog
                               (pr-str
                                (merge {::op.spec/op-key ::app.chan/left-tournament
                                        ::op.spec/op-type ::op.spec/fire-and-forget}
                                       {::app.spec/frequency frequency
                                        ::app.spec/peer-id peer-id}))))
                    (<! (release))
                    (swap! tournaments* dissoc frequency)
                    (swap! state* update ::app.spec/tournaments dissoc frequency)
                    (app.chan/op
                     {::op.spec/op-key ::app.chan/left-tournament
                      ::op.spec/op-type ::op.spec/fire-and-forget}
                     channels
                     {::app.spec/frequency frequency
                      ::app.spec/peer-id peer-id}))))

              {::op.spec/op-key ::app.chan/left-tournament
               ::op.spec/op-type ::op.spec/fire-and-forget}
              (let [{:keys [::app.spec/frequency
                            ::app.spec/peer-id]} value]
                (println ::left-tournament)
                (println value)
                (when (get-in @state* [::app.spec/tournaments frequency])
                  (swap! state* update-in
                         [::app.spec/tournaments frequency ::app.spec/peer-metas]
                         dissoc peer-id)))))
          (recur))))))


(defonce ops (create-proc-ops channels ctx {}))

(defn main [& args]
  (println ::main)
  (app.chan/op
   {::op.spec/op-key ::app.chan/init}
   channels
   {}))

(def exports #js {:main main})

(when (exists? js/module)
  (set! js/module.exports exports))




(comment

  (cljc.core/rand-uuid)

  (go
    (println (<! (peernode.chan/op
                  {::op.spec/op-key ::peernode.chan/id
                   ::op.spec/op-type ::op.spec/request-response
                   ::op.spec/op-orient ::op.spec/request}
                  channels))))

  (go
    (let [out| (chan 64)]
      (peernode.chan/op
       {::op.spec/op-key ::peernode.chan/request-pubsub-stream
        ::op.spec/op-type ::op.spec/request-stream
        ::op.spec/op-orient ::op.spec/request}
       channels
       out|)
      (loop []
        (when-let [value  (<! out|)]
          (println value)
          (recur)))))


  (def counter (atom 0))

  (do
    (swap! counter inc)
    (peernode.chan/op
     {::op.spec/op-key ::peernode.chan/pubsub-publish
      ::op.spec/op-type ::op.spec/fire-and-forget}
     channels
     {::some @counter}))

  ;;
  )


(defn create-puppeteer
  []
  (go
    (try
      (let [data (<p! (.request axios
                                (clj->js
                                 {"url" "http://puppeteer:9222/json/version"
                                  "method" "get"
                                  "headers" {"Host" "localhost:9222"}})))

            webSocketDebuggerUrl (-> (aget (.-data data) "webSocketDebuggerUrl")
                                     (str/replace "localhost" "puppeteer"))]
        (<p! (.connect puppeteer
                       (clj->js
                        {"browserWSEndpoint" webSocketDebuggerUrl
                         #_"browserURL" #_"http://puppeteer:9222"}))))
      (catch js/Error err (println err)))))

(comment

  (go
    (let [data (<p! (.request axios
                              (clj->js
                               {"url" "http://puppeteer:9222/json/version"
                                "method" "get"
                                "headers" {"Host" "localhost:9222"}})))

          webSocketDebuggerUrl (-> (aget (.-data data) "webSocketDebuggerUrl")
                                   (str/replace "localhost" "puppeteer"))]
      (println webSocketDebuggerUrl)
      #_(println (js-keys data))
      #_(println (aget (.-data data) "webSocketDebuggerUrl"))))


  (go
    (try
      (let [page (<p! (.newPage browser))
            _ (<p! (.goto page "https://example.com"))
            dimensions (<p! (.evaluate page (fn []
                                              #js {"width" js/document.documentElement.clientWidth})))]
        (println dimensions))
      (catch js/Error err (println err))))

  ;;
  )