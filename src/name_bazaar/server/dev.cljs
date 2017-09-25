(ns name-bazaar.server.dev
  (:require
    [cljs-http.client :as http]
    [cljs-time.coerce :refer [to-epoch]]
    [cljs-time.core :as t]
    [cljs-web3.async.eth :as web3-eth-async]
    [cljs-web3.async.evm :as web3-evm-async]
    [cljs-web3.core :as web3]
    [cljs-web3.eth :as web3-eth]
    [cljs.core.async :refer [<! >! chan take!]]
    [cljs.nodejs :as nodejs]
    [cljs.pprint]
    [cljs.spec.alpha :as s]
    [district0x.server.api-server :as api-server]
    [district0x.server.effects :as d0x-effects]
    [district0x.server.state :as state :refer [*server-state*]]
    [district0x.server.utils :as u :refer [watch-event-once]]
    [district0x.shared.config :as config]
    [goog.date.Date]
    [honeysql.core :as sql]
    [honeysql.helpers :as sql-helpers]
    [name-bazaar.server.api]
    [name-bazaar.server.contracts-api.auction-offering :as auction-offering]
    [name-bazaar.server.contracts-api.auction-offering-factory :as auction-offering-factory]
    [name-bazaar.server.contracts-api.buy-now-offering :as buy-now-offering]
    [name-bazaar.server.contracts-api.buy-now-offering-factory :as buy-now-offering-factory]
    [name-bazaar.server.contracts-api.deed :as deed]
    [name-bazaar.server.contracts-api.ens :as ens]
    [name-bazaar.server.contracts-api.mock-registrar :as registrar]
    [name-bazaar.server.contracts-api.offering :as offering]
    [name-bazaar.server.contracts-api.offering-registry :as offering-registry]
    [name-bazaar.server.contracts-api.offering-requests :as offering-requests]
    [name-bazaar.server.contracts-api.used-by-factories :as used-by-factories]
    [name-bazaar.server.core :refer [mainnet-port]]
    [name-bazaar.server.db :as db]
    [name-bazaar.server.db-generator :as db-generator]
    [name-bazaar.server.db-sync :as db-sync]
    [name-bazaar.server.effects :refer [deploy-smart-contracts!]]
    [name-bazaar.shared.smart-contracts :refer [smart-contracts]]
    [print.foo :include-macros true])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(nodejs/enable-util-print!)
(set! js/XMLHttpRequest (nodejs/require "xhr2"))

(def namehash (aget (nodejs/require "eth-ens-namehash") "hash"))
(def sha3 (comp (partial str "0x") (aget (nodejs/require "js-sha3") "keccak_256")))
(def Web3 (nodejs/require "web3"))
(set! js/Web3 Web3)

(def total-accounts 6)
(def testrpc-port 8549)

(defn on-jsload []
  (config/load-config! config/default-config)
  (api-server/reg-route! :get "/config/:key" api-server/handle-get-config-key)
  (api-server/reg-route! :get "/config" api-server/handle-get-config)
  (api-server/start! (config/get-config :api-port))
  (d0x-effects/create-web3! *server-state* {:port testrpc-port}))

(defn deploy-to-mainnet! []
  (go
    (d0x-effects/create-web3! *server-state* {:port mainnet-port})
    (d0x-effects/load-smart-contracts! *server-state* smart-contracts)
    (<! (d0x-effects/load-my-addresses! *server-state*))
    (<! (deploy-smart-contracts! *server-state* {:persist? true}))))

(defn initialize! [server-state-atom]
  (let [ch (chan)]
    (go
      (db-sync/stop-syncing!)
      (d0x-effects/load-smart-contracts! server-state-atom smart-contracts)
      (<! (deploy-smart-contracts! server-state-atom {:persist? true}))
      (<! (db-generator/generate! @server-state-atom {:total-accounts total-accounts}))
      (d0x-effects/create-db! server-state-atom)
      (db-sync/start-syncing! @server-state-atom)
      (>! ch true))
    ch))

(defn -main [& _]
  (go
    (config/load-config! config/default-config)
    (<! (d0x-effects/start-testrpc! *server-state* {:total_accounts total-accounts
                                                    :port testrpc-port}))
    (d0x-effects/create-web3! *server-state* {:port testrpc-port})
    (d0x-effects/create-db! *server-state*)
    (d0x-effects/load-smart-contracts! *server-state* smart-contracts)
    (api-server/start! (config/get-config :api-port))
    (<! (d0x-effects/load-my-addresses! *server-state*))))

(set! *main-cli-fn* -main)

(comment
  (deploy-to-mainnet!)
  (run-mainnet!)
  (name-bazaar.server.core/-main)
  (state/my-addresses)

  (do
    (d0x-effects/create-db! district0x.server.state/*server-state*)
    (name-bazaar.server.db-sync/start-syncing! @*server-state*))
  )

