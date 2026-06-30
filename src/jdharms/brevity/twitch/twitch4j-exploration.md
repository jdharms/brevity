# Helix + EventSub Guide for Clojure Bot

## What the Library Provides

### Helix REST (`rest-helix` module)

The `TwitchHelix` interface exposes **140+ Helix endpoint methods** — effectively the entire
Twitch API surface:

| Category | Examples |
|---|---|
| Users & Channels | `getUsers`, `getChannelInformation`, `updateChannelInformation` |
| Streams | `getStreams`, `getStreamKey` |
| Chat | `sendChatMessage`, `getChatters`, `getEmotes`, `sendChatAnnouncement`, `sendShoutout` |
| Moderation | `banUser`, `unbanUser`, `getModerators`, `addBlockedTerm`, `checkAutoModStatus` |
| Custom Rewards | `createCustomReward`, `getCustomRewardRedemption`, `updateRedemptionStatus` |
| Predictions & Polls | `createPrediction`, `getPredictions`, `createPoll`, `getPolls` |
| Hype Trains | `getHypeTrainEvents` |
| Clips & Videos | `createClip`, `getClips`, `getVideos` |
| Raids | `startRaid`, `cancelRaid` |
| VIPs & Followers | `addChannelVip`, `getChannelFollowers` |
| Subscriptions | `getSubscriptions`, `checkUserSubscription` |
| EventSub CRUD | `getEventSubSubscriptions`, `createEventSubSubscription`, `deleteEventSubSubscription` |
| Conduits | `getConduits`, `createConduit`, `getConduitShards` |

**Rate limiting is automatic** via `bucket4j` — global (800 req/min) and per-endpoint limits
(e.g., announcements: 1/2s per channel, raids: 10/10min). No manual throttling needed.

**Responses** are Jackson-deserialized into typed domain objects (`StreamList`, `UserList`, etc.)
each with a `data` list and a `pagination.cursor` for cursor-based pagination.

All calls return `HystrixCommand<T>` — call `.execute()` to block, `.queue()` for a Java `Future`.

### EventSub WebSocket (`eventsub-websocket` + `eventsub-common` modules)

**80+ subscription types** as typed constants in `SubscriptionTypes`:

| Category | Examples |
|---|---|
| Stream lifecycle | `STREAM_ONLINE`, `STREAM_OFFLINE` |
| Channel | `CHANNEL_UPDATE`, `CHANNEL_FOLLOW_V2`, `CHANNEL_BAN`, `CHANNEL_RAID` |
| Chat | `CHANNEL_CHAT_MESSAGE`, `CHANNEL_CHAT_NOTIFICATION`, `CHANNEL_CHAT_CLEAR` |
| Channel Points | `CHANNEL_POINTS_CUSTOM_REWARD_REDEMPTION_ADD`, `AUTOMATIC_REWARD_REDEMPTION_ADD_V2` |
| Subscriptions | `CHANNEL_SUBSCRIBE`, `CHANNEL_SUBSCRIPTION_GIFT`, `CHANNEL_CHEER` |
| Moderation | `CHANNEL_MODERATE`, `CHANNEL_MODERATOR_ADD`, `CHANNEL_WARNING_SEND` |
| Polls & Predictions | `CHANNEL_POLL_BEGIN/END`, `CHANNEL_PREDICTION_BEGIN/END` |
| Hype Train | `CHANNEL_HYPE_TRAIN_BEGIN_V2`, `CHANNEL_HYPE_TRAIN_END_V2` |
| Goals & Charity | `CHANNEL_GOAL_BEGIN/END`, `CHANNEL_CHARITY_CAMPAIGN_DONATE` |
| Misc | `CHANNEL_SHOUTOUT_CREATE`, `CHANNEL_SHARED_CHAT_BEGIN`, `USER_UPDATE` |

**Connection management is fully automatic:**
- Handles `SESSION_RECONNECT` messages from Twitch (30s grace period, session ID preserved)
- Silent keepalive processing (~10s interval from Twitch)
- Duplicate message deduplication via `EventSubVerifier`
- Subscription retry on reconnect (4xx client errors not retried, 5xx are)
- Connection state exposed via `TwitchEventSocket.getState()`:
  `DISCONNECTED → CONNECTING → CONNECTED → RECONNECTING`

