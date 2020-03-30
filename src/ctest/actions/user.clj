; Copyright (c) Fabian Schneider and Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 2.0 (http://www.eclipse.org/legal/epl-v20.html)
; which can be found in the file epl-v2.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns ctest.actions.user
  (:require
    [clojure.edn :as edn]
    [cemerick.friend :as friend]
    [ctest.db.crud :as crud]))

      
(def user-attributes
  {:password "Password"
   :fullname "Full name"})

(defn- user-role
  [user]
  (some-> user :role keyword name))


(defn update-user
  "Updates a given user"
  [{:keys [username] :as user}]
  (let [editing-role (:role (friend/current-authentication)),
        user-role (edn/read-string (:role (crud/read-user username)))]
    (if (or (not= user-role :ctest.config/configadmin) (= editing-role :ctest.config/configadmin))
      (if (crud/put-user user)
        {:body user :status 200}
        {:body {:error (format "There is no user named \"%s\"." username)} :status 404})
      {:status 403, :body {:error "You are not allowed to modify that user."}})))


(defn create-user
  "Creates a new user"
  [{:keys [username] :as user}]
  (if (crud/has-user? username)
    {:body {:error (str "User " username " already exists.")} :status 403}
    (if (crud/put-user user)
      {:body user :status 201}
      {:status 500})))


(defn delete-user
  "Deletes a given user"
  {:description "User \"{{parameters.username}}\" deleted",
   :error "Failed to delete user \"{{parameters.username}}\"",
   :action-type :delete}
  [username]
  (cond
    (and (= (:role (crud/read-user username)) :ctest.config/configadmin) (not= (:role (friend/current-authentication)) :ctest.config/configadmin))
      {:status 403, :body {:error "You are not allowed to delete that user."}}
    (= (:username (friend/current-authentication)) username)
      {:status 403, :body {:error "You must not delete yourself."}}
    (= 1 (first (crud/delete-user username)))
      {:body {:username username} :status 204}
    :else
      {:status 404}))