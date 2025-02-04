;; Copyright (c) George Lipov. All rights reserved.
;; The use and distribution terms for this software are covered by the Eclipse
;; Public License 2.0 (https://choosealicense.com/licenses/epl-2.0/) which can
;; be found in the file LICENSE at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license. You must not remove this notice, or any other, from
;; this software.

(ns playback.core
  #?(:cljs (:require-macros playback.core))
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [com.fulcrologic.guardrails.core :refer [>defn ? | =>]]
   [playback.utils :refer #?(:clj  :all
                             :cljs [log-data format-portal-label])]
   #?@(:clj  [[debux.core :as debux :refer [dbg dbgn]]
              [portal.api :as portal]]
       :cljs [[debux.cs.core :as debux :refer-macros [dbg dbgn]]
              [portal.api :as portal]])))


;;; Vars and Init ;;;


(debux/set-source-info-mode! false)
(debux/set-tap-output! true)


(def ^:no-doc !fn-arg-cache (atom {}))
(def ^:no-doc ^:dynamic *!portal-value* nil)

(def ^:private ^:dynamic *env*)


#?(:clj
   (do
     (def ^:private optype->ops
       {::defn      #{'clojure.core/defn
                      'cljs.core/defn
                      'clojure.core/defn-
                      'cljs.core/defn-}
        ::>defn     #{'com.fulcrologic.guardrails.core/>defn
                      'com.fulcrologic.guardrails.core/>defn-}
        ::defmethod #{'clojure.core/defmethod
                      'cljs.core/defmethod}
        ::fn        #{'clojure.core/fn
                      'cljs.core/fn}
        ::fn-reg    #{'re-frame.core/reg-cofx
                      're-frame.core/reg-event-ctx
                      're-frame.core/reg-event-db
                      're-frame.core/reg-event-fx
                      're-frame.core/reg-fx
                      're-frame.core/reg-sub
                      're-frame.core/reg-sub-raw}
        ::loop      #{'clojure.core/loop
                      'cljs.core/loop}})

     (def ^:private op->optype (invert-optype->ops optype->ops))
     (def ^:private default-type-hierarchy (reduce (fn [hierarchy [op optype]]
                                                     (derive hierarchy op optype))
                                                   (make-hierarchy)
                                                   op->optype))

     (defonce ^:private !dispatch-type-hierarchy (atom nil))
     ;; If we just set the hierarchy in the def above, it won't get reset on
     ;; reload, so we initialise it like this
     (reset! !dispatch-type-hierarchy default-type-hierarchy)))


;;; Specs ;;;


(s/def ::form any?)
(s/def ::result any?)
(s/def ::indent-level nat-int?)
(s/def ::info any?)

(s/def ::debux-eval
  (s/and (s/keys :req-un [::form ::result ::indent-level]
                 :opt-un [::info])
         #(<= (count %) 4)))

(s/def ::optype
  #{::defn ::>defn ::defmethod ::fn ::fn-reg ::loop})


;;; Tracing Implementation ;;;


#?(:clj
   (defn- split-fn
     [fn-form]
     (split-with (every-pred (complement vector?) ; parameters
                             (complement list?))  ; multi-arity
                 fn-form)))


#?(:clj
   (defn- arg-cache-key
     [[op id & [?dispatch-val] :as _fn-form] optype]
     (case optype
       ::defn (get-qualified-sym id *env*)
       ::fn-reg [op id (get-ns-name *env*)]
       ::defmethod [(get-qualified-sym id *env*)
                    ?dispatch-val])))


#?(:clj
   (defn- traced-op
     [op optype]
     (case optype
       ::defmethod 'defn
       op)))


#?(:clj
   (defn- wrapping-op
     [op]
     (let [optype (op->optype (resolve-sym *env* op))]
       (case optype
         ::>defn 'defn
         op))))


