(ns mb.licenses-test
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [mb.licenses :as lic])
  (:import (java.io StringReader StringWriter)
           java.util.jar.JarFile))

(def classpath-urls (str/split (System/getProperty "java.class.path")
                               (re-pattern lic/classpath-separator)))

(defn jar-filename-from-cp
  [jar-filename classpath-urls]
  (let [[x & rst :as found] (filter #(when (str/includes? % jar-filename) %) classpath-urls)]
    (cond (seq rst) (throw (ex-info (str "Multiple jars found for " jar-filename)
                                    {:filename jar-filename
                                     :found    found}))
          (not x)   (throw (ex-info (str "No results found for " jar-filename)
                                    {:filename jar-filename}))
          x         x)))

(defn parse [rdr] (xml/parse rdr :skip-whitespace true))

(def clojure-xml "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.clojure</groupId>
  <artifactId>clojure</artifactId>
  <name>clojure</name>
  <packaging>jar</packaging>
  <version>1.10.3</version>

  <licenses>
    <license>
      <name>Eclipse Public License 1.0</name>
      <url>http://opensource.org/licenses/eclipse-1.0.php</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
</project>"
  )

(def clojure-jdbc-xml "<?xml version=\"1.0\" encoding=\"utf-8\"?>
<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">
  <modelVersion>4.0.0</modelVersion>
  <artifactId>java.jdbc</artifactId>
  <version>0.7.11</version>
  <name>java.jdbc</name>

  <parent>
    <groupId>org.clojure</groupId>
    <artifactId>pom.contrib</artifactId>
    <version>0.2.2</version>
  </parent>
</project>"
  )

(deftest pom->coordinates-test
  (testing "Parses version information from pom"
    (testing "When group information is top level"
      (let [xml (parse (StringReader. clojure-xml))]
        (is (= {:group    "org.clojure"
                :artifact "clojure"
                :version  "1.10.3"}
               (lic/pom->coordinates xml)))))
    (testing "When group informatoin is under the parent tag"
     (let [xml (parse (StringReader. clojure-jdbc-xml))]
       (is (= {:group    "org.clojure"
               :artifact "java.jdbc"
               :version  "0.7.11"}
              (lic/pom->coordinates xml)))))))

(defn jar-file [filename]
  (if (.exists (io/file filename))
    (JarFile. filename)
    (throw (ex-data (str "Jar " filename " not found") {:filename filename}))))

(defn jar [filename]
  (jar-file (jar-filename-from-cp filename classpath-urls)))

(deftest pom->license-test
  (testing "Can find license information from the pom"
    (testing "If present"
      (let [xml (parse (StringReader. clojure-xml))]
        (is (= {:name         "Eclipse Public License 1.0"
                :url          "http://opensource.org/licenses/eclipse-1.0.php"
                :distribution "repo"}
               (lic/pom->licenses xml))))
      (let [xml (lic/jar->pom (jar-filename-from-cp "org/clojure/clojure" classpath-urls))]
        (is (= {:name         "Eclipse Public License 1.0"
                :url          "http://opensource.org/licenses/eclipse-1.0.php"
                :distribution "repo"}
               (lic/pom->licenses xml)))))
    (testing "Returning nil if not present"
      (let [xml (parse (StringReader. clojure-jdbc-xml))]
        (is (nil? (lic/pom->licenses xml)))))))

(deftest license-from-jar-test
  (letfn [(license [j] (some->> j
                                lic/license-from-jar
                                str/split-lines
                                (drop-while str/blank?)
                                first
                                str/trim))]
    (testing "Can find license information bundled in the jar"
      (is (= "META-INF/LICENSE.txt"
             (some-> (jar "commons/commons-math3")
                     (lic/get-entry "META-INF/LICENSE.txt")
                     (.getName))))
      (is (= "Apache License"
             (license (jar "commons/commons-math3")))))
    (testing "Nil if not present"
      (is (nil? (license (jar "hiccup/hiccup")))))))

(deftest license-from-backfill-test
  (let [backfill {"a" {"a" "License Information"}
                  "b" {"b" {:resource "MIT.txt"}}
                  :override/group
                  {"group-y" "License Information"
                   "group-z" {:resource "EPL.txt"}}}]
    (doseq [[coords expected-license]
            [[{:group "a" :artifact "a"} "License Information"]
             [{:group "b" :artifact "b"} "Permission is hereby granted"]
             [{:group "group-y" :artifact "literally-any-package"} "License Information"]
             [{:group "group-z" :artifact "literally-any-package"} "\nEclipse Public License"]]]
      (let [license (lic/license-from-backfill coords backfill)]
        (is (not (str/blank? license)))
        (is (str/starts-with? license expected-license))))))

(deftest process*-test
  (testing "categorizes jar entries"
    (let [jars            (map #(jar-filename-from-cp % classpath-urls)
                    ["org/clojure/clojure"
                     "commons/commons-math3"
                     "hiccup/hiccup"])
          normalize-entry (fn [[jar {:keys [coords license error]}]]
                            [((juxt :group :artifact) coords)
                             (cond-> {:license (not (str/blank? license))}
                               error (assoc :error error))])
          normalize       (fn [results]
                            (into {}
                            (map (fn [[k v]]
                                   [k (into {} (map normalize-entry) v)]))
                            results))]
      (is (= {:with-license
              {["org.clojure" "clojure"]              {:license true}
               ["org.apache.commons" "commons-math3"] {:license true}}
              :without-license
              {["hiccup" "hiccup"]
               {:license false :error "Error determining license or coords"}}}
             (normalize (lic/process* {:classpath-entries jars
                                       :backfill          {}}))))
      (is (= {:with-license
              {["org.clojure" "clojure"]              {:license true}
               ["org.apache.commons" "commons-math3"] {:license true}
               ["hiccup" "hiccup"]                    {:license true}}
              :without-license
              {}}
             (normalize (lic/process* {:classpath-entries jars
                                       :backfill {"hiccup" {"hiccup" "license"}}}))))
      (is (= {:with-license
              {["org.clojure" "clojure"]              {:license true}
               ["org.apache.commons" "commons-math3"] {:license true}
               ["hiccup" "hiccup"]                    {:license true}}
              :without-license
              {}}
             (normalize (lic/process* {:classpath-entries jars
                                       :backfill {:override/group {"hiccup" "license"}}})))))))

(deftest write-license-test
  (is (= (str "The following software may be included in this product:  a : a . "
              "This software contains the following license and notice below:\n\n\n"
              "license text\n\n\n----------\n\n")
         (let [os (StringWriter.)]
           (lic/write-license os ["jar" {:coords  {:group "a" :artifact "a"}
                                         :license "license text"}])
           (str os)))))

#_(run-tests)

(comment
  (def jar-filename "/Users/dan/.m2/repository/net/jcip/jcip-annotations/1.0/jcip-annotations-1.0.jar")
  (let [pom (lic/jar->pom jar-filename)]
    (lic/pom->coordinates pom))
  (jar-filename-from-cp "org/clojure/clojure" classpath-urls)
  (jar-filename-from-cp "commons/commons-math3" classpath-urls)
  (jar-filename-from-cp "hiccup/hiccup" classpath-urls))