(ns name-bazaar.server.db-sync
  (:require
    [cljs-web3.eth :as web3-eth]
    [cljs.core.async :refer [<! >! chan]]
    [district0x.shared.big-number :as bn]
    [district0x.server.state :as state]
    [name-bazaar.server.contracts-api.english-auction-offering :as english-auction-offering]
    [name-bazaar.server.contracts-api.ens :as ens]
    [name-bazaar.server.contracts-api.offering :as offering]
    [name-bazaar.server.contracts-api.offering-registry :as offering-registry]
    [name-bazaar.server.contracts-api.offering-requests :as offering-requests]
    [name-bazaar.server.db :as db]
    [name-bazaar.shared.utils :refer [offering-version->type]]
    [clojure.string :as string]
    [name-bazaar.server.contracts-api.registrar :as registrar])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defonce event-filters (atom []))

(defn node-owner? [server-state offering-address {:keys [:offering/name :offering/node] :as offering}]
  (let [ch (chan)]
    (go
      (let [ens-owner-ch (ens/owner server-state {:ens.record/node node})
            split-name (string/split name ".")]
        (>! ch
            (if (and (= (count split-name) 2)               ;; For TLD .eth names we must also verify deed ownership
                     (= (last split-name) "eth"))
              (and (= (second (<! (registrar/entry-deed-owner server-state {:ens.record/label (first split-name)})))
                      offering-address)
                   (= (second (<! ens-owner-ch))
                      offering-address))
              (= (second (<! ens-owner-ch))                 ;; For other names just basic ENS ownership check
                 offering-address)))))
    ch))

(defn english-auction? [version]
  (= (offering-version->type version) :english-auction-offering))

(defn get-offering-from-event [server-state event-args]
  (let [ch (chan)]
    (go
      (let [offering (second (<! (offering/get-offering server-state (:offering event-args))))
            english-auction-offering (when (english-auction? (:version event-args))
                                       (second (<! (english-auction-offering/get-english-auction-offering server-state (:offering event-args)))))
            owner? (<! (node-owner? server-state (:offering event-args) offering))
            offering (-> offering
                       (merge english-auction-offering)
                       (assoc :offering/node-owner? owner?))]
        (>! ch offering)))
    ch))

(defn on-offering-added [server-state err {:keys [:args]}]
  (go
    (let [offering (<! (get-offering-from-event server-state args))
          db (state/db server-state)]
      (.serialize db (fn []
                       (db/upsert-offering! db offering)
                       (db/upsert-ens-record! db {:ens.record/node (:offering/node offering)
                                                  :ens.record/last-offering (:offering/address offering)}))))))

(defn on-offering-changed [server-state err {:keys [:args]}]
  (go
    (let [offering (<! (get-offering-from-event server-state args))]
      (db/upsert-offering! (state/db server-state) offering))))

(defn stop-watching-filters! []
  (doseq [filter @event-filters]
    (when filter
      (web3-eth/stop-watching! filter (fn [])))))

(defn on-new-requests [server-state err {{:keys [:node :name]} :args}]
  (go
    (let [requests-count (first (second (<! (offering-requests/requests-counts server-state {:offering-requests/nodes [node]}))))]
      (db/upsert-offering-requests! (state/db server-state) {:offering-request/node node
                                                             :offering-request/name name
                                                             :offering-request/requesters-count requests-count}))))

(defn on-request-added [server-state err {{:keys [:node :name requesters-count]} :args}]
  (db/upsert-offering-requests! (state/db server-state) {:offering-request/node node
                                                         :offering-request/name name
                                                         :offering-request/requesters-count (bn/->number requesters-count)}))

(defn on-ens-transfer [server-state err {{:keys [:node :owner]} :args}]
  (db/set-offering-node-owner?! (state/db server-state) {:offering/address owner
                                                         :offering/node-owner? true}))

(defn start-syncing! [server-state]
  (db/create-tables! (state/db server-state))
  (stop-watching-filters!)
  (reset! event-filters
          [(offering-registry/on-offering-changed server-state
                                                  {}
                                                  "latest"
                                                  (partial on-offering-changed server-state))

           (offering-requests/on-request-added server-state
                                               {}
                                               "latest"
                                               (partial on-request-added server-state))

           (ens/on-transfer server-state
                            {}
                            "latest"
                            (partial on-ens-transfer server-state))

           (offering-registry/on-offering-added server-state
                                                {}
                                                {:from-block 0 :to-block "latest"}
                                                (partial on-offering-added server-state))

           (offering-requests/on-new-requests server-state
                                              {}
                                              {:from-block 0 :to-block "latest"}
                                              (partial on-new-requests server-state))]))

(defn stop-syncing! []
  (stop-watching-filters!)
  (reset! event-filters []))