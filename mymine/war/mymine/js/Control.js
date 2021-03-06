

function Control(){this.initialize.apply(this, arguments)};
(function(Class){
	Class.prototype.initialize = function() {
	}

	var checkButtons = {
		filter_user: false
	};

	Class.userId = null;
	Class.checkButtons = checkButtons;

	Class.init = function() {
		new RedMine().getCurrentUser(function(data){
			Class.userId = data.user.id;
		});

		Class.setup();
	}

	Class.setValue = function(name, val) {
		checkButtons[name] = val
	}
	Class.getValue = function(name) {
		return checkButtons[name];
	}

	Class.setup = function() {
		setupCustomQuery();
		setupProjects();
		setupMasterTable();
	}
	function setupCustomQuery() {
		var querys = Config.redmineCustomQuery;
		if (querys == null) return;
		if (Config.redmineCustomImg == null) return;

		var $btns = $("#customQueryButtons");
		$btns.html("");
		for (var i=0; i<querys.length; i++) {
			if (Config.redmineCustomQuery[i] == "") continue;

			$btn = $("<img class='CheckButton' />");
			$btn.attr("id", "custom_"+i);
			$btn.attr("data-group", "custom");
			$btn.attr("src", Config.redmineCustomImg[i])
			$btn.attr("alt", Config.redmineCustomName[i]);
			$btns.append($btn);
		}
	}

	function setupProjects() {
		var projects = Config.redmineProjectId;
		if (projects == null) return;

		var $sel = $("#projectSelector").html("<option value=''>*</option>");
		for (var i=0; i<projects.length; i++) {
			var opt = "<option value='"+projects[i]+"'>"
				+Config.redmineProjectName[i]
				+"</option>"
			$sel.append($(opt));
		}
		$sel.val(Storage.loadData("projectSelector"));
	}

	function setupMasterTable() {
		var masterTable = MasterTable.getMasterTable();
		if (masterTable == null) return;

		var $filters = $("#filterButtons").html("");
		for (var k in masterTable) {
			if (k.indexOf("cf_")==0) continue; // TODO:カスタムフィールドの扱い
			var $btn = Class.makePulldownButton("filter_"+k, masterTable[k]);
			$filters.append($btn);
		}
	}


	Class.popupBalloon = function($button) {
		var alt = $button.attr("alt");
		var $balloon = $("#balloon");
		$balloon.offset({top:0,left:0}).css("display","inline-block").html(alt);

		var offset = $button.offset();
		offset.top += $button.width() + 8 ;
		offset.left += $button.height() + 8;
		if (offset.left+$balloon.width()>$(document.body).width()) {
			offset.left = $(document.body).width()-$balloon.width()-12;
		}
		$balloon.offset(offset);
	}
	Class.hideBalloon = function() {
		$("#balloon").hide();
	}

	//-----------------------------------------------------
	// Check Button functions
	//-----------------------------------------------------
	Class.toggleCheckButton = function(elem) {
		var $elem = $(elem);

		if ($elem.attr("disabled")) return;

		var curFlag = checkButtons[elem.id];
		var group = $elem.attr("data-group");
		if (group) {
			var $group = $(".CheckButton[data-group='"+group+"']");
			$group.each(function(){
				Class.offCheckButton(this);
			})
			//Class.onCheckButton(elem);
		} //else {
			if (curFlag) {
				Class.offCheckButton(elem);
			} else {
				Class.onCheckButton(elem);
			}
		//}
		$elem.trigger("change", [checkButtons[elem.id], elem.id, group]);
	};
	Class.offCheckButton = function(elem) {
		checkButtons[elem.id] = false;
		$(elem).removeClass("CheckButtonOn");
	}
	Class.onCheckButton = function(elem) {
		checkButtons[elem.id] = true;
		$(elem).addClass("CheckButtonOn");
	}
	Class.checkButtonGroup = function(group) {
		// TODO:手抜き
		for (var i=0; i<10; i++) {
			if (checkButtons[group+"_"+i]) return i;
		}
		return -1;
	}

	//-----------------------------------------------------
	// Pulldown Button functions
	//-----------------------------------------------------

	Class.makePulldownButton = function(id,opts) {
		var $elem = $("#templ_pulldownButton>span").clone();
		var $img = $elem.find("img:first-child");

		var icon = opts.icon;
		if (icon==null||icon=="") icon="img/funnel.png";
		$elem.attr("id", id);
		$img.attr("src", icon);
		$img.attr("alt", opts.name);

		var $menu = $elem.find(".PopupMenu").html("");
		var list = [];
		for (var k in opts.values) list.push({id:k, name:opts.values[k]});
		if (opts.keySort == "name") {
			list.sort(function(a,b){return a.name>b.name?1:-1;});
		} else if (opts.keySort == "id") {
			list.sort(function(a,b){return a.id-b.id;});
		}


		for (var i=0; i<list.length; i++) {
			var $item = $("<div class='PopupMenuItem' ></div>");
			$item.attr("data-value", list[i].id);
			$item.text(list[i].name);
			$menu.append($item);
		}
		return $elem;
	}



})(Control);
