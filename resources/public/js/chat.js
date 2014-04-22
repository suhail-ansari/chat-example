
var chatWindow = function(userid, email, chat){
	console.log(userid);

	var myMessageTemplate = '<div class="chat-message-my">' +
							    '<a class="chat-avatar">{{avatar}}</a>' +
							    '<div class="chat-content">{{content}}</div>' +
							'</div>';
	var otherMessageTemplate = '<div class="chat-message-other">' +
							        '<a class="chat-avatar">{{avatar}}</a>' +
							      	'<div class="chat-content">{{content}}</div>' +
							    '</div>';

 	var chatWindowTemplate ='<div class="chat-window panel panel-info" data-id="{{userid}}">' +
							  '<div class="chat-header panel-heading">' +
							    '<div class="chat-name panel-title">{{email}}</div>' +
							    '<div class="chat-close"><span>&times;</span></div>' +
							  '</div>' +
							  '<div class="chat-main panel-body">' +
							    '<div class="chat-message-container">' +
							    '</div>' +
							    '<div class="chat-input-wrapper">' +
							      '<input class="chat-input" type="text">' +
							    '</div>' +
							  '</div>' +
							'</div>';


	//create the html for chat window//
	var chatWindowHTML = Mustache.render(chatWindowTemplate, {userid: userid, email: email});


	//add it to the chat container//
	$(".chat-container").append(chatWindowHTML);

	//make a reference in chat object
	chat.windows[userid] = $(".chat-container .chat-window[data-id=" + userid +"]");
	chat.windows[userid].userid = userid;
	chat.windows[userid].find(".chat-close span").click(function(){
		chat.windows[userid].remove();
		delete chat.windows[userid];
	});
	//bind event for sending messages//
	chat.windows[userid].find("input").keyup(function(e){
		if(e.keyCode == 13){
			chat.sendMessage(chat.userid, chat.windows[userid].userid, $(this).val().trim());
			$(this).val("");
		}
	});

	chat.myAvatar = chat.userid[0].toUpperCase();
	chat.windows[userid].avatar = userid[0].toUpperCase();

	//add interface for adding new messages
	chat.windows[userid].addMessage = function(message){
		if(message.from === chat.userid){
			chat.windows[userid].find(".chat-message-container").append(Mustache.render(myMessageTemplate, {avatar: chat.myAvatar, content: message.content}));
		}else{
			chat.windows[userid].find(".chat-message-container").append(Mustache.render(otherMessageTemplate, {avatar: chat.windows[userid].avatar, content: message.content}));
		}		
	}

	chat.windows[userid].addOfflineMessage = function(message){
		chat.windows[userid].find(".chat-message-container").append("<div style='width:75%; margin:auto; color:red;'>user offline</div>");
	}
	//add focus method for when, the user clicks the name in the chat-list
	chat.windows[userid].focus = function(){
		chat.windows[userid].find("input").focus();
	}
}




