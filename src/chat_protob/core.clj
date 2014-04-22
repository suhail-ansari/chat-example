(ns chat-protob.core
	(:use [chat-protob.db :as db] 
		[compojure.core :only [POST GET ANY defroutes]]
		[compojure.handler :only [site]]
		[compojure.route :only [files not-found resources]]
		[ring.middleware.session :only [wrap-session]]
		[ring.middleware.session.cookie :only [cookie-store]]
		[ring.middleware.params :only [wrap-params]]
		[ring.util.response :only [redirect redirect-after-post]]
		org.httpkit.server clostache.parser korma.core
		cheshire.core)
	(:import (java.util UUID))
	(:require [org.httpkit.timer :as timer] 
			  [taoensso.carmine :as car :refer (wcar)]))


;;global socket pool for users
(def socket-pool (atom {}))

;;setup redis for storing sessions and pub/sub messaging queue.
(def redis-conn {:pool {} :spec {:host "127.0.0.1" :port 8001}})
(defmacro redis* [& body] `(car/wcar redis-conn ~@body))


;;
(defn send-to-channels! [userid message]
	(let [final-msg (generate-string {:status 200 :messages [message]})
		  channels (get @socket-pool userid)]
		  (when (pos? (count channels))
		  	(doseq [channel channels]
		  		(#(send! (val %) final-msg) channel)))))


;;cache the messages until the user connects
(defn save-to-cache [userid message-str]
	(let [user-msg-key (str userid "#messages") prev-cache-messages (redis* (car/get user-msg-key))]
		(if (nil? prev-cache-messages)
			(redis* (car/set user-msg-key [message-str]))
			(redis* (car/set user-msg-key (conj prev-cache-messages message-str))))))

;;process incoming messages
(defn incoming-message-handler [message]
	(if (= message 1)
		(println "subscribed to messages queue")
		(do (let [msg-map (parse-string message (fn [k] (keyword k)))]
		      (println msg-map)
		      (future (send-to-channels! (:userid msg-map) (:message msg-map)))))))
		  	
(defn send-to-channels [userid message]
	(redis* (car/publish "messages" (generate-string {:userid userid :message message}))))

;;subscribe to the message channel
(def incoming-message-listener 
	(car/with-new-pubsub-listener (:spec redis-conn)
    	;;{"messages" (fn [msg] (println (type msg))(println msg) (println (type (last msg))))}
    	{"messages" (fn [msg] (incoming-message-handler (last msg))
    						  (println msg))}
   		(car/subscribe "messages")))

;;returns the current timestamp
(defn current-timestamp [] (quot (System/currentTimeMillis) 1))

;;static json-header, used for sending json response
(def json-headers {"Content-Type" "application/json"})

;;static response to an invalid user login attempt
(def invalid-user-response {:status 403
							:headers json-headers
							:body (generate-string {:status 403 :err_message "Unathorized User"})})

;;static response to an incorrect password login attempt
(def login-fail-response {:status 403
							:headers json-headers
							:body (generate-string {:status 403 :err_message "Incorrect Password"})})

;;========login handling========;;

;;creates a new session-token, saves the user in redis for reference 
(defn create-session [session-token user]
	(redis* (car/set session-token user)
			(car/set (str (:userid user) "#status") "online"))
	{:status 200
	 :headers json-headers
	 :body (generate-string {:status 200 :message "OK" :token session-token :userid (:userid user)})})

;;updates the last connection time for the given user
(defn update-last-connection [{userid :userid}]
	(redis* (car/set (str userid "#last-connection") (current-timestamp))))

;;called after the POST pwd matches the user pwd.
;;sets user as online
;;updates the last-connection time
;;cand calls create-session to generate a new session.
(defn login-success [user]
	(update users (set-fields {:status "online"}) (where {:id (:id user)})) ;; mark the user as "online"
	(update-last-connection user) ;; update the user's last connect time
	(let [session-token (str (UUID/randomUUID))]
		(create-session session-token user)))

;;matches POST pwd with user pwd
(defn login-user [user pwd]
	(if (= (:pwd user) pwd)
			(login-success user)
			login-fail-response))

;;get handler for login
(defn login-handler [{params :params :as request}]
	(let [user (first (select users (where {:email (:email params)})))]
		(if (nil? user)
			invalid-user-response
			(login-user user (:pwd params)))))	


;;========websocket connection handling========;;

;;static response when unauthorized token is used
(def unathorized-connection-response {:status 403
 									  :headers json-headers 
 									  :body (generate-string {:status 403 :message "Unauthorized Token"})})

(defn increase-channel-count [userid]
	(if (nil? (redis* (car/get (str userid "#count"))))
		(redis* (car/set (str userid "#count") 1))
		(redis* (car/incr (str userid "#count")))))

(defn decrease-channel-count [userid]
	(when (not (nil? (redis* (car/get (str userid "#count")))))
		(redis* (car/decr (str userid "#count")))))

;;saves channel when a user connects, and then use this saved channel to send messages in the future
;;(it's just a refrence to the connected channels)
;; also save the last connection time of the user, to be used to implement presence
(defn save-channel [userid channel-uuid channel]
	(let [current-user-channels (get @socket-pool userid)]
		(swap! socket-pool assoc userid (assoc current-user-channels channel-uuid channel)))
	(update-last-connection {:userid userid})
	(increase-channel-count userid))


;;implements a timeout to check whether the user has gone offline
;;when a user disconnects and the number of connected channels reaches zero
;;then this function will run after 5 seconds to check whether the user
;;has tried to reconnect in the past 5 seconds, if not then user is said to have
;;gone offline
(defn set-offline-timeout [userid session-token]
	(timer/schedule-task 5000
		(let [current-time (current-timestamp)
		 	  last-connection-time (Long. (or (redis* (car/get (str userid "#last-connection"))) 0))]
			(if (and (>= (- current-time last-connection-time) 5000) (zero? (count (get @socket-pool userid))) (= "0" (redis* (car/get (str userid "#count")))))
				(let [user (redis* (car/get session-token))]
					(update users (set-fields {:status "offline"}) (where {:id (:id user)}))
					(swap! socket-pool dissoc userid)
					(redis* (car/del session-token)
							(car/del (str userid "#status"))
							(car/del (str userid "#last-connection"))
							(car/del (str userid "#count"))))))))


;;handles the closing of channel, when its disconnected from the client-side
;;if the number of connected channels reaches zero, then this calls the above function
;;to handle presence of the user
(defn client-close-handler [userid channel-uuid session-token]
	(let [current-user-channels (get @socket-pool userid)]
		(swap! socket-pool assoc userid (dissoc current-user-channels channel-uuid)))
	(decrease-channel-count userid)
	(when (and (= 0 (count (get @socket-pool userid))) (= "0" (redis* (car/get (str userid "#count"))))) 
		(set-offline-timeout userid session-token)))

;;handles the channel closing from the server side, not much to deal, just remove dead connections
;;from the global socket pool
(defn server-close-handler [userid channel-uuid session-token]
	(let [current-user-channels (get @socket-pool userid)]
			(swap! socket-pool assoc userid (dissoc current-user-channels channel-uuid)))
	(decrease-channel-count userid))



(defn send-previous-msg [user-msg-key channel]
	(let [prev-cache-messages (redis* (car/get user-msg-key))]
		(when-not (nil? prev-cache-messages)
			(do 
				(send! channel (generate-string {:status 202 :messages prev-cache-messages}))
				(redis* (car/del user-msg-key))))))

;;init websocket, when the transport is done via websockets
(defn init-websocket [userid channel-uuid channel session-token]
	(on-close channel (fn [status]
		(if (= status :server-close)
			(server-close-handler userid channel-uuid session-token)
			(client-close-handler userid channel-uuid session-token))))
	(on-receive channel (fn [data]
					(when (= data "ping")
						(do (send! channel "pong")
							(update-last-connection {:userid userid})))))
	(send-previous-msg (str userid "#messages") channel))

;;this is the timeout to handle idle connections, closes the connection and 
;;forces the client to reconnect with a new onnection
(defn set-longpoll-timeout [userid channel-uuid]
	(timer/schedule-task 30000 
		(let [channel (get (get @socket-pool userid) channel-uuid)]
			(when-not (nil? channel)
				(when (open? channel)
					(send! channel (generate-string {:status 300 :message "reconnect"})))))))

;;inits a long poll connection, will call the above timeout if the channel is still open
(defn init-longpoll [userid channel-uuid channel session-token]
	(on-close channel (fn [status]
		(if (= status :server-close)
			(server-close-handler userid channel-uuid session-token)
			(client-close-handler userid channel-uuid session-token))))
	(set-longpoll-timeout userid channel-uuid)
	(send-previous-msg (str userid "#messages") channel))

;;checks the connection method (websocket/long poll) and initalizes accordingly
(defn init-connection [request session-token {userid :userid :as user}]
	(let [channel-uuid (str (UUID/randomUUID))]
		(with-channel request channel
			(save-channel userid channel-uuid channel)
			(if (websocket? channel)
				(init-websocket userid channel-uuid channel session-token)
				(init-longpoll userid channel-uuid channel session-token)))))

;;main get handler when a user connects to the chat with a valid session-token
(defn connection-handler [{params :params :as request}]
	(let [session-token (:token params) user (redis* (car/get session-token))]
		(if (nil? user)
			unathorized-connection-response
			(init-connection request session-token user))))


;;========send message handling========;;

(defn user-offline-response [to-userid]
	{:status 200
	 :headers json-headers
	 :body (generate-string {:status 201 :message {:to to-userid :content "user offline" :code 201}})})


(defn send-message [{params :params :as request}]
	(let [toid-status (redis* (car/get (str (:to params) "#status")))]
		(if (nil? toid-status)
			(user-offline-response (:to params))
			(let [new-msg-id (:GENERATED_KEY (insert chat-message (values {:fromid (:from params) :toid (:to params) :content (:content params)})))
				  fromid-channel-count (redis* (car/get (str (:from params) "#count")))
				  toid-channel-count (redis* (car/get (str (:to params) "#count")))
				  fromid-status (redis* (car/get (str (:from params) "#status")))
				  final-msg {:to (:to params) :from (:from params) :content (:content params) :id new-msg-id}]
				  (if (and (= toid-status "online") (zero? (Integer. (or toid-channel-count 0))))
				      (do (save-to-cache (:to params) (generate-string final-msg))
				      	  (send-to-channels (:to params) (generate-string final-msg)))
				  	  (send-to-channels (:to params) (generate-string final-msg)))
				  (if (and (= fromid-status "online") (zero? (Integer. (or toid-channel-count 0))))
				      (do (save-to-cache (:from params) (generate-string final-msg))
				      	  (send-to-channels (:from params) (generate-string final-msg)))
				  	  (send-to-channels (:from params) (generate-string final-msg)))
				  {:status 200 :headers json-headers :body (generate-string {:status 200 :message "OK"})}))))

;;(redis* (car/publish "messages" (generate-string {:from (:from params) :to (:to params) :content (:content params) :id new-msg-id})))))))

(defn index-handler [request]
	(render-resource "templates/index.html" {}))


;;get the list of all the users with their online status. 
(defn get-list [{params :params :as request}]
	(let [user (redis* (car/get (:token params)))]
		(if (nil? user)
			invalid-user-response
			(let [all-users (select users (fields :email :userid :status))]
				{:status 200
				 :headers json-headers 
				 :body (generate-string {:status 200 :list all-users})}))))



(defroutes main-routes
	(GET "/login" [request] login-handler)
	(GET "/connect/:token" [request] connection-handler)
	(POST "/send" [request] send-message)
	(POST "/list/:token" [request] get-list)
	(GET "/" [request] index-handler)
	(resources "/static"))

(def app (-> main-routes wrap-params site))

(defn -main
	([] (-main "8000")) ;; default port 8000
	([port] (run-server app {:port (Integer. port)})
			(println "Server started @ 127.0.0.1:" port)))
