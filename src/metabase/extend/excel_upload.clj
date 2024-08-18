(ns metabase.extend.excel-upload
    "/api/extend endpoints."
    (:require
      [cheshire.core :as json]
      [clojure.java.io :as io]
      [compojure.core :refer [DELETE GET POST PUT]]
      [medley.core :as m]
      [metabase.analyze :as analyze]
      [metabase.api.common :as api]
      [metabase.api.common.validation :as validation]
      [metabase.api.dataset :as api.dataset]
      [metabase.api.field :as api.field]
      [metabase.api.query-metadata :as api.query-metadata]
      [metabase.compatibility :as compatibility]
      [metabase.driver.util :as driver.u]
      [metabase.events :as events]
      [metabase.lib.convert :as lib.convert]
      [metabase.lib.core :as lib]
      [metabase.lib.metadata.jvm :as lib.metadata.jvm]
      [metabase.lib.types.isa :as lib.types.isa]
      [metabase.lib.util.match :as lib.util.match]
      [metabase.models :refer [Card CardBookmark Collection Database
                               PersistedInfo Table]]
      [metabase.models.card :as card]
      [metabase.models.card.metadata :as card.metadata]
      [metabase.models.collection :as collection]
      [metabase.models.collection.root :as collection.root]
      [metabase.models.interface :as mi]
      [metabase.models.params :as params]
      [metabase.models.params.custom-values :as custom-values]
      [metabase.models.persisted-info :as persisted-info]
      [metabase.models.query :as query]
      [metabase.models.query.permissions :as query-perms]
      [metabase.models.revision.last-edit :as last-edit]
      [metabase.models.timeline :as timeline]
      [metabase.public-settings :as public-settings]
      [metabase.public-settings.premium-features :as premium-features]
      [metabase.query-processor.card :as qp.card]
      [metabase.query-processor.pivot :as qp.pivot]
      [metabase.server.middleware.offset-paging :as mw.offset-paging]
      [metabase.task.persist-refresh :as task.persist-refresh]
      [metabase.upload :as upload]
      [metabase.util :as u]
      [metabase.util.date-2 :as u.date]
      [metabase.util.i18n :refer [deferred-tru trs tru]]
      [metabase.util.log :as log]
      [metabase.util.malli :as mu]
      [metabase.util.malli.registry :as mr]
      [metabase.util.malli.schema :as ms]
      [steffan-westcott.clj-otel.api.trace.span :as span]
      [toucan2.core :as t2]
      [metabase.extend.upload-tmp :as upload-tmp]
      )
      (:import (com.github.must11.excel2csv ExcelDeal)
        (java.io File)
        )
    )

(set! *warn-on-reflection* true)





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

(defn- suffix-to-csv [filename]
      (clojure.string/replace  filename #"\.[^.]+$" ".csv"))

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