#?(:clj
   (defn- stripped-fn-head-rest
     [fn-head-rest optype]
     (case optype
       ::defmethod (drop 1 fn-head-rest)
       fn-head-rest)))


#?(:clj
   (defn- generate-capture-defn
     [fn-form trace-level optype fn-arg-cache-key]
     (let [[fn-head fn-tail] (split-fn fn-form)
           [op traced-fn-name & fn-head-rest] fn-head
           fn-head-rest    (stripped-fn-head-rest fn-head-rest optype)
           renamed-fn-name (unique-playback-sym
                            *env*
                            (symbol (str traced-fn-name "__playback_core__")))
           renamed-orig-fn `(~(traced-op op optype)
                             ~(with-meta renamed-fn-name {::playback? true})
                             ~@fn-head-rest
                             ~@fn-tail)
           traced-fn       `(~(wrapping-op op) ~@(rest fn-head)
                             [& args#]
                             (swap! !fn-arg-cache assoc '~fn-arg-cache-key args#)
                             (-> (str "▷ ︎"
                                      ~(if (cljs-env? *env*)
                                         `(.toLocaleTimeString (js/Date.))
                                         `(.format
                                           (java.text.SimpleDateFormat. "HH:mm:ss")
                                           (java.util.Date.)))
                                      " "
                                      ~(string/join (repeat 32 "＿")))
                                 (symbol)
                                 (log-data))
                             (log-data (str ~(str "#"
                                                  (string/join (repeat trace-level ">"))
                                                  " ")
                                            '~fn-arg-cache-key)
                                       true)
                             (when ~(> trace-level 1)
                               (log-data :args true)
                               (log-data (vec args#)))
                             (let [result# (apply ~renamed-fn-name args#)]
                               (log-data :ret true)
                               (log-data result#)
                               result#))]
       `(do
          ;; needed for recursive and multi-arity functions, because the tracing
          ;; fn now has the original fn name they're trying to call
          (declare ~traced-fn-name)
          ~renamed-orig-fn
          ~traced-fn
          ~(when-not (string/ends-with? (str traced-fn-name) "!")
             `(when-let [args# (get @!fn-arg-cache '~fn-arg-cache-key)]
                (apply ~traced-fn-name args#)))))))


#?(:clj
   (defn- trace-form-dispatch [form _trace-level]
     (if-not (seq? form)
       :default
       (let [op (first form)]
         (if (symbol? op)
           (resolve-sym *env* op)
           :default)))))


#?(:clj
   (defmulti ^:private trace-form* trace-form-dispatch :hierarchy !dispatch-type-hierarchy))


#?(:clj
   (defmethod trace-form* ::defn
     [form trace-level]
     (let [fn-arg-cache-key (arg-cache-key form ::defn)]
       (generate-capture-defn form trace-level ::defn fn-arg-cache-key))))


#?(:clj
   (defmethod trace-form* ::>defn
     [form trace-level]
     (let [fn-arg-cache-key (arg-cache-key form ::defn)]
       (generate-capture-defn form trace-level ::>defn fn-arg-cache-key))))


#?(:clj
   (defmethod trace-form* ::defmethod
     [form trace-level]
     (let [fn-arg-cache-key (arg-cache-key form ::defmethod)]
       (generate-capture-defn form trace-level ::defmethod fn-arg-cache-key))))


#?(:clj
   (defn- generate-fn-trace-wrap
     [fn-form trace-level]
     (let [[fn-head _fn-tail] (split-fn fn-form)]
       `(~@fn-head
         [& args#]
         (log-data (str ~(str "#"
                              (string/join (repeat trace-level ">"))
                              " ")
                        '~fn-form)
                   true
                   2)
         (when ~(> trace-level 1)
           (log-data :args true 2)
           (log-data (vec args#)))
         (let [result# (apply ~fn-form args#)]
           (log-data :ret true 2)
           (log-data result#)
           result#)))))


#?(:clj
   (defmethod trace-form* ::fn
     [form trace-level]
     (generate-fn-trace-wrap form trace-level)))


#?(:clj
   (defn- wrap-fn-reg
     [form trace-level]
     (let [[op id :as reg-head] (butlast form)
           handler-fn (last form)]
       (if-not (and (seq? handler-fn)
                    (#{'fn 'fn*} (first handler-fn)))
         form                                     ; anon-fn is not defined inline
         (let [[_ handler-fn-tail] (split-fn handler-fn)
               fn-name          (unique-playback-sym
                                 *env*
                                 (symbol (str (name id) "__" op "__playback_core__")))
               fn-arg-cache-key (arg-cache-key form ::fn-reg)]
           `(do
              ~(generate-capture-defn `(~'defn
                                        ~(with-meta fn-name {::playback? true})
                                        ~@handler-fn-tail)
                                      trace-level
                                      ::defn
                                      fn-arg-cache-key)
              (~@reg-head ~fn-name)))))))


#?(:clj
   (defmethod trace-form* ::fn-reg
     [form trace-level]
     (wrap-fn-reg form trace-level)))


#?(:clj
   (defmethod trace-form* ::loop
     [form trace-level]
     (case trace-level
       1 `(dbg ~form :simple)
       2 `(dbgn ~form))))


#?(:clj
   (defmethod trace-form* :default
     [form trace-level]
     (case trace-level
       1 `(dbg ~form :simple)
       2 `(dbg ~form))))


;;; Data Reader Functions and Macros ;;;


(defmacro ^:no-doc trace>
  [form]
  (binding [*env* &env]
    (trace-form* form 1)))


(defmacro ^:no-doc trace>>
  [form]
  (binding [*env* &env]
    (trace-form* form 2)))


(defn ^:no-doc trace-o [form] `(trace> ~form))
(defn ^:no-doc trace-io [form] `(trace>> ~form))
(defn ^:no-doc get-portal-data [_] `@playback.core/*!portal-value*)


;;; Public ;;;


#?(:clj
   (>defn extend-default-optypes!
     "Resets the trace dispatch optype hierarchy to default and extends it with
     the supplied ops. Idempotent function. Pass `nil` to just reset."
     [optype->ops]
     [(? (s/map-of ::optype (s/coll-of qualified-symbol?))) => any?]
     (let [new-type-hierarchy (if-not optype->ops
                                default-type-hierarchy
                                (reduce (fn [hierarchy [op optype]]
                                          (derive hierarchy op optype))
                                        default-type-hierarchy
                                        (invert-optype->ops optype->ops)))]
       (reset! !dispatch-type-hierarchy new-type-hierarchy))))


(defn portal-tap
  [value]
  (if-not (s/valid? ::debux-eval value)
    (portal/submit value)
    (let [{:keys [info form indent-level result]} value]
      (when info
        (portal/submit (format-portal-label info indent-level)))
      (when form
        (portal/submit (format-portal-label form indent-level)))
      (when result
        (portal/submit result)))))


(>defn open-portal!
  ([]
   [=> any?]
   (open-portal! nil))
  ([portal-config]
   [(? (s/map-of keyword? any?)) => any?]
   (when (some? *!portal-value*)
     (println "Closing previous portal session...")
     #?(:clj  (portal/close *!portal-value*)
        :cljs (portal/close)))
   (let [!portal-instance (portal/open (merge {} portal-config))]
     #?(:clj  (alter-var-root #'*!portal-value* (constantly !portal-instance))
        :cljs (set! *!portal-value* !portal-instance))
     !portal-instance)))


;;; DEBUG ;;;


#_(defmacro ^:no-doc test-resolve-sym
    [sym]
    `(tap> (quote ~(resolve-sym &env sym))))

#_(defmacro ^:no-doc test-resolve-var
    [sym]
    `(tap> (quote ~(resolve-var &env sym))))

#_(defmacro ^:no-doc print-env
    []
    `(tap> (quote ~&env)))
