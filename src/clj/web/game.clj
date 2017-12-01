(ns web.game
  (:require [web.ws :as ws]
            [web.lobby :refer [all-games old-states] :as lobby ]
            [web.utils :refer [response]]
            [game.main :as main]
            [game.core :as core]
            [cheshire.core :as json]
            [crypto.password.bcrypt :as bcrypt]))


(defn send-state-diffs!
  "Sends diffs generated by game.main/public-diffs to all connected clients."
  [{:keys [gameid players spectators] :as game}
   {:keys [type runner-diff corp-diff spect-diff] :as diffs}]
  (doseq [{:keys [id side] :as pl} players]
    (ws/send! id [:netrunner/diff
                  (json/generate-string (if (= side "Corp")
                                                 corp-diff
                                                 runner-diff))]))
  (doseq [{:keys [id] :as pl} spectators]
    (ws/send! id [:netrunner/diff
                  (json/generate-string  spect-diff)])))

(defn send-state!
  "Sends full states generated by game.main/public-states to all connected clients."
  ([game states]
   (send-state! :netrunner/state game states))

  ([event
    {:keys [gameid players spectators] :as game}
    {:keys [type runner-state corp-state spect-state] :as states}]
   (doseq [{:keys [id side] :as pl} players]
     (ws/send! id [event (json/generate-string (if (= side "Corp")
                                                 corp-state
                                                 runner-state))]))
   (doseq [{:keys [id] :as pl} spectators]
     (ws/send! id [event (json/generate-string spect-state)]))))

(defn swap-and-send-state! [{:keys [gameid state] :as game} old-state]
  "Updates the old-states atom with the new game state, then sends a :netrunner/state
  message to game clients."
  (swap! old-states assoc gameid @state)
  (send-state! game (main/public-states state)))

(defn swap-and-send-diffs! [{:keys [gameid state] :as game} old-state]
  "Updates the old-states atom with the new game state, then sends a :netrunner/diff
  message to game clients."
  (swap! old-states assoc gameid @state)
  (send-state-diffs! game (main/public-diffs old-state state)))

(defn handle-game-start
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id}]
  (let [{:keys [players gameid] :as game} (lobby/game-for-client client-id)]
    (when (lobby/first-player? client-id gameid)
      (let [strip-deck (fn [player] (update-in player [:deck] #(select-keys % [:_id])))
            game (as-> game g
                       (assoc g :started true
                                :original-players players
                                :ending-players players)
                       (assoc g :state (core/init-game g))
                       (update-in g [:players] #(mapv strip-deck %)))]
        (swap! all-games assoc gameid game)
        (swap! old-states assoc gameid @(:state game))
        (lobby/refresh-lobby :update gameid)
        (send-state! :netrunner/start game (main/public-states (:state game)))))))

(defn handle-game-action [{{{:keys [username] :as user} :user} :ring-req
                           client-id                           :client-id
                           {:keys [command args] :as msg}      :?data}]

  (let [{:keys [players state gameid] :as game} (lobby/game-for-client client-id)
        old-state (get @old-states gameid)
        side (.toLowerCase (some #(when (= client-id (:id %)) (:side %)) players))]
    (main/handle-action user command state side args)
    (swap-and-send-diffs! game old-state)))

(defn handle-game-watch
  "Handles a watch command when a game has started."
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id
    {:keys [gameid password options]}   :?data
    reply-fn                            :?reply-fn}]
  (if-let [{game-password :password state :state started :started :as game}
           (@all-games gameid)]
    (when (and user game (lobby/allowed-in-game game user))
      (if-not started
        false ; don't handle this message, let lobby/handle-game-watch.
        (if (or (empty? game-password)
                (bcrypt/check password game-password))
          (let [old-state @state
                {:keys [spect-state]} (main/public-states state)]
            ;; Add as a spectator, inform the client that this is the active game,
            ;; add a chat message, then send full states to all players.
            ; TODO: this would be better if a full state was only sent to the new spectator, and diffs sent to the existing players.
            (lobby/spectate-game user client-id gameid)
            (ws/send! client-id [:lobby/select {:gameid gameid
                                                :started started}])
            (main/handle-notification state (str username " joined the game as a spectator."))
            (swap-and-send-state! game old-state)
            (when reply-fn (reply-fn 200))
            true)
          (when reply-fn
            (reply-fn 403)
            false))))
    (when reply-fn
      (reply-fn 404)
      false)))


(ws/register-ws-handlers!
  :netrunner/start handle-game-start
  :netrunner/action handle-game-action
  :lobby/watch handle-game-watch)