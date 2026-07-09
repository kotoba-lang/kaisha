(ns kaisha.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaisha.model :as k]
            [kaisha.validate :as v]))

(defn fixture-space []
  (-> (k/space "gftd")
      (k/add-member (k/member "jun" {:kaisha/display-name "Jun Kawasaki"}))
      (k/add-member (k/member "rin"))
      (k/add-channel (k/channel "general"))
      (k/add-channel (k/channel "ops" {:kaisha/visibility :private}))
      (k/join "general" "jun")
      (k/join "general" "rin")
      (k/join "ops" "jun")
      (k/post "general" (k/message "m-1" {:kaisha/author "jun"
                                          :kaisha/body "morning @rin"
                                          :kaisha/at "2026-07-07T09:00:00Z"}))
      (k/post "general" (k/message "m-2" {:kaisha/author "rin"
                                          :kaisha/body "morning"
                                          :kaisha/at "2026-07-07T09:01:00Z"}))
      (k/reply "general" "m-1" (k/message "m-3" {:kaisha/author "rin"
                                                 :kaisha/body "on it"
                                                 :kaisha/at "2026-07-07T09:02:00Z"}))))

(deftest channel-and-thread-model
  (let [sp (fixture-space)]
    (testing "top-level order excludes thread replies"
      (is (= ["m-1" "m-2"] (map :kaisha/id (k/messages-in-order sp "general")))))
    (testing "thread returns parent then replies"
      (is (= ["m-1" "m-3"] (map :kaisha/id (k/thread sp "general" "m-1")))))
    (testing "space validates"
      (is (v/valid? sp)))))

(deftest mentions-and-visibility
  (let [sp (fixture-space)]
    (is (= #{"rin"} (k/mentions "morning @rin")))
    (is (k/mentioned? sp (k/message-by-id sp "general" "m-1") "rin"))
    (is (not (k/mentioned? sp (k/message-by-id sp "general" "m-2") "rin")))
    (testing "private channel hidden from non-members"
      (is (= ["general" "ops"] (map :kaisha/id (k/visible-channels sp "jun"))))
      (is (= ["general"] (map :kaisha/id (k/visible-channels sp "rin")))))))

(deftest reactions-and-read-markers
  (let [sp (-> (fixture-space)
               (k/react "general" "m-1" "+1" "rin")
               (k/react "general" "m-1" "+1" "jun")
               (k/mark-read "rin" "general" "m-1"))]
    (is (= #{"rin" "jun"}
           (get-in (k/message-by-id sp "general" "m-1") [:kaisha/reactions "+1"])))
    (testing "unread = top-level messages after the marker"
      (is (= ["m-2"] (map :kaisha/id (k/unread sp "rin" "general"))))
      (is (= ["m-1" "m-2"] (map :kaisha/id (k/unread sp "jun" "general")))))
    (testing "a marker pointing at a thread reply (m-3, a reply to m-1, not
              top-level) must fail open to everything unread -- NOT silently
              report nothing. mark-read never validates msg-id is top-level,
              and messages-in-order filters replies out, so drop-while would
              otherwise consume the whole top-level sequence looking for a
              match that can never appear, leaving `rest` on an empty seq"
      (let [sp2 (k/mark-read sp "jun" "general" "m-3")]
        (is (= ["m-1" "m-2"] (map :kaisha/id (k/unread sp2 "jun" "general"))))))
    (testing "a marker pointing at a nonexistent id also fails open"
      (let [sp2 (k/mark-read sp "jun" "general" "does-not-exist")]
        (is (= ["m-1" "m-2"] (map :kaisha/id (k/unread sp2 "jun" "general"))))))))

(deftest validation-catches-defects
  (let [codes (fn [sp] (set (map :kaisha/code (v/problems sp))))]
    (testing "unknown author"
      (is (contains? (codes (-> (k/space "s")
                                (k/add-channel (k/channel "c"))
                                (k/post "c" (k/message "m" {:kaisha/author "ghost"
                                                            :kaisha/at "2026-07-07T00:00:00Z"}))))
                     :message/unknown-author)))
    (testing "orphan reply"
      (is (contains? (codes (-> (k/space "s")
                                (k/add-member (k/member "jun"))
                                (k/add-channel (k/channel "c"))
                                (k/reply "c" "missing" (k/message "m" {:kaisha/author "jun"
                                                                       :kaisha/at "2026-07-07T00:00:00Z"}))))
                     :message/orphan-reply)))
    (testing "nested thread"
      (is (contains? (codes (-> (fixture-space)
                                (k/reply "general" "m-3" (k/message "m-4" {:kaisha/author "jun"
                                                                           :kaisha/at "2026-07-07T09:03:00Z"}))))
                     :message/nested-thread)))
    (testing "private channel without members"
      (is (contains? (codes (-> (k/space "s")
                                (k/add-channel (k/channel "c" {:kaisha/visibility :private}))))
                     :channel/private-without-members)))))

(deftest seed-space-is-valid
  (is (v/valid? (k/seed-space))))