var Chat = function(){
	var chat = {}, transport;
	chat.windows = {};

	chat.processMessage = function(message){
		if(typeof(message) === "string"){
			message = JSON.parse(message);
		}

		if(message.code == 201 || message.code == "201"){
			var toId = message.to;
			if(chat.windows[toId]){
				chat.windows[toId].addOfflineMessage(message);
			}else{
				var email = $(".chat-item[data-id=" + toId + "]").text();
				chatWindow(toId, email, chat);
				chat.windows[toId].addOfflineMessage(message);
			}
			return;	
		}

		var fromId = (message.from === chat.userid)?message.to:message.from;
		if(chat.windows[fromId]){
			chat.windows[fromId].addMessage(message);
		}else{
			var email = $(".chat-item[data-id=" + fromId + "]").text();
			chatWindow(fromId, email, chat);
			chat.windows[fromId].addMessage(message);
		}
	}

	chat.onMessage = function(data){
		console.log(data);
		if(typeof(data) === "string"){
			data = JSON.parse(data);
		}
		var messages;
		if(transport !== "websocket"){
			chat.startLongPoll();
			messages = data.messages;
		}else{
			if(typeof(data.data) === "string"){
				if(data.data === "pong"){
					return;
				}
				data = JSON.parse(data.data);
			}
			messages = data.messages;
		}
		for(var i in messages){
			chat.processMessage(messages[i]);
		}
	}

	chat.onError = function(err){
		chat.loginError();
		$.cookie("token", "");
		if(chat.connectionPingInterval){
			clearInterval(chat.connectionPingInterval);	
		}
		console.log("long poll error");
		console.log(err);
	}

	chat.startLongPoll = function(){
		if(!chat.token){
			console.log("no token found, please reconnect");
			return;
		}else{
			$.ajax({
				url: "/connect/" + chat.token,
				success: chat.onMessage,
				error: chat.onError
			});
		}
	}

	chat.connect = function(){
		if(transport === "websocket"){
			chat.connection = new WebSocket("ws://" + location.host + "/connect/" + chat.token);
			chat.connection.onmessage = chat.onMessage;
			chat.connection.onerror = chat.onError;
			chat.connectionPingInterval = setInterval(function(){
				chat.connection.send("ping");
			}, 30000);

			window.onbeforeunload = function(){
				chat.connection.onclose = function(){};
				chat.connection.close();
			}
		}else{
			chat.startLongPoll();
		}
		chat.getList();
	}

	chat.loginSuccess = function(data){
		console.log(data);
		if(!data.token){
			console.log("no token in response");
		}else{
			chat.token = data.token;
			chat.userid = data.userid;
			chat.connect();
			$.cookie("token", data.token);
			$.cookie("userid", data.userid);
		}
		$("#login-diag").hide(0);
	}

	chat.loginError = function(err){
		$("#login-diag").show(0);
		$("#login-diag .email-field").val("");
		$("#login-diag .pwd-field").val("");
		console.log(err);
		return;
	}

	chat.connectWithToken = function(token, mode){
		if(!mode){
			transport = "websocket";
		}else{
			transport = mode;
		}

		chat.token = token;
		chat.connect();
	}

	chat.connectWithCreds = function(email, pass, mode){
		if(!mode){
			transport = "websocket";
		}else{
			transport = mode;
		}
		$.ajax({
			url: "/login",
			data: {
				email: email,
				pwd: pass
			},
			success: chat.loginSuccess,
			error: chat.loginError
		});
	}

	chat.sendMessage = function(from, to, message){
		$.ajax({
			url: "/send",
			type: "POST",
			data: {
				from: from,
				to: to,
				content: message
			},
			success: function(data){
				console.log(data);
			},
			error: function(err){
				console.log(err);
			}
		});
	}

	chat.chatItemClick = function(userid, email){
		if(chat.windows[userid]){
			chat.windows[userid].focus();
		}else{
			chatWindow(userid, email, chat);
		}
	}

	chat.listSuccess = function(data){
		var list = data.list;
		for(var i in list){
			var item = list[i];
			if(item.userid === chat.userid){
				continue;
			}
			var chatItemHTML = "<div class='chat-item' data-id='" + item.userid + "'>" + item.email +"</div>"
			$(".chat-list .chat-list-container").append(chatItemHTML);
		}

		$(".chat-list .chat-item").click(function(ev){
			console.log($(this).attr("data-id"));
			chat.chatItemClick($(this).attr("data-id"), $(this).text());
		});
	}

	chat.listError = function(err){
		console.log(err);
	}


	chat.getList = function(){
		$.ajax({
			url: "/list/" + chat.token,
			type: "POST",
			success: chat.listSuccess,
			error: chat.listError
		});
	}

	return chat;
}

$(document).ready(function(){

	var showLoginDiag = function(){
		$("#login-diag").show(0);
	}

	var chat = new Chat();
	var token = $.cookie("token");
	var userid = $.cookie("userid");
	if(token){
		if(location.hash === "#ajax"){
			chat.userid = userid;
			chat.connectWithToken(token, "ajax");
		}else{
			chat.userid = userid;
			chat.connectWithToken(token);
		}
	}else{
		showLoginDiag();
	}

	$("#login-diag button").click(function(event){
		console.log("clicked");
		var email = $("#login-diag .email-field").val();
		var pwd = $("#login-diag .pwd-field").val();

		if(!email || !pwd){
			alert("please enter the proper creds");
		}else{
			if(location.hash === "#ajax"){
				chat.connectWithCreds(email, pwd, "ajax");
			}else{
				chat.connectWithCreds(email, pwd);
			}
		}
	});

});
//
//	start();	
//});


