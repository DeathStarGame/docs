(ns deathstar.scenario.render
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [cljs.reader :refer [read-string]]
   [clojure.pprint :refer [pprint]]
   [reagent.core :as r]
   [reagent.dom :as rdom]

   [cljctools.csp.op.spec :as op.spec]
   [cljctools.cljc.core :as cljc.core]

   [deathstar.scenario.spec :as scenario.spec]
   [deathstar.scenario.chan :as scenario.chan]

   ["antd/lib/layout" :default AntLayout]
   ["antd/lib/menu" :default AntMenu]
   ["antd/lib/icon" :default AntIcon]
   ["antd/lib/button" :default AntButton]
   ["antd/lib/list" :default AntList]
   ["antd/lib/row" :default AntRow]
   ["antd/lib/col" :default AntCol]
   ["antd/lib/form" :default AntForm]
   ["antd/lib/input" :default AntInput]
   ["antd/lib/tabs" :default AntTabs]
   ["antd/lib/table" :default AntTable]
   ["react" :as React]
   ["antd/lib/checkbox" :default AntCheckbox]


   ["antd/lib/divider" :default AntDivider]
   ["@ant-design/icons/SmileOutlined" :default AntIconSmileOutlined]
   ["@ant-design/icons/LoadingOutlined" :default AntIconLoadingOutlined]
   ["@ant-design/icons/SyncOutlined" :default AntIconSyncOutlined]


   [lab.render.konva]
   
   ["react-konva" :rename {Stage KonvaStage
                           Layer KonvaLayer
                           Rect KonvaRect
                           Path KonvaPath
                           Circle KonvaCircle
                           Group KonvaGroup}]))


(def ant-row (r/adapt-react-class AntRow))
(def ant-col (r/adapt-react-class AntCol))
(def ant-divider (r/adapt-react-class AntDivider))
(def ant-layout (r/adapt-react-class AntLayout))
(def ant-layout-content (r/adapt-react-class (.-Content AntLayout)))
(def ant-layout-header (r/adapt-react-class (.-Header AntLayout)))

(def ant-menu (r/adapt-react-class AntMenu))
(def ant-menu-item (r/adapt-react-class (.-Item AntMenu)))
(def ant-icon (r/adapt-react-class AntIcon))
(def ant-button (r/adapt-react-class AntButton))
(def ant-button-group (r/adapt-react-class (.-Group AntButton)))
(def ant-list (r/adapt-react-class AntList))
(def ant-input (r/adapt-react-class AntInput))
(def ant-input-password (r/adapt-react-class (.-Password AntInput)))
(def ant-checkbox (r/adapt-react-class AntCheckbox))
(def ant-form (r/adapt-react-class AntForm))
(def ant-table (r/adapt-react-class AntTable))
(def ant-form-item (r/adapt-react-class (.-Item AntForm)))
(def ant-tabs (r/adapt-react-class AntTabs))
(def ant-tab-pane (r/adapt-react-class (.-TabPane AntTabs)))

(def ant-icon-smile-outlined (r/adapt-react-class AntIconSmileOutlined))
(def ant-icon-loading-outlined (r/adapt-react-class AntIconLoadingOutlined))
(def ant-icon-sync-outlined (r/adapt-react-class AntIconSyncOutlined))

; https://github.com/sergeiudris/starnet/blob/af86204ff94776ceab140208f5a6e0d654d30eba/ui/src/starnet/ui/alpha/main.cljs
; https://github.com/sergeiudris/starnet/blob/af86204ff94776ceab140208f5a6e0d654d30eba/ui/src/starnet/ui/alpha/render.cljs


(def konva-stage (r/adapt-react-class KonvaStage))
(def konva-layer (r/adapt-react-class KonvaLayer))
(def konva-rect (r/adapt-react-class KonvaRect))
(def konva-circle (r/adapt-react-class KonvaCircle))
(def konva-group (r/adapt-react-class KonvaGroup))
(def konva-path (r/adapt-react-class KonvaPath))


(defn create-state
  [data]
  (r/atom data))

(declare  rc-main rc-grid rc-entity)

(defn render-ui
  [channels state {:keys [id] :or {id "ui"}}]
  (rdom/render [rc-main channels state]  (.getElementById js/document id)))

(defn rc-main
  [channels state]
  (r/with-let []
    [:<>
     #_[:pre {} (with-out-str (pprint @state))]
     #_[ant-button {:icon (r/as-element [ant-icon-sync-outlined])
                    :size "small"
                    :title "button"
                    :on-click (fn [] ::button-click)}]
     [rc-grid channels state]
     [rc-entity channels state]
     #_[lab.render.konva/rc-konva-grid channels state]
     #_[lab.render.konva/rc-konva-example-circle channels state]]))

(def colors
  {::scenario.spec/sands "#D2B48Cff"
   ::scenario.spec/location "brown"
   ::scenario.spec/recharge "#30ad23"
   ::scenario.spec/rover "blue"})


(defn rc-grid
  [channels state]
  (r/with-let [entities* (r/cursor state [::scenario.spec/entities])
              ;;  width js/window.innerWidth
              ;;  height js/window.innerHeight
               box-size 13
               on-mouse-over (fn [evt]
                               (let [box (.-target evt)]
                                 #_(println (.id box))
                                 #_(println (get @entities* (.id box)))
                                 (swap! state assoc ::scenario.spec/hovered-entity (get @entities* (.id box)))
                                 #_(println (js-keys box))
                                 #_(println (.id box))
                                 #_(.fill box "#E5FF80")
                                 (.strokeWidth box 2)
                                 (.draw box)))
               on-mouse-out (fn [evt]
                              (let [box (.-target evt)
                                    entity (get @entities* (.id box))]
                                (.strokeWidth box 0.001)
                                #_(.fill box (::scenario.spec/color entity))
                                (.draw box)))]
    [konva-stage
     {:width (* box-size 63)
      :height (* box-size 31)}
     [konva-layer
      {:on-mouseover on-mouse-over
       :on-mouseout on-mouse-out}
      (map (fn [entity]
             (let [{:keys [::scenario.spec/entity-type
                           ::scenario.spec/x
                           ::scenario.spec/y
                           ::scenario.spec/uuid
                           ::scenario.spec/color]} entity]
               (condp = entity-type
                 ::scenario.spec/location
                 [konva-group
                  {:key (str x "-" y)
                   :x (* x box-size)
                   :y (* y box-size)}
                  [konva-rect {:id uuid
                               :width (- box-size 1)
                               :height (- box-size 1)
                               :fill (::scenario.spec/sands colors)
                               :strokeWidth 0.001
                               :stroke "white"}]
                  [konva-rect {:id uuid
                               :width (- box-size 4)
                               :height (- box-size 4)
                               :fill (::scenario.spec/location colors)
                               :strokeWidth 0.001
                               :stroke "white"}]]
                 ;default
                 [konva-rect {:key (str x "-" y)
                              :x (* x box-size)
                              :y (* y box-size)
                              :id uuid
                              :width (- box-size 1)
                              :height (- box-size 1)
                              :fill (get colors entity-type)
                              :strokeWidth 0.001
                              :stroke "white"}]))) (vals @entities*))]]))


(defn rc-entity
  [channels state]
  (r/with-let [hovered-entity* (r/cursor state [::scenario.spec/hovered-entity])]
    [:div {:style {:position "absolute" :top 0 :right 0 :background-color "#ffffff99"}}
     [:pre
      (with-out-str (pprint @hovered-entity*))]]))