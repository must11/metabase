(ns metabase.extend.upload-tmp
    (:require
      [clojure.core.memoize :as memoize]
      [clojure.java.io :as io]
      [clojure.string :as str]
      [environ.core :as env]
      [metabase.config :as config]
      [metabase.util.files :as u.files]
      [metabase.util.i18n :refer [trs]]
      [metabase.util.log :as log])
    (:import
      (java.io File)
      (java.nio.file Files Path)))

(set! *warn-on-reflection* true)

(defn- upload-tmp-dir-filename ^String []
       (or (env/env :mb-upload-tmp-dir)
           (.getAbsolutePath (io/file "upload_tmp"))))

(def ^:private upload-tmp-dir*
  ;; Memoized so we don't log the error messages multiple times if the plugins directory doesn't change
  (memoize/memo
    (fn [filename]
        (try
          ;; attempt to create <current-dir>/upload_tmp if it doesn't already exist. Check that the directory is readable.
          (let [path (u.files/get-path filename)]
               (u.files/create-dir-if-not-exists! path)
               (assert (Files/isWritable path)
                       (trs "Metabase does not have permissions to write to upload_tmp directory {0}" filename))
               {:path  path, :temp false})
          ;; If we couldn't create the directory, or the directory is not writable, fall back to a temporary directory
          ;; rather than failing to launch entirely. Log instructions for what should be done to fix the problem.
          (catch Throwable e
            (log/warn
              e
              (format "Metabase cannot use the upload_tmp directory %s" filename)
              "\n"
              "Please make sure the directory exists and that Metabase has permission to write to it."
              "You can change the directory Metabase uses for modules by setting the environment variable MB_PLUGINS_DIR."
              "Falling back to a temporary directory for now.")
            ;; Check whether the fallback temporary directory is writable. If it's not, there's no way for us to
            ;; gracefully proceed here. Throw an Exception detailing the critical issues.
            (let [path (u.files/get-path (System/getProperty "java.io.tmpdir"))]
                 (assert (Files/isWritable path)
                         (trs "Metabase cannot write to temporary directory. Please set MB_UPLOAD_TMP_DIR to a writable directory and restart Metabase."))
                 {:path path, :temp true}))))))

(defn upload-tmp-dir-info
      "Map with a :path key containing the `Path` to the Metabase plugins directory, and a :temp key indicating whether a
      temporary directory was used."
      ^Path []
      (upload-tmp-dir* (upload-tmp-dir-filename)))

(defn upload-tmp-dir
      "Get a `Path` to the Metabase plugins directory, creating it if needed. If it cannot be created for one reason or
      another, or if we do not have write permissions for it, use a temporary directory instead.

      This is a wrapper around `plugins-dir-info` which also contains a :temp key indicating whether a temporary directory
      was used."
      []
      (:path (upload-tmp-dir-info)))
