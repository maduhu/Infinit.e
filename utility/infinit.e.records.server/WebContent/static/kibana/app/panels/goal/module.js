/*! kibana - v3.0.0 - 2014-05-16
 * Copyright (c) 2014 Rashid Khan; Licensed Apache License */

define("panels/goal/module",["angular","app","lodash","jquery","kbn","config","chromath"],function(a,b,c,d,e){var f=a.module("kibana.panels.goal",[]);b.useModule(f),f.controller("goal",["$scope","$rootScope","querySrv","dashboard","filterSrv",function(b,d,e,f,g){b.panelMeta={editorTabs:[{title:"Queries",src:"app/partials/querySelect.html"}],modals:[{description:"Inspect",icon:"icon-info-sign",partial:"app/partials/inspector.html",show:b.panel.spyable}],status:"Stable",description:"Displays the progress towards a fixed goal on a pie chart"};var h={donut:!0,tilt:!1,legend:"above",labels:!0,spyable:!0,query:{goal:100},queries:{mode:"all",ids:[]}};c.defaults(b.panel,h),b.init=function(){b.$on("refresh",function(){b.get_data()}),b.get_data()},b.set_refresh=function(a){b.refresh=a},b.close_edit=function(){b.refresh&&b.get_data(),b.refresh=!1,b.$emit("render")},b.get_data=function(){if(0!==f.indices.length){b.panelMeta.loading=!0;var d=b.ejs.Request().indices(f.indices);b.panel.queries.ids=e.idsByMode(b.panel.queries);var h=e.getQueryObjs(b.panel.queries.ids),i=b.ejs.BoolQuery();c.each(h,function(a){i=i.should(e.toEjsObj(a))});var j;d=d.query(i).filter(g.getBoolFilter(g.ids)).size(0),b.inspector=a.toJson(JSON.parse(d.toString()),!0),j=d.doSearch(),j.then(function(a){b.panelMeta.loading=!1;var c=a.hits.total,d=b.panel.query.goal-c;b.data=[{label:"Complete",data:c,color:e.colors[parseInt(b.$id,16)%8]},{data:d,color:Chromath.lighten(e.colors[parseInt(b.$id,16)%8],.7).toString()}],b.$emit("render")})}}}]),f.directive("goal",["querySrv",function(b){return{restrict:"A",link:function(f,g){function h(){g.css({height:f.row.height});var a;a={show:f.panel.labels,radius:0,formatter:function(a,b){var d=parseInt(f.row.height.replace("px",""),10)/8+String("px");return c.isUndefined(a)?"":'<div style="font-size:'+d+';font-weight:bold;text-align:center;padding:2px;color:#fff;">'+Math.round(b.percent)+"%</div>"}};var e={series:{pie:{innerRadius:f.panel.donut?.45:0,tilt:f.panel.tilt?.45:1,radius:1,show:!0,combine:{color:"#999",label:"The Rest"},label:a,stroke:{width:0}}},grid:{backgroundColor:null,hoverable:!0,clickable:!0},legend:{show:!1},colors:b.colors};g.is(":visible")&&require(["jquery.flot.pie"],function(){f.legend=d.plot(g,f.data,e).getData(),f.$$phase||f.$apply()})}g.html('<center><img src="img/load_big.gif"></center>'),f.$on("render",function(){h()}),a.element(window).bind("resize",function(){h()});var i=d("<div>");g.bind("plothover",function(a,b,c){c?i.html([e.query_color_dot(c.series.color,15),c.series.label||"",parseFloat(c.series.percent).toFixed(1)+"%"].join(" ")).place_tt(b.pageX,b.pageY,{offset:10}):i.remove()})}}}])});