**The key convenience:** calling `socket.register(SubscriptionTypes.STREAM_ONLINE, ...)` will
automatically create the Helix subscription using the current WebSocket session ID — you do not
need to manually call `helix.createEventSubSubscription(...)` and track session IDs yourself.

Events are dispatched via `events4j` — the same `eventManager.onEvent(Class, handler)` pattern
already used in `core.clj` for IRC.

---

## What You Implement Yourself

### Clojure Interop Glue

- **HystrixCommand unwrapping** — a helper fn that calls `.execute()` and pulls `.getData()`:
  ```clojure
  (defn execute! [cmd] (.execute cmd))
  (defn data! [cmd] (.getData (execute! cmd)))
  ```
- **Builder calls** — `doto` works well (already your pattern in `core.clj`)
- **Thin Clojure wrappers** around the Helix endpoints you actually use

### OAuth Scope Management

The library does not warn about missing scopes at subscription registration time — Twitch returns
a 403 that surfaces as an `EventSocketSubscriptionFailureEvent`. You need to track which scopes
your current token has and request the right ones during the auth flow. Each subscription type's
required scope is documented in the Twitch API docs and in the `SubscriptionType` implementations
in `eventsub-common/.../subscriptions/`.

### Application Logic

Event routing, command dispatch, per-channel state, cooldowns, multi-channel management, etc.
The library hands you typed events; what you do with them is your code.

---

## Migration Path from Current Code

Your existing credential management (`build-credential`, `startup-credential!`, the refresh loop)
is solid — keep it.

1. Add Helix to `TwitchClientBuilder` via `.withEnableHelix(true)`, or build a standalone
   `TwitchHelix` client directly for more control
2. Build a `TwitchEventSocket` using the same `OAuth2Credential` you already produce
3. Replace the `ChannelMessageEvent` IRC listener with a `ChannelChatMessageEvent` EventSub
   listener
4. Wrap Helix calls you need with thin Clojure fns
5. Drop the IRC chat client once EventSub chat is working

---

## Appendix A — Helix Quickstart

### 1. Build the client

```clojure
(import '[com.github.twitch4j.helix TwitchHelixBuilder])

(defn build-helix [client-id client-secret credential]
  (-> (TwitchHelixBuilder/builder)
      (.withClientId client-id)
      (.withClientSecret client-secret)
      (.withDefaultAuthToken credential)
      (.build)))
```

`credential` is the `OAuth2Credential` you already produce from `build-credential`.

### 2. Execute a call

All methods return `HystrixCommand<T>`. Call `.execute()` to block synchronously:

```clojure
(defn get-stream [helix broadcaster-id]
  (-> (.getStreams helix nil nil (java.util.List/of broadcaster-id) nil nil nil nil)
      (.execute)
      (.getStreams)   ; list of Stream objects
      first))
```

A thin generic helper avoids repeating `.execute` everywhere:

```clojure
(defn helix! [cmd]
  (-> cmd .execute .getData))  ; getData returns the List<T> for paginated endpoints
```

### 3. Paginate

Each list response has a `pagination` field with a `cursor`:

```clojure
(defn get-all-streams [helix broadcaster-id]
  (loop [cursor nil acc []]
    (let [result  (.execute (.getStreams helix nil cursor (java.util.List/of broadcaster-id) nil nil nil nil))
          streams (.getStreams result)
          next    (some-> result .getPagination .getCursor)]
      (if (and next (seq streams))
        (recur next (into acc streams))
        (into acc streams)))))
```

### 4. Send a chat message via Helix

Using the newer Helix chat endpoint (instead of IRC):

```clojure
(defn send-message [helix broadcaster-id sender-id token message]
  (-> (.sendChatMessage helix token broadcaster-id sender-id message nil)
      .execute))
```

`broadcaster-id` is the channel owner's user ID; `sender-id` is your bot's user ID.
The token must belong to the sender and have the `user:write:chat` scope.

### 5. Look up a user ID

You'll need numeric user IDs frequently:

