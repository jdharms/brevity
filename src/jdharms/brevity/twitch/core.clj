(ns jdharms.brevity.twitch.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ring.util.codec :as codec]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]]
            [clj-http.client :as http]
            [cheshire.core :as json])
  (:import [com.github.twitch4j TwitchClientBuilder]
           [com.github.twitch4j.auth.providers TwitchIdentityProvider]
           [com.github.twitch4j.chat.events.channel ChannelMessageEvent]
           [com.github.philippheuer.credentialmanager.domain OAuth2Credential]
           [java.util.concurrent Executors TimeUnit]))

(defn load-secrets []
  (edn/read-string (slurp "twitch-creds.secret.edn")))

(defn authorize-url [client-id scopes state]
  (str "https://id.twitch.tv/oauth2/authorize?"
       (codec/form-encode
        {"client_id" client-id
         "redirect_uri" "http://localhost:3000/callback"
         "response_type" "code"
         "scope" (str/join " " scopes)
         "state" state})))

(def received-code (atom nil))

(defn handler [request]
  (let [code (get-in request [:query-params "code"])
        state (get-in request [:query-params "state"])]
    (reset! received-code {:code code :state state})
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body "Got it. You can close the tab."}))

(defn start-server []
  (jetty/run-jetty (wrap-params handler)
                   {:port 3000 :join? false}))

(defn exchange-code [client-id client-secret code]
  (let [response (http/post "https://id.twitch.tv/oauth2/token"
                            {:form-params {"client_id" client-id
                                           "client_secret" client-secret
                                           "code" code
                                           "grant_type" "authorization_code"
                                           "redirect_uri" "http://localhost:3000/callback"}
                             :throw-exceptions false})
        body (json/parse-string (:body response) true)]
    (when (= 200 (:status response))
      {:access-token (:access_token body)
       :refresh-token (:refresh_token body)})))

(defn load-tokens []
  (when (.exists (io/file "tokens.edn"))
    (edn/read-string (slurp "tokens.edn"))))

(defn save-tokens [{:keys [access-token refresh-token]}]
  (spit "tokens.edn" (pr-str {:access-token access-token
                              :refresh-token refresh-token})))

(defn save-tokens-from-credential [credential]
  (save-tokens {:access-token (.getAccessToken credential)
                :refresh-token (.getRefreshToken credential)}))

(defn build-credential [tokens]
  (doto (OAuth2Credential.
         "twitch"
         (:access-token tokens))
    (.setRefreshToken (:refresh-token tokens))))

(defn- enrich [^TwitchIdentityProvider tip ^OAuth2Credential credential]
  (.orElse (.getAdditionalCredentialInformation tip credential) nil))

(defn- validate-or-refresh [^TwitchIdentityProvider tip ^OAuth2Credential initial]
  (or (enrich tip initial)
      (when-let [refreshed (.orElse (.refreshCredential tip initial) nil)]
        (enrich tip refreshed))))

(defn- startup-credential! [^TwitchIdentityProvider tip ^OAuth2Credential initial]
  (let [cred (validate-or-refresh tip initial)]
    (when-not cred
      (throw (ex-info "Invalid tokens -- re-run the one-time auth flow."
                      {:recoverable false})))
    (save-tokens-from-credential cred)
    cred))

(def refresh-executor (atom nil))

(defn- stop-refresh-loop! []
  (when-let [e @refresh-executor]
    (.shutdown e)
    (reset! refresh-executor nil)))

(defn- refresh-tick! [^TwitchIdentityProvider tip ^OAuth2Credential credential]
  (if-let [refreshed (.orElse (.refreshCredential tip credential) nil)]
    (if-let [enriched (enrich tip refreshed)]
      (do (.updateCredential credential enriched)
          (save-tokens-from-credential credential)
          (println "[refresh] ok, new expires-in: " (.getExpiresIn credential)))
      (do (.updateCredential credential refreshed)
          (save-tokens-from-credential credential)
          (println "[refresh] unable to validate refreshed token!")))
    (do (println "[refresh] unable to refresh token!")
        (stop-refresh-loop!))))

(defn- start-refresh-loop! [^TwitchIdentityProvider tip ^OAuth2Credential credential]
  (stop-refresh-loop!)
  (let [executor (Executors/newSingleThreadScheduledExecutor)
        test-delay 5
        initial-delay (max 60 (quot (.getExpiresIn credential) 2))]
    (.scheduleAtFixedRate executor
                          #(refresh-tick! tip credential)
                          test-delay
                          60
                          TimeUnit/SECONDS)
    (reset! refresh-executor executor)))

(defn build-client [client-id client-secret credential]
  (-> (TwitchClientBuilder/builder)
      (.withClientId client-id)
      (.withClientSecret client-secret)
      (.withEnableChat true)
      (.withChatAccount credential)
      (.build)))

(def commands
  {"ping" (fn [_ctx] "pong")
   "echo" (fn [ctx] (str/join " " (:args ctx)))
   "hello" (fn [ctx] (str "Hello, " (:user ctx) "!"))})

(defn parse-command [message]
  (when (str/starts-with? message "!")
    (let [[cmd & args] (str/split (subs message 1) #"\s+")]
      {:cmd cmd :args args})))

(defn handle-command [client channel user message]
  (when-let [{:keys [cmd args]} (parse-command message)]
    (when-let [handler (get commands cmd)]
      (when-let [response (handler {:user user :channel channel :args args})]
        (.sendMessage (.getChat client) channel response)))))

(defn on-message [client event]
  (let [channel (.getName (.getChannel event))
        user (.getName (.getUser (.getMessageEvent event)))
        message (.getMessage event)]
    (println (str "[" channel "] " user ": " message))
    (handle-command client channel user message)))

(def subscription (atom nil))

(defn register-message-handler [client]
  (when-let [s @subscription]
    (.dispose s))
  (reset! subscription
          (-> client
              (.getEventManager)
              (.onEvent ChannelMessageEvent
                        (reify java.util.function.Consumer
                          (accept [_ event]
                            (#'on-message client event)))))))

(comment
  ;; --- one-time auth ---
  (def secrets (load-secrets))
  (def server (start-server))
  (def scopes ["chat:read" "chat:edit"])
  (println (authorize-url (:client-id secrets)
                          scopes
                          "some-random-state"))

  ;; Open that URL in browser, authorize, callback fills received-code
  @received-code ;; sanity check
  (def token-response
    (exchange-code (:client-id secrets)
                   (:client-secret secrets)
                   (:code @received-code)))
  (save-tokens (:body token-response))
  (.stop server) ;; done with the callback server
  )

(comment
  ;; -- normal startup --
  (def secrets (load-secrets))
  (def credential (build-credential (load-tokens)))
  (def tip (TwitchIdentityProvider. (:client-id secrets)
                                    (:client-secret secrets)
                                    nil))
  (def valid (startup-credential! tip credential))

  (start-refresh-loop! tip valid)

  (def client (build-client (:client-id secrets)
                            (:client-secret secrets)
                            valid))
  (register-message-handler client)
  (.joinChannel (.getChat client) "concision")

  ;; !ping should work now

  (.close client) ;; close when done
  )

(comment
  (import '[javax.sound.sampled AudioSystem])

  (doseq [info (AudioSystem/getMixerInfo)]
    (println (.getName info) "—" (.getDescription info))))

(comment
  ;;
  )