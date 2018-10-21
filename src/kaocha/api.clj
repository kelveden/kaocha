(ns kaocha.api
  "Programmable test runner interface."
  (:require [clojure.test :as t]
            [kaocha.monkey-patch]
            [kaocha.testable :as testable]
            [kaocha.result :as result]
            [kaocha.plugin :as plugin]
            [kaocha.report :as report]
            [kaocha.history :as history]
            [kaocha.config :as config]
            [kaocha.output :as output]
            [kaocha.stacktrace :as stacktrace]))

(require '[kaocha.matcher-combinators])

(defmacro ^:private with-reporter [r & body]
  `(with-redefs [t/report ~r]
     ~@body))

(defmacro ^:private with-shutdown-hook [f & body]
  `(let [runtime#     (java.lang.Runtime/getRuntime)
         on-shutdown# (Thread. ~f)]
     (.addShutdownHook runtime# on-shutdown#)
     (try
       ~@body
       (finally
         (.removeShutdownHook runtime# on-shutdown#)))))

(defn test-plan [config]
  (let [tests (:kaocha/tests (plugin/run-hook :kaocha.hooks/pre-load config))]
    (plugin/run-hook
     :kaocha.hooks/post-load
     (-> config
         (dissoc :kaocha/tests)
         (assoc :kaocha.test-plan/tests (testable/load-testables tests))))))

(defn- resolve-reporter [config]
  (let [fail-fast? (:kaocha/fail-fast? config)
        reporter   (:kaocha/reporter config)
        reporter   (-> reporter
                       (cond-> (not (vector? reporter)) vector)
                       (conj 'kaocha.report/report-counters
                             'kaocha.history/track
                             'kaocha.report/dispatch-extra-keys)
                       (cond-> fail-fast? (conj 'kaocha.report/fail-fast))
                       config/resolve-reporter)]
    (assoc config :kaocha/reporter (fn [m]
                                     (try
                                       (reporter m)
                                       (catch clojure.lang.ExceptionInfo e
                                         (if (:kaocha/fail-fast (ex-data e))
                                           (throw e)
                                           (do
                                             (output/error "Error in reporter: " (ex-data e) " when processing " (:type m))
                                             (stacktrace/print-cause-trace e))))
                                       (catch Throwable t
                                         (output/error "Error in reporter: " (.getClass t) " when processing " (:type m))
                                         (stacktrace/print-cause-trace t)))))))

(defn run [config]
  (let [plugins      (:kaocha/plugins config)
        plugin-chain (plugin/load-all plugins)]
    (plugin/with-plugins plugin-chain
      (let [config     (->> config
                            (plugin/run-hook :kaocha.hooks/config)
                            resolve-reporter)
            fail-fast? (:kaocha/fail-fast? config)
            color?     (:kaocha/color? config)
            history    (atom [])]
        (binding [testable/*fail-fast?*   fail-fast?
                  history/*history*       history
                  output/*colored-output* color?]
          (let [test-plan (test-plan config)]
            (with-reporter (:kaocha/reporter test-plan)
              (with-shutdown-hook (fn []
                                    (println "^C")
                                    (binding [history/*history* history]
                                      (t/do-report (history/clojure-test-summary))))
                (let [test-plan       (plugin/run-hook :kaocha.hooks/pre-run test-plan)]
                  (binding [testable/*test-plan* test-plan]
                    (let [test-plan-tests (:kaocha.test-plan/tests test-plan)
                          result-tests    (testable/run-testables test-plan-tests test-plan)
                          result          (plugin/run-hook :kaocha.hooks/post-run
                                                           (-> test-plan
                                                               (dissoc :kaocha.test-plan/tests)
                                                               (assoc :kaocha.result/tests result-tests)))]
                      (assert (= (count test-plan-tests) (count (:kaocha.result/tests result))))
                      (-> result
                          result/testable-totals
                          result/totals->clojure-test-summary
                          t/do-report)
                      result)))))))))))
