(ns metabase.extend.excel-upload
    "/api/extend endpoints."
    (:require
      [clojure.java.io :as io]
      [compojure.core :refer [DELETE GET POST PUT]]
      [medley.core :as m]
      [metabase.api.common :as api]

      [metabase.models :refer [Card CardBookmark Collection Database
                               PersistedInfo Table]]
      [metabase.models.card :as card]
      [metabase.models.collection :as collection]

      [metabase.models.params :as params]
      [clojure.string :as str]

      [metabase.public-settings :as public-settings]

      [metabase.upload :as upload]
      [metabase.util :as u]
      [metabase.util.i18n :refer [deferred-tru trs tru]]
      [metabase.util.log :as log]
      [metabase.extend.upload-tmp :as upload-tmp]
      )
      (:import (com.github.must11.excel2csv ExcelDeal)
        (java.io File)
        )
    )

(set! *warn-on-reflection* true)

(defn- file-extension [filename]
       (when filename
             (-> filename (str/split #"\.") rest last)))
(defn- suffix-to-csv [filename]
       (clojure.string/replace  filename #"\.[^.]+$" ".csv"))

(defn help-excel-to-csv
[filename ^File file]
  (if (= "xlsx" (file-extension filename))
    (let [save_file_name (str (upload-tmp/upload-tmp-dir) "/" (suffix-to-csv filename))]

         (ExcelDeal/toCSV file save_file_name)
         (io/delete-file file :silently )
         {:filename (suffix-to-csv filename),:file (File. save_file_name)}
    )
    (->{:filename filename,:file file})
      )
      )


(defn- from-csv!
       "This helper function exists to make testing the POST /api/extend/from-excel endpoint easier."
       [{:keys [collection-id filename file]}]
       (try
         (let [uploads-db-settings (public-settings/uploads-settings)
               model (upload/create-csv-upload! {:collection-id (parse-long collection-id)
                                                 :filename      filename
                                                 :file          file
                                                 :schema-name   (:schema_name uploads-db-settings)
                                                 :table-prefix  (:table_prefix uploads-db-settings)
                                                 :db-id         (or (:db_id uploads-db-settings)
                                                                    (throw (ex-info (tru "The uploads database is not configured.")
                                                                                    {:status-code 422})))})]
              {:status 200
               :body   (:id model)})
         (catch Throwable e
           {:status (or (-> e ex-data :status-code)
                        500)
            :body   {:message (or (ex-message e)
                                  (tru "There was an error uploading the file"))}})
         (finally (io/delete-file file :silently ))))


(defn- from-excel!
       [{:keys [collection-id filename file]}]
      (log/infof  filename)
       (let [save_file_name (str (upload-tmp/upload-tmp-dir) "/" (suffix-to-csv filename))]
            (log/infof "save_file_name %s" save_file_name)
            (ExcelDeal/toCSV file save_file_name)
            (io/delete-file file :silently )
            (from-csv! {:collection-id collection-id
                         :filename     filename
                        :file (File. save_file_name)})))


(api/defendpoint ^:multipart POST "/from-excel"
                 "Create a table and model populated with the values from the attached CSV. Returns the model ID if successful."
                 [:as {raw-params :params}]
                 ;(log/infof parse-long (get raw-params "collection_id"))
                 (from-excel! {:collection-id (get raw-params "collection_id")
                             :filename      (get-in raw-params ["file" :filename])
                             :file          (get-in raw-params ["file" :tempfile])}))
(api/defendpoint GET "/test"
                 "Create a table and model populated with the values from the attached CSV"
                 []
                {:message "yes"})
(api/define-routes)