```clojure
(defn get-user-id [helix login]
  (-> (.getUsers helix nil nil (java.util.List/of login))
      .execute
      .getUsers
      first
      .getId))
```

---

## Appendix B — EventSub WebSocket Quickstart

### 1. Build the socket

```clojure
(import '[com.github.twitch4j.eventsub.socket TwitchEventSocket])

(defn build-event-socket [client-id client-secret credential]
  (-> (TwitchEventSocket/builder)
      (.clientId client-id)
      (.clientSecret client-secret)
      (.defaultToken credential)
      (.build)))
```

The socket connects automatically on the first `register` call.

### 2. Register a subscription

```clojure
(import '[com.github.twitch4j.eventsub.subscriptions SubscriptionTypes])

(defn subscribe-stream-online [socket broadcaster-id]
  (.register socket
             SubscriptionTypes/STREAM_ONLINE
             (reify java.util.function.UnaryOperator
               (apply [_ b]
                 (-> b (.broadcasterUserId broadcaster-id) .build)))))
```

`register` returns an `EventSubSubscription` — the library calls `helix.createEventSubSubscription`
internally using the active WebSocket session ID.

### 3. Listen for events

```clojure
(import '[com.github.twitch4j.eventsub.events StreamOnlineEvent
                                               ChannelChatMessageEvent])

(defn register-handlers [socket]
  (let [em (.getEventManager socket)]
    (.onEvent em StreamOnlineEvent
              (reify java.util.function.Consumer
                (accept [_ event]
                  (println "Stream online:" (.getBroadcasterUserName event)))))
    (.onEvent em ChannelChatMessageEvent
              (reify java.util.function.Consumer
                (accept [_ event]
                  (println (str "[" (.getBroadcasterUserName event) "] "
                                (.getChatterUserName event) ": "
                                (.getText (.getMessage event)))))))))
```

### 4. Subscribe to chat messages

Chat message subscriptions require the `user:read:chat` scope on the token and the
bot/sender's user ID as the `userId` condition:

```clojure
(defn subscribe-chat [socket broadcaster-id bot-user-id]
  (.register socket
             SubscriptionTypes/CHANNEL_CHAT_MESSAGE
             (reify java.util.function.UnaryOperator
               (apply [_ b]
                 (-> b
                     (.broadcasterUserId broadcaster-id)
                     (.userId bot-user-id)
                     .build)))))
```

### 5. Monitor connection state

```clojure
(import '[com.github.twitch4j.eventsub.socket.events EventSocketConnectionStateEvent
                                                      EventSocketSubscriptionFailureEvent])

(defn register-lifecycle-handlers [socket]
  (let [em (.getEventManager socket)]
    (.onEvent em EventSocketConnectionStateEvent
              (reify java.util.function.Consumer
                (accept [_ event]
                  (println "EventSub state:" (.getState event)))))
    (.onEvent em EventSocketSubscriptionFailureEvent
              (reify java.util.function.Consumer
                (accept [_ event]
                  (println "Subscription failed:" (.getSubscriptionType event)
                           "—" (.getError event)))))))
```

A `SUBSCRIPTION_FAILURE` for chat events almost always means a missing OAuth scope.

### 6. Tear down

```clojure
(.close socket)  ; closes WebSocket, cancels subscriptions
```

### Required scopes summary

| Subscription | Required scope |
|---|---|
| `STREAM_ONLINE` / `STREAM_OFFLINE` | none (app token works) |
| `CHANNEL_CHAT_MESSAGE` | `user:read:chat` |
| `CHANNEL_FOLLOW_V2` | `moderator:read:followers` |
| `CHANNEL_BAN` / `CHANNEL_MODERATE` | `channel:read:moderation` or `moderator:read:*` |
| `CHANNEL_POINTS_CUSTOM_REWARD_REDEMPTION_ADD` | `channel:read:redemptions` |
| `CHANNEL_SUBSCRIBE` / `CHANNEL_CHEER` | `channel:read:subscriptions` / `bits:read` |

Always verify against the [Twitch EventSub subscription types reference](https://dev.twitch.tv/docs/eventsub/eventsub-subscription-types/)
as scope requirements can change.
