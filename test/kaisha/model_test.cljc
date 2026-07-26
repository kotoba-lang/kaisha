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
                     :channel/private-without-members)))
    (testing "message author must be a member of a private channel, not just the space"
      (let [base (-> (k/space "s")
                     (k/add-member (k/member "alice"))
                     (k/add-member (k/member "bob"))
                     (k/add-channel (k/channel "secret" {:kaisha/visibility :private
                                                         :kaisha/members #{"alice"}})))
            non-member-post (k/post base "secret" (k/message "m" {:kaisha/author "bob"
                                                                   :kaisha/at "2026-07-07T00:00:00Z"}))
            member-post     (k/post base "secret" (k/message "m" {:kaisha/author "alice"
                                                                   :kaisha/at "2026-07-07T00:00:00Z"}))]
        (is (contains? (codes non-member-post) :message/author-not-channel-member))
        (is (not (contains? (codes member-post) :message/author-not-channel-member))
            "a private channel's own member posting is not flagged")))))

(deftest reactions-can-be-withdrawn
  (let [sp (-> (fixture-space)
               (k/react "general" "m-1" "+1" "rin")
               (k/react "general" "m-1" "+1" "jun"))
        one-left (k/unreact sp "general" "m-1" "+1" "rin")
        none-left (k/unreact one-left "general" "m-1" "+1" "jun")]
    (is (= #{"jun"} (get-in (k/message-by-id one-left "general" "m-1")
                            [:kaisha/reactions "+1"])))
    (testing "the emoji key is dropped once the last reactor leaves -- an
              empty reactor set still renders as a zero-count badge"
      (is (= {} (:kaisha/reactions (k/message-by-id none-left "general" "m-1")))))
    (testing "withdrawing a reaction that was never given is a no-op"
      (is (= sp (k/unreact sp "general" "m-1" "🎉" "rin"))))))

(deftest declared-channel-and-message-fields-are-reachable
  (let [sp (fixture-space)]
    (testing "topic"
      (is (= "deploys" (:kaisha/topic (k/channel-by-id (k/set-topic sp "general" "deploys")
                                                       "general")))))
    (testing "edit stamps :kaisha/edited-at and replaces the body"
      (let [edited (k/edit sp "general" "m-1" "morning @rin (fixed)" "2026-07-07T09:05:00Z")
            msg (k/message-by-id edited "general" "m-1")]
        (is (= "morning @rin (fixed)" (:kaisha/body msg)))
        (is (= "2026-07-07T09:05:00Z" (:kaisha/edited-at msg)))))
    (testing "archive is reversible and never hides messages"
      (let [archived (k/archive sp "general")]
        (is (:kaisha/archived? (k/channel-by-id archived "general")))
        (is (= ["m-1" "m-2"] (map :kaisha/id (k/messages-in-order archived "general")))
            "an archived channel stays readable")
        (is (= ["ops"] (map :kaisha/id (k/active-channels archived "jun"))))
        (is (= ["general" "ops"] (map :kaisha/id (k/visible-channels archived "jun")))
            "archived is a default-view narrowing, not an access rule")
        (is (not (:kaisha/archived? (k/channel-by-id (k/unarchive archived "general")
                                                     "general"))))))
    (testing "every setter is a no-op on an unknown id rather than creating a phantom"
      (is (= sp (k/set-topic sp "nope" "x")))
      (is (= sp (k/archive sp "nope")))
      (is (= sp (k/unarchive sp "nope")))
      (is (= sp (k/edit sp "general" "no-such-message" "x" "2026-07-07T00:00:00Z"))))))

(deftest seed-space-is-valid
  (is (v/valid? (k/seed-space))))
