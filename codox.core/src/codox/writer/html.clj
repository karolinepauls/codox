(ns codox.writer.html
  "Documentation writer that outputs HTML."
  (:use [hiccup core page element])
  (:import [java.net URLEncoder]
           [org.pegdown PegDownProcessor])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [me.raynes.cegdown :as md]
            [codox.utils :as util]))

(def ^:private url-regex
  #"((https?|ftp|file)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]+[-A-Za-z0-9+&@#/%=~_|])")

(defn- add-anchors [text]
  (if text
    (str/replace text url-regex "<a href=\"$1\">$1</a>")))

(defmulti format-doc
  "Format the docstring of a var or namespace into HTML."
  (fn [project metadata] (or (:doc/format metadata) (:format project)))
  :default :plaintext)

(defmethod format-doc :plaintext [_ metadata]
  [:pre (add-anchors (h (:doc metadata)))])

(defmethod format-doc :markdown [_ metadata]
  (if-let [doc (:doc metadata)]
    (.markdownToHtml (PegDownProcessor.) doc)))

(defn- ns-filename [namespace]
  (str (:name namespace) ".html"))

(defn- ns-filepath [output-dir namespace]
  (str output-dir "/" (ns-filename namespace)))

(defn- var-id [var]
  (str "var-" (-> var :name str URLEncoder/encode (str/replace "%" "."))))

(defn- var-uri [namespace var]
  (str (ns-filename namespace) "#" (var-id var)))

(defn- get-mapping-fn [mappings path]
  (some (fn [[re f]] (if (re-find re path) f)) mappings))

(defn- var-source-uri
  [{:keys [src-dir-uri src-uri-mapping src-linenum-anchor-prefix]}
   {:keys [path file line]}]
  (str src-dir-uri
       (if-let [mapping-fn (get-mapping-fn src-uri-mapping (str path))]
         (mapping-fn file)
         path)
       (if src-linenum-anchor-prefix
         (str "#" src-linenum-anchor-prefix line))))

(defn- split-ns [namespace]
  (str/split (str namespace) #"\."))

(defn- namespace-parts [namespace]
  (->> (split-ns namespace)
       (reductions #(str %1 "." %2))
       (map symbol)))

(defn- add-depths [namespaces]
  (->> namespaces
       (map (juxt identity (comp count split-ns)))
       (reductions (fn [[_ ds] [ns d]] [ns (cons d ds)]) [nil nil])
       (rest)))

(defn- add-heights [namespaces]
  (for [[ns ds] namespaces]
    (let [d (first ds)
          h (count (take-while #(not (or (= d %) (= (dec d) %))) (rest ds)))]
      [ns d h])))

(defn- add-branches [namespaces]
  (->> (partition-all 2 1 namespaces)
       (map (fn [[[ns d0 h] [_ d1 _]]] [ns d0 h (= d0 d1)]))))

(defn- namespace-hierarchy [namespaces]
  (->> (map :name namespaces)
       (sort)
       (mapcat namespace-parts)
       (distinct)
       (add-depths)
       (add-heights)
       (add-branches)))

(defn- index-by [f m]
  (into {} (map (juxt f identity) m)))

;; The values in ns-tree-part are chosen for aesthetic reasons, based
;; on a text size of 15px and a line height of 31px.

(defn- ns-tree-part [height]
  (if (zero? height)
    [:span.tree [:span.top] [:span.bottom]]
    (let [row-height 31
          top        (- 0 21 (* height row-height))
          height     (+ 0 30 (* height row-height))]
      [:span.tree {:style (str "top: " top "px;")}
       [:span.top {:style (str "height: " height "px;")}]
       [:span.bottom]])))

(defn- namespaces-menu [project & [current]]
  (let [namespaces (:namespaces project)
        ns-map     (index-by :name namespaces)]
    [:div#namespaces.sidebar
     [:h3 (link-to "index.html" [:span.inner "Namespaces"])]
     [:ul
      (for [[name depth height branch?] (namespace-hierarchy namespaces)]
        (let [class  (str "depth-" depth (if branch? " branch"))
              short  (last (split-ns name))
              inner  [:div.inner (ns-tree-part height) [:span (h short)]]]
          (if-let [ns (ns-map name)]
            (let [class (str class (if (= ns current) " current"))]
              [:li {:class class} (link-to (ns-filename ns) inner)])
            [:li {:class class} [:div.no-link inner]])))]]))

(defn- sorted-public-vars [namespace]
  (sort-by (comp str/lower-case :name) (:publics namespace)))

(defn- vars-menu [namespace]
  [:div#vars.sidebar
   [:h3 (link-to "#top" [:span.inner "Public Vars"])]
   [:ul
    (for [var (sorted-public-vars namespace)]
      (list*
       [:li.depth-1
        (link-to (var-uri namespace var) [:div.inner [:span (h (:name var))]])]
       (for [mem (:members var)]
         (let [branch? (not= mem (last (:members var)))
               class   (if branch? "depth-2 branch" "depth-2")
               inner   [:div.inner (ns-tree-part 0) [:span (h (:name mem))]]]
           [:li {:class class}
            (link-to (var-uri namespace mem) inner)]))))]])

(def ^{:private true} default-includes
  (list
   [:meta {:charset "UTF-8"}]
   (include-css "css/default.css")
   (include-js "js/jquery.min.js")
   (include-js "js/page_effects.js")))

(defn- project-title [project]
  (str (str/capitalize (:name project)) " " (:version project)))

(defn- header [project]
  [:div#header
   [:h2 "Generated by " (link-to "https://github.com/weavejester/codox" "Codox")]
   [:h1 (link-to "index.html" (h (project-title project)) " API documentation")]])

(defn- index-page [project]
  (html5
   [:head
    default-includes
    [:title (h (project-title project)) " API documentation"]]
   [:body
    (header project)
    (namespaces-menu project)
    [:div#content.namespace-index
     [:h2 (h (project-title project))]
     [:div.doc (h (:description project))]
     (for [namespace (sort-by :name (:namespaces project))]
       [:div.namespace
        [:h3 (link-to (ns-filename namespace) (h (:name namespace)))]
        [:div.doc (format-doc project (update-in namespace [:doc] util/summary))]
        [:div.index
         [:p "Public variables and functions:"]
         (unordered-list
          (for [var (sorted-public-vars namespace)]
            (link-to (var-uri namespace var) (h (:name var)))))]])]]))

(defn- var-usage [var]
  (for [arglist (:arglists var)]
    (list* (:name var) arglist)))

(defn- var-docs [project var]
  [:div.public.anchor {:id (h (var-id var))}
   [:h3 (h (:name var))]
   (if-not (= (:type var) :var)
     [:h4.type (name (:type var))])
   (if-let [added (:added var)]
     [:h4.added "added in " added])
   (if-let [deprecated (:deprecated var)]
     [:h4.deprecated "deprecated" (if (string? deprecated) (str " in " deprecated))])
   [:div.usage
    (for [form (var-usage var)]
      [:code (h (pr-str form))])]
   [:div.doc (format-doc project var)]
   (if-let [members (seq (:members var))]
     [:div.members
      [:h4 "members"]
      [:div.inner
       (let [project (dissoc project :src-dir-uri)]
         (map (partial var-docs project) members))]])
   (if (:src-dir-uri project)
     [:div.src-link (link-to (var-source-uri project var) "view source")])])

(defn- namespace-page [project namespace]
  (html5
   [:head
    default-includes
    [:title (h (:name namespace)) " documentation"]]
   [:body
    (header project)
    (namespaces-menu project namespace)
    (vars-menu namespace)
    [:div#content.namespace-docs
     [:h2#top.anchor (h (:name namespace))]
     [:div.doc (format-doc project namespace)]
     (for [var (sorted-public-vars namespace)]
       (var-docs project var))]]))

(defn- copy-resource [output-dir src dest]
  (io/copy (io/input-stream (io/resource src))
           (io/file output-dir dest)))

(defn- mkdirs [output-dir & dirs]
  (doseq [dir dirs]
    (.mkdirs (io/file output-dir dir))))

(defn- write-index [output-dir project]
  (spit (io/file output-dir "index.html") (index-page project)))

(defn- write-namespaces
  [output-dir project]
  (doseq [namespace (:namespaces project)]
    (spit (ns-filepath output-dir namespace)
          (namespace-page project namespace))))

(defn write-docs
  "Take raw documentation info and turn it into formatted HTML."
  [project]
  (doto (:output-dir project "doc")
    (mkdirs "css" "js")
    (copy-resource "codox/css/default.css" "css/default.css")
    (copy-resource "codox/js/jquery.min.js" "js/jquery.min.js")
    (copy-resource "codox/js/page_effects.js" "js/page_effects.js")
    (write-index project)
    (write-namespaces project))
  nil)
