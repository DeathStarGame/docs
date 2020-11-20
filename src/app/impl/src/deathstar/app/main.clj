(ns deathstar.app.main
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string :as str]

   [cljctools.csp.op.spec :as op.spec]
   [cljctools.cljc.core :as cljc.core]

   [cljctools.rsocket.spec :as rsocket.spec]
   [cljctools.rsocket.chan :as rsocket.chan]
   [cljctools.rsocket.impl :as rsocket.impl]
   [cljctools.rsocket.examples-java]
   [cljctools.rsocket.examples]

   [deathstar.app.spec :as app.spec]
   [deathstar.app.chan :as app.chan]

   [deathstar.peernode.spec :as peernode.spec]
   [deathstar.peernode.chan :as peernode.chan]

   [deathstar.ui.spec :as ui.spec]
   [deathstar.ui.chan :as ui.chan]))

(def channels (merge
               (app.chan/create-channels)
               (ui.chan/create-channels)
               (peernode.chan/create-channels)))

(def channels-rsocket-peernode (rsocket.chan/create-channels))
(def channels-rsocket-ui (rsocket.chan/create-channels))

(pipe (::peernode.chan/ops| channels) (::rsocket.chan/ops| channels-rsocket-peernode))
(pipe (::rsocket.chan/requests| channels-rsocket-peernode) (::app.chan/ops| channels))

(pipe (::ui.chan/ops| channels) (::rsocket.chan/ops| channels-rsocket-ui))
(pipe (::rsocket.chan/requests| channels-rsocket-ui) (::app.chan/ops| channels))

(def state (atom
            {::app.spec/counter 0
             ::app.spec/games-list {}}))

(defn create-proc-ops
  [channels ctx]
  (let [{:keys [::app.chan/ops|]} channels]
    (go
      (loop []
        (when-let [[value port] (alts! [ops|])]
          (condp = port
            ops|
            (condp = (select-keys value [::op.spec/op-key ::op.spec/op-type ::op.spec/op-orient])

              {::op.spec/op-key ::app.chan/init}
              (let [{:keys []} value]
                (println ::init)
                (go (loop []
                      (<! (timeout (* 1000 (+ 1 (rand-int 2)))))
                      (swap! state update ::app.spec/counter inc)
                      (ui.chan/op
                       {::op.spec/op-key ::ui.chan/update-state
                        ::op.spec/op-type ::op.spec/fire-and-forget}
                       channels
                       @state)
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
                        (recur)))))))
        (recur)))))

#_(def rsocket-peernode (rsocket.impl/create-proc-ops
                         channels-rsocket-peernode
                         {::rsocket.spec/connection-side ::rsocket.spec/initiating
                          ::rsocket.spec/host "peernode"
                          ::rsocket.spec/port 7000
                          ::rsocket.spec/transport ::rsocket.spec/websocket}))

(def rsocket-ui (rsocket.impl/create-proc-ops
                 channels-rsocket-ui
                 {::rsocket.spec/connection-side ::rsocket.spec/accepting
                  ::rsocket.spec/host "0.0.0.0"
                  ::rsocket.spec/port 7000
                  ::rsocket.spec/transport ::rsocket.spec/websocket}))

(def ops (create-proc-ops channels {}))

(defn -main [& args]
  (println ::-main)
  (app.chan/op
   {::op.spec/op-key ::app.chan/init}
   channels
   {}))

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