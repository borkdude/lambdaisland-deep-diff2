(ns lambdaisland.deep-diff.printer
  (:require [fipp.engine :as fipp]
            [fipp.visit :as fv]
            [puget.color :as color]
            [puget.dispatch]
            [puget.printer :as puget]
            [arrangement.core]
            [lambdaisland.deep-diff.diff :as diff]
            #?@(:cljs
                [[cljs-time.coerce :refer [from-date]]
                 [cljs-time.format :refer [formatter unparse]]
                 [goog.string :refer [format]]
                 [goog.object :as gobj]]))
  #?(:clj (:import (java.text SimpleDateFormat)
                   (java.util TimeZone)
                   (java.sql Timestamp))))

(defn get-type-name
  "Get the type of the given object as a string. For Clojure, gets the name of
  the class of the object. For ClojureScript, gets either the `name` attribute
  or the protocol name if the `name` attribute doesn't exist."
  [x]
  #?(:clj (.getName (class x))
     :cljs (let [t (type x)
                 n (.-name t)]
             (if (empty? n)
               (pr-str t)
               n))))

(defn print-deletion [printer expr]
  (let [no-color (assoc printer :print-color false)]
    (color/document printer ::deletion [:span "-" (puget/format-doc no-color (:- expr))])))

(defn print-insertion [printer expr]
  (let [no-color (assoc printer :print-color false)]
    (color/document printer ::insertion [:span "+" (puget/format-doc no-color (:+ expr))])))

(defn print-mismatch [printer expr]
  [:group
   [:span ""] ;; needed here to make this :nest properly in kaocha.report/print-expr '=
   [:align
    (print-deletion printer expr) :line
    (print-insertion printer expr)]])

(defn print-other [printer expr]
  (let [no-color (assoc printer :print-color false)]
    (color/document printer ::other [:span "-" (puget/format-doc no-color expr)])))

(defn- map-handler [this value]
  (let [ks (#'puget/order-collection (:sort-keys this) value (partial sort-by first arrangement.core/rank))
        entries (map (partial puget/format-doc this) ks)]
    [:group
     (color/document this :delimiter "{")
     [:align (interpose [:span (:map-delimiter this) :line] entries)]
     (color/document this :delimiter "}")]))

(defn- map-entry-handler [printer value]
  (let [k (key value)
        v (val value)]
    (let [no-color (assoc printer :print-color false)]
      (cond
        (instance? lambdaisland.deep_diff.diff.Insertion k)
        [:span
         (print-insertion printer k)
         (if (coll? v) (:map-coll-separator printer) " ")
         (color/document printer ::insertion (puget/format-doc no-color v))]

        (instance? lambdaisland.deep_diff.diff.Deletion k)
        [:span
         (print-deletion printer k)
         (if (coll? v) (:map-coll-separator printer) " ")
         (color/document printer ::deletion (puget/format-doc no-color v))]

        :else
        [:span
         (puget/format-doc printer k)
         (if (coll? v) (:map-coll-separator printer) " ")
         (puget/format-doc printer v)]))))


#?(:clj (def ^:private ^ThreadLocal thread-local-utc-date-format
          (proxy [ThreadLocal] []
            (initialValue []
              (doto (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS-00:00")
                (.setTimeZone (TimeZone/getTimeZone "GMT"))))))
   :cljs (def thread-local-utc-date-format
           (doto (cljs-time.format/formatter "yyyy-MM-dd'T'HH:mm:ss.SSS-00:00"))))

(def ^:private print-date
  (puget/tagged-handler
   'inst
   #?(:clj #(.format ^SimpleDateFormat (.get thread-local-utc-date-format) %)
      :cljs (fn [input-date]
                 (let [dt (from-date input-date)]
                   (cljs-time.format/unparse thread-local-utc-date-format dt))))))

#?(:clj (def ^:private ^ThreadLocal thread-local-utc-timestamp-format
  (proxy [ThreadLocal] []
    (initialValue []
      (doto (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss")
        (.setTimeZone (TimeZone/getTimeZone "GMT"))))))
   :cljs (def thread-local-utc-timestamp-format
           (doto (cljs-time.format/formatter "yyyy-MM-dd'T'HH:mm:ss"))))

(def ^:private print-timestamp
  (puget/tagged-handler
   'inst
   #?(:clj #(str (.format ^SimpleDateFormat (.get thread-local-utc-timestamp-format) %)
                 (format ".%09d-00:00" (.getNanos ^Timestamp %)))
      :cljs (fn [input-date]
              (let [dt (from-date input-date)]
                (cljs-time.format/unparse thread-local-utc-timestamp-format dt))))))

(def ^:private print-calendar
  (puget/tagged-handler
   'inst
   #(let [formatted (format "%1$tFT%1$tT.%1$tL%1$tz" %)
          offset-minutes (- (.length formatted) 2)]
      (str (subs formatted 0 offset-minutes)
           ":"
           (subs formatted offset-minutes)))))

(def ^:private common-handlers
  {'lambdaisland.deep_diff.diff.Deletion
   print-deletion

   'lambdaisland.deep_diff.diff.Insertion
   print-insertion

   'lambdaisland.deep_diff.diff.Mismatch
   print-mismatch})

#?(:clj
   (def ^:private print-handlers
     {'clojure.lang.PersistentArrayMap
      map-handler

      'clojure.lang.PersistentHashMap
      map-handler

      'clojure.lang.MapEntry
      map-entry-handler

      'java.util.Date
      print-date

      'java.util.GregorianCalendar
      print-calendar

      'java.sql.Timestamp
      print-timestamp})
   :cljs
   (def ^:private print-handlers
     {'cljs.core.PersistentArrayMap
      map-handler

      'cljs.core.PersistentHashMap
      map-handler

      'cljs.core.MapEntry
      map-entry-handler

      'js/Date
      print-date

      'cljs.core.uuid
      (puget/tagged-handler 'uuid str)}))


(defn- print-handler-resolver [extra-handlers]
  (fn [^Class klz]
    (and klz (get (merge @#'common-handlers @#'print-handlers extra-handlers)
                  (symbol (get-type-name klz))))))

;; (defn register-print-handler!
;;   "Register an extra print handler.

;;   `type` must be a symbol of the fully qualified class name. `handler` is a
;;   Puget handler function of two arguments, `printer` and `value`."
;;   [type handler]
;;   (alter-var-root #'print-handlers assoc type handler))

(defn puget-printer
  ([]
   (puget-printer {}))
  ([opts]
   (let [extra-handlers (:extra-handlers opts)]
     (puget/pretty-printer (merge {:width          (or *print-length* 100)
                                   :print-color    true
                                   :color-scheme   {::deletion  [:red]
                                                    ::insertion [:green]
                                                    ::other     [:yellow]
                                                    ;; puget uses green and red for
                                                    ;; boolean/tag, but we want to reserve
                                                    ;; those for diffed values.
                                                    :boolean    [:bold :cyan]
                                                    :tag        [:magenta]}
                                   :print-handlers  (print-handler-resolver extra-handlers)}
                                  (dissoc opts :extra-handlers))))))

(defn format-doc [expr printer]
  (puget/format-doc printer expr))

(defn print-doc [doc printer]
  (fipp.engine/pprint-document doc {:width (:width printer)}))