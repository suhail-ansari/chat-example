(ns chat-protob.db
	(:use korma.db korma.core))

(declare users chat-message)

(defdb chat-db (mysql {:db "db"
                       :user "user"
                       :password "pass"
                       :host "127.0.0.1"
                       :port "3306"}))

(defentity users
  (pk :id)
  (table :chatusers) 
  (entity-fields :userid :email :status))

(defentity chat-message
	(pk :id)
	(table :chatmessages)
	(entity-fields :fromid :toid :ts :id))



;;=======Queries======;;
;;create table chatmessages(
;;	id int primary key not null auto_increment,
;;	fromid varchar(255) not null,
;;	toid varchar(255) not null,
;;	content text,
;;	ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP 
;;	);

;;create table chatusers(
;;	id int primary key not null auto_increment,
;;	userid varchar(255) unique not null,
;;	email varchar(255) unique not null,
;;	pwd varchar(255) not null default "sarah",
;;	status varchar(255) not null default "offline");