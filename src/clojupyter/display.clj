(ns clojupyter.display
  (:require [clojupyter.protocol.mime-convertible :as mc]
            [clojupyter.misc.mime-convertible :as mmc]
            [clojupyter.kernel.jup-channels :as jup]
            [clojupyter.util :as u]
            [clojupyter.state :as state]
            [clojupyter.messages :as msg]
            [clojure.data.codec.base64 :as b64]
            [hiccup.core :as hiccup]))

;;--------------------------------------------------------------------------------------------------
;;  DISPLAY MESSAGES
;;--------------------------------------------------------------------------------------------------

(defn display!
  ([data] (display! data {} ""))
  ([data metadata id]
   (let [content (msg/display-data-content data metadata {:display_id id})
         {:keys [jup req-message]} (state/current-context)]
     (jup/send!! jup :iopub_port req-message msg/DISPLAY-DATA content)
     nil)))

(defn update-display!
  ([id data] (update-display! id data {}))
  ([id data metadata]
   (let [content (msg/update-display-data data metadata {:display_id id})
         {:keys [jup req-message]} (state/current-context)]
     (jup/send!! jup :iopub_port req-message msg/UPDATE-DISPLAY-DATA content)
     nil)))

(defn clear-output!
  [wait?]
  (let [content (msg/clear-output-content wait?)
        {:keys [jup req-message]} (state/current-context)]
    (jup/send!! jup :iopub_port req-message msg/CLEAR-OUTPUT content)
    nil))


;; HTML

(defn hiccup
  [v]
  (mmc/render-mime :text/html (hiccup/html v)))

(defn html
  [v]
  (mmc/render-mime :text/html v))

;; Latex

(defn latex
  [v]
  (mmc/render-mime :text/latex v))

;; Markdown

(defn markdown
  [v]
  (mmc/render-mime :text/markdown v))


;; Vega Lite

(defn vega-lite-1
  [v]
  (mmc/render-mime :application/vnd.vegalite.v1+json v))

(defn vega-lite-3
  [v]
  (mmc/render-mime :application/vnd.vegalite.v3+json v))

(defn vega-lite
  [v]
  (vega-lite-3 v))

;; Vega

(defn vega-5
  [v]
  (mmc/render-mime :application/vnd.vega.v5+json v))

(defn vega
  [v]
  (vega-5 v))

;; Gif

(defn gif
  [b]
  (mmc/render-mime :image/gif (new String (b64/encode b))))

;; Pdf

(defn pdf
  [b]
  (mmc/render-mime :application/pdf (new String (b64/encode b))))

;; JSON

(defn json
  [v]
  (mmc/render-mime :application/json v))

;; VDOM

(defn vdom
  [v]
  (mmc/render-mime :application/vdom.v1+json v))
