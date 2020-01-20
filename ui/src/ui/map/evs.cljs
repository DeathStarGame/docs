(ns ui.map.evs
  (:require [re-frame.core :as rf]
            [day8.re-frame.tracing :refer-macros [fn-traced defn-traced]]
            [goog.dom]
            [ui.map.core]))


(rf/reg-event-fx
 ::toggle-key
 (fn-traced [{:keys [db]} [_ ea]]
            (let []
              {:db (update db :ui.db.map/selected-key (fn [k]
                                                          (if (= k ea)
                                                            nil
                                                            ea)
                                                          ))
               :dispatch [::toggle-selected-cell nil]
               })))
