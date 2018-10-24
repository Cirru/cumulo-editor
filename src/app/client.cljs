
(ns app.client
  (:require [respo.core :refer [render! clear-cache! realize-ssr! *changes-logger]]
            [respo.cursor :refer [mutate]]
            [app.comp.container :refer [comp-container]]
            [cljs.reader :refer [read-string]]
            [app.connection :refer [send! setup-socket!]]
            [app.schema :as schema]
            [app.client-util :refer [ws-host parse-query!]]
            [app.util.dom :refer [focus!]]
            [app.util.shortcuts :refer [on-window-keydown]]
            [app.client-updater :as updater]))

(defonce *connecting? (atom false))

(defonce *states (atom {}))

(defonce *store (atom nil))

(defn dispatch! [op op-data]
  (when (not= op :states) (.info js/console "Dispatch" (str op) (clj->js op-data)))
  (case op
    :states (reset! *states ((mutate op-data) @*states))
    :states/clear (reset! *states {})
    :manual-state/abstract (reset! *states (updater/abstract @*states))
    :manual-state/draft-box (reset! *states (updater/draft-box @*states))
    :effect/save-files
      (do (reset! *states (updater/clear-editor @*states)) (send! op op-data))
    :ir/reset-files (do (reset! *states (updater/clear-editor @*states)) (send! op op-data))
    (send! op op-data)))

(defn detect-watching! []
  (let [query (parse-query!)]
    (when (some? (:watching query))
      (dispatch! :router/change {:name :watching, :data (:watching query)}))))

(defn simulate-login! []
  (let [raw (.getItem js/window.localStorage (:local-storage-key schema/configs))]
    (if (some? raw)
      (do (dispatch! :user/log-in (read-string raw)))
      (do (println "Found no storage.")))))

(defn connect! []
  (.info js/console "Connecting...")
  (reset! *connecting? true)
  (setup-socket!
   *store
   {:url ws-host,
    :on-close! (fn [event]
      (reset! *store nil)
      (reset! *connecting? false)
      (.error js/console "Lost connection!")
      (dispatch! :states/clear nil)),
    :on-open! (fn [event] (simulate-login!) (detect-watching!))}))

(def mount-target (.querySelector js/document ".app"))

(defn render-app! [renderer]
  (renderer mount-target (comp-container @*states @*store) #(dispatch! %1 %2)))

(defn retry-connect! [] (if (and (nil? @*store) (not @*connecting?)) (connect!)))

(def ssr? (some? (.querySelector js/document "meta.respo-ssr")))

(defn main! []
  (if ssr? (render-app! realize-ssr!))
  (comment
   reset!
   *changes-logger
   (fn [global-element element changes] (println "Changes:" changes)))
  (render-app! render!)
  (connect!)
  (add-watch
   *store
   :changes
   (fn [] (render-app! render!) (if (= :editor (get-in @*store [:router :name])) (focus!))))
  (add-watch *states :changes (fn [] (render-app! render!)))
  (.addEventListener
   js/window
   "keydown"
   (fn [event] (on-window-keydown event dispatch! (:router @*store))))
  (.addEventListener js/window "focus" (fn [event] (retry-connect!)))
  (.addEventListener
   js/window
   "visibilitychange"
   (fn [event] (when (= "visible" (.-visibilityState js/document)) (retry-connect!))))
  (println "App started!"))

(defn reload! [] (clear-cache!) (render-app! render!) (println "Code updated."))

(set! js/window.onload main!)
