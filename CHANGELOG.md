# Version 0.3.0

* Upgrade to Clojure 1.10.1.

* Upgrade of all dependencies.

* Tested with Java 8, Java 11 and Java 14.

* Removes configuration parameter `:forwarded?`.

* Switched `import` task to batched insert.

* Switched CSV import to batched update.

* Default configuration sets `:ssl? false`.

* Fixes shutdown logic in import loop.

* New `upgrade` task to migrate from `0.1.3` and `0.2.x` to `0.3.x`. 

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
   