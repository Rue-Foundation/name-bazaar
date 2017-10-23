(ns name-bazaar.server.effects
  (:require
    [cljs.core.async :refer [<! >! chan]]
    [cljs.nodejs :as nodejs]
    [district0x.server.effects :as d0x-effects]
    [district0x.server.state :as state]
    [name-bazaar.server.contracts-api.ens :as ens]
    [name-bazaar.server.contracts-api.used-by-factories :as used-by-factories])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def namehash (aget (nodejs/require "eth-ens-namehash") "hash"))

(def default-deploy-opts
  {:from-index 0
   :contracts-file-namespace 'name-bazaar.shared.smart-contracts
   :contracts-file-path "/src/name_bazaar/shared/smart_contracts.cljs"
   :gas-price 30000000000})

(defn library-placeholders [& [{:keys [:emergency-multisig]}]]
  {:auction-offering "beefbeefbeefbeefbeefbeefbeefbeefbeefbeef"
   :buy-now-offering "beefbeefbeefbeefbeefbeefbeefbeefbeefbeef"
   :ens "314159265dD8dbb310642f98f50C066173C1259b"
   :offering-registry "fEEDFEEDfeEDFEedFEEdFEEDFeEdfEEdFeEdFEEd"
   emergency-multisig "DeEDdeeDDEeDDEEdDEedDEEdDEeDdEeDDEEDDeed"})


(defn deploy-ens! [server-state-atom default-opts]
  (d0x-effects/deploy-smart-contract! server-state-atom (merge default-opts
                                                               {:contract-key :ens
                                                                :gas 700000})))

(defn deploy-registrar! [server-state-atom default-opts]
  (d0x-effects/deploy-smart-contract! server-state-atom
                                      (merge default-opts
                                             {:contract-key :registrar
                                              :gas 2700000
                                              :args [(state/contract-address @server-state-atom :ens)
                                                     (namehash "eth")]})))

(defn deploy-offering-registry! [server-state-atom default-opts {:keys [:emergency-multisig]}]
  (d0x-effects/deploy-smart-contract! server-state-atom (merge default-opts
                                                               {:gas 700000
                                                                :contract-key :offering-registry
                                                                :args [emergency-multisig]})))

(defn deploy-offering-requests! [server-state-atom default-opts]
  (d0x-effects/deploy-smart-contract! server-state-atom (merge default-opts
                                                               {:gas 1700000
                                                                :contract-key :offering-requests})))


(defn deploy-buy-now-offering! [server-state-atom default-opts {:keys [:emergency-multisig]}]
  (d0x-effects/deploy-smart-contract! server-state-atom
                                      (merge default-opts
                                             {:gas 1200000
                                              :contract-key :buy-now-offering
                                              :library-placeholders
                                              (dissoc (library-placeholders {:emergency-multisig emergency-multisig})
                                                      :buy-now-offering :auction-offering)})))

(defn deploy-auction-offering! [server-state-atom default-opts {:keys [:emergency-multisig]}]
  (d0x-effects/deploy-smart-contract! server-state-atom
                                      (merge default-opts
                                             {:gas 1700000
                                              :contract-key :auction-offering
                                              :library-placeholders
                                              (dissoc (library-placeholders {:emergency-multisig emergency-multisig})
                                                      :buy-now-offering :auction-offering)})))

(defn deploy-buy-now-factory! [server-state-atom default-opts]
  (d0x-effects/deploy-smart-contract! server-state-atom
                                      (merge default-opts
                                             {:gas 1700000
                                              :contract-key :buy-now-offering-factory
                                              :library-placeholders (select-keys (library-placeholders)
                                                                                 [:buy-now-offering])
                                              :args [(state/contract-address @server-state-atom :ens)
                                                     (state/contract-address @server-state-atom :offering-registry)
                                                     (state/contract-address @server-state-atom :offering-requests)]})))

(defn deploy-auction-factory! [server-state-atom default-opts]
  (d0x-effects/deploy-smart-contract! server-state-atom
                                      (merge default-opts
                                             {:gas 2000000
                                              :contract-key :auction-offering-factory
                                              :library-placeholders (select-keys (library-placeholders)
                                                                                 [:auction-offering])
                                              :args [(state/contract-address @server-state-atom :ens)
                                                     (state/contract-address @server-state-atom :offering-registry)
                                                     (state/contract-address @server-state-atom :offering-requests)]})))

(defn deploy-district0x-emails! [server-state-atom default-opts]
  (d0x-effects/deploy-smart-contract! server-state-atom (merge default-opts
                                                               {:gas 500000
                                                                :contract-key :district0x-emails})))

(defn deploy-smart-contracts! [server-state-atom & [{:keys [:persist? :emergency-multisig :skip-ens-registrar?] :as deploy-opts}]]
  (let [ch (chan)
        deploy-opts (merge default-deploy-opts deploy-opts)
        emergency-wallet (or emergency-multisig (state/active-address @server-state-atom))]
    (go
      (when-not skip-ens-registrar?
        (<! (deploy-ens! server-state-atom deploy-opts))
        (<! (deploy-registrar! server-state-atom deploy-opts))
        (<! (ens/set-subnode-owner! @server-state-atom
                                    {:ens.record/label "eth"
                                     :ens.record/node ""
                                     :ens.record/owner (state/contract-address @server-state-atom :registrar)})))

      (<! (deploy-offering-registry! server-state-atom deploy-opts {:emergency-multisig emergency-wallet}))
      (<! (deploy-offering-requests! server-state-atom deploy-opts))

      (<! (deploy-buy-now-offering! server-state-atom deploy-opts {:emergency-multisig emergency-wallet}))
      (<! (deploy-buy-now-factory! server-state-atom deploy-opts))

      (<! (deploy-auction-offering! server-state-atom deploy-opts {:emergency-multisig emergency-wallet}))
      (<! (deploy-auction-factory! server-state-atom deploy-opts))

      (<! (deploy-district0x-emails! server-state-atom deploy-opts))

      (<! (used-by-factories/set-factories! @server-state-atom {:contract-key :offering-registry}))
      (<! (used-by-factories/set-factories! @server-state-atom {:contract-key :offering-requests}))

      (when persist?
        (d0x-effects/store-smart-contracts! (:smart-contracts @server-state-atom)
                                            {:file-path (:contracts-file-path deploy-opts)
                                             :namespace (:contracts-file-namespace deploy-opts)}))

      (>! ch server-state-atom))
    ch))
