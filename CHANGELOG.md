# Version 0.2.2

* Fixes a bug in user management dialog.

* Removes unnecessary JavaScript includes.


# Version 0.2.1

* Adds an *app access*. By appending `?app=true` to a tracking URL, CTest will only return "negative" or "in progress" instead of the complete HTML.

* The backup daemon configuration allows specification of the backup schedule and changed from `:backup-path` to the following:
  ```clojure
  :backup {:path "backup"
           :start-minute 30
           :interval 60}
  ```
 
* The import daemon configuration allows the specification of the `:column-separator`:
  ```clojure
  :import {...
           :column-separator ";"
           ...}
  ```
 
 * Reports can now be filtered by report `type` and `context` by querying `/reports/list?type=error&context=CSV`.
   