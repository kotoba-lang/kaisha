# kaisha 会社

Portable CLJC model for the GFTD workspace **communication space** (Slack/Teams
相当): one space per company, channels, threaded messages, mentions, reactions,
and read markers — as one EDN-native graph.

Sibling of the other workspace surfaces:

- `kotoba-lang/slides` — decks, docs, drive, sheets
- `kotoba-lang/calendar` — events, attendees
- `kotoba-lang/mail` / `contacts` — correspondence, people

Like those, `kaisha` stays pure data and pure functions. Hosts render it as web
apps, persist it in Datomic/kotoba (append-only datoms; CRDT via
`kotoba-lang/crdt` for live co-editing), or expose it over kotoba-server XRPC.
Identity is intended to be kotoba CACAO `did:key` (the `:kaisha/did` slot on
members); nothing in the model requires it.

> Not to be confused with `gftdcojp/ai-gftd-kaisya` (different org, different
> spelling, different product).

## Model

```clojure
(require '[kaisha.model :as k])

(def sp
  (-> (k/space "gftd")
      (k/add-member (k/member "jun"))
      (k/add-member (k/member "rin"))
      (k/add-channel (k/channel "general"))
      (k/join "general" "jun")
      (k/post "general" (k/message "m-1" {:kaisha/author "jun"
                                          :kaisha/body "morning @rin"
                                          :kaisha/at "2026-07-07T09:00:00Z"}))
      (k/reply "general" "m-1" (k/message "m-2" {:kaisha/author "rin"
                                                 :kaisha/body "on it"
                                                 :kaisha/at "2026-07-07T09:01:00Z"}))))

(k/messages-in-order sp "general")   ;; top-level messages, oldest first
(k/thread sp "general" "m-1")        ;; parent + replies
(k/mentions "morning @rin")          ;; => #{"rin"}
(k/visible-channels sp "rin")        ;; public + private-where-member
(k/unread sp "rin" "general")        ;; after the member's read marker
```

Semantics follow Slack: threads are one level deep (a reply's parent must be a
top-level message), private channels are visible to their members only, read
markers are per member per channel.

`kaisha.validate/problems` returns `{:kaisha/severity :kaisha/code ...}` maps
for structural defects (unknown author, orphan reply, nested thread, private
channel without members); `valid?` is false on any `:error`.

## Test

```bash
clojure -M:test
```

## Scope and follow-ups

v1 is the pure EDN model only. Deliberately out of scope, tracked as follow-ups
in ADR-2607072310:

- transport (kotoba-server XRPC lexicons, realtime fan-out over KSE)
- persistence (datom projection, CRDT message bodies)
- AI participation — LLM-drafted posts must go through a governed actor
  (post-LLM ⊣ Governor, human-approved send) in the `tayori`/`teian` pattern,
  never straight into the model.
