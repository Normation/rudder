/*(You must include this commment)
 * Round Corners® 2009 
 * Version - in progress...
 * written by Avinoam Henig
 * Special thanks to Dan Blaisdell for help and mentoring (http://manifestwebdesign.com/)
 * 
 * For more information go to:
 * http://roundCorners.avinoam.info
 * or email me at: contact@avinoam.info
 * 
 * Licensed under the MIT license:
 * http://www.opensource.org/licenses/mit-license.php
 * 
 * Required files:
 * jQuery 1.3 Core Javascirpt File
 * Explorer Canvas Release 3 Javascript File
 */
/*
 * To-Do:
 * 	This Release:
 * 		fix bugs (see below*)
 * 		comment entire script well
 * 		clean up code
 * 		optimize javascript for faster speeds (escpecially on IE)
 * 	Later:
 * 		improve the getting of hover, active, and focus styles
 * 		improve canvas drawing
 * 		add features (see below**)
 * 
 * *Bugs:
 * placing on documentation is wrong on ie 6 and 7
 * On em change problems on Safari, Chrome, and IE
 * firefox (sometimes safari also, maybe others) does weird resize and move little things on hover sometimes
 * active styles dont work on opera
 * 
 * **Features:
 * round images
 * support animations
 * shadows
 * tooltips
 * background transparency
 * custom shapes
 */

//Wrapper function
(function($){

//Array of functions to run on fontchange
var emFuncs = new Array();

//Add a function to emFuncs array
$.fn.fontchange = function(func){
	emFuncs[emFuncs.length] = func;
	return this;
};

//on load
$(window).bind('load', function(){
	
	//Create element used to check if the em value has changed
	$emEL = $('<div></div>').css({
		
		//make position absolute with em's so we can check if position in pixels has changed
		'position' : 'absolute',
		'left' : '-100em',
		
		//make sure its not visible
		'width' : '0px',
		'height' : '0px',
		'background' : 'none',
		'border:' : 'none',
		'padding' : '0px',
		'margin' : '0px'
	
	//add to the beginning of the body
	}).prependTo('body');
	
	//save the left position of the em element in pixels
	var oLeft = $emEL.offset().left;
	
	//on keyup run function to check if position in pixels has changed
	$(window).keyup(function(){
		checkEM();
	});
});

//function to check if position of em element in pixels has changed
function checkEM(){
	
	//get the current left position of $emEL in pixels
	var nLeft = $emEL.offset().left;
	
	//Compare new left with old left if its not the same run all emFuncs
	if(nLeft!=oLeft){
		for(var i=0; i<emFuncs.length; i++) emFuncs[i]();
	};
	
	//Change old left to new left so that next time it wont ruun functions again until em has changed again
	oLeft = nLeft;
	
};

//change html function so that if the element has been rounded then its runs the html function on the inner span that has all the content in it
//otherwise just run the regiular html function
$.fn._html = $.fn.html;
$.fn.html = function(val){
	
	//if there is a value run through all the elements and run the apropriate function
	//else just return the apropriate function
	if(val || typeof val == 'string'){
		$(this).each(function(){
			if(this.canvas) $('span.inner', this)._html(val);
			else $(this)._html(val);
		});
		return this;
	} else{
		return this.canvas ? $('span.inner', this)._html() : $(this)._html();
	};
	
};

//add setXY fuctionality to the offset funciton
$.fn._offset = $.fn.offset;
$.fn.offset = function(newXY){
	return newXY ? this.setXY(newXY) : this._offset();
};
$.fn.setXY = function(newXY){
	$(this).each(function(){
		var el = this;
		var hide = false;
		if($(el).css('display')=='none'){
			hide = true;
			$(el).show();
		};
        var style_pos = $(el).css('position');
        if (style_pos == 'static') {
        	$(el).css('position','relative');
            style_pos = 'relative';
        };
		var pageXY = $(el).offset();
		if(pageXY){
			var delta = {
				left : parseInt($(el).css('left')),
				top : parseInt($(el).css('top'))
			};
			if (isNaN(delta.left)) delta.left = (style_pos == 'relative') ? 0 : el.offsetLeft;
			if (isNaN(delta.top)) delta.top = (style_pos == 'relative') ? 0 : el.offsetTop;
			if (newXY.left || newXY.left===0) $(el).css('left',newXY.left - pageXY.left + delta.left + 'px');
			if (newXY.top || newXY.top===0) $(el).css('top',newXY.top - pageXY.top + delta.top + 'px');
		};
		if(hide) $(el).show();
	});
	return this;
};

//add get hover, active, and focus styles capability to the css fuction
$.fn._css = $.fn.css;
$.fn.css = function(one, two){
	var el = this;
	var theResult = el;
	if(arguments.length==1 && typeof one=='string'){
		var type, style, result = false;
		if(one.search(':')!=-1){
			type = one.split(':')[0].replace(' ', '');
			style = one.split(':')[1].replace(' ', '');
		} else{
			theResult = el._css(one, two);
		};
		if(type && style){
			var styleSplits = style.split('-');
			style = styleSplits[0];
			for(var s=1; s<styleSplits.length; s++){
				style += styleSplits[s].substring(0, 1).toUpperCase();
				style += styleSplits[s].substring(1, styleSplits[s].length);
			};
			var rules = el.getCSSRules(':'+type);
			result = el._css(style);
			if(type=='active'){
				var hoverRules = el.getCSSRules(':hover');
				for(var hr=0; hr<hoverRules.length; hr++){
					if(hoverRules[hr].style[style]) result = hoverRules[hr].style[style];
				};
			};
			if(result) for(var r=0; r<rules.length; r++) if(rules[r].style[style]) result = rules[r].style[style];
			theResult = result;
		} else theResult = el._css(one, two);
	} else theResult = this._css(one, two);
	return theResult;
};
$.fn.getCSSRules = function(type){
	var el = this;
	var elRules = new Array();
	if(type==null) type = '';
	for(var ss = 0; ss<document.styleSheets.length; ss++){
		if($.browser.msie) var rules = document.styleSheets[ss].rules;
		else var rules = document.styleSheets[ss].cssRules;
		for(var r=0; r<rules.length; r++){
			if(!rules[r].selectorText || rules[r].selectorText.search(type)==-1) continue;
			var sel = rules[r].selectorText.replace(':hover', '').replace(':active', '').replace(':focus', '');
			if ($(sel)) {
				$(sel).each(function(){
					if ($(this)[0] == $(el)[0]) {
						elRules[elRules.length] = rules[r];
					};
				});
			};
		};
	};
	return elRules;
};

//fuction to initialize canvas
$.fn.canvas = function(){
	
	$(this).each(function(){
		
		var el = this;
		
		//if there is already a canvas for this element then end the function
		if(el.canvas) return;
		
		//if element is hidden then show it temporarily
		var hide = false;
		if($(el).css('display')=='none'){
			//remember to hide it later
			hide = true;
			$(el).show();
		};
		
		//variable to know if the element doesn't have a closing tag
		var close = false;
		
		//variable containig function name needed to be used to add canvas to the page (can vary according to type of tag and browser)
		var funcName = 'insertBefore';
		
		//if element does not have a closing tag close = true
		if(el.tagName=='INPUT' || el.tagName=='TEXTAREA' || el.tagName=='IMG') close = true;
		
		//variable to know how to display span that holds all the content above the canvas (can vary according to type of tag and browser)
		var tagDisplay = 'inline';
		
		if($.browser.msie) tagDisplay = $(el).css('display');
		
		//if using opera display span block
		if($.browser.opera) tagDisplay = 'block';
		
		//if element has a closing tag
		if(!close){
			
			//wrap the inner contents of the element with a span, with the correct display type, and a class of inner
			$(el).wrapInner('<span style="display: '+tagDisplay+';position: relative; z-index: 1; background: none; border: none; margin: 0px; padding: 0px;" class="inner" />');
			
			//if using safari change the position of all the elements inside the span to relative
			if($.browser.safari) $('span.inner', el).children().css('position', 'relative');
			
			//if using ie the canvas is placed last inside the element, else place it first inside
			if($.browser.msie) funcName = 'appendTo';
			else funcName = 'prependTo';	
		
		};
		
		//variable to place the canvas element in
		var canvas;
		
		//if ie we initialize it for excanvas
		if ($.browser.msie){
			
			//for excanvas release 3 we need to use createElement on a canvas for excanvas to run
			canvas = document.createElement('canvas');
			
			//initialize the canvas with excanvas
			el.canvas = G_vmlCanvasManager.initElement(canvas);
		
		} else{
			
			//create canvas
			canvas = $('<canvas/>');
			
			//save canvas for future reference
			el.canvas = canvas[0];
			
		};
		
		//add canvas to page in the specified spot, with the same width and height as the element
		//positioned absolutely with the setXY function at the same spot as the element
		$(canvas)[funcName](el).attr({
			'width': $(el).outerWidth(),
			'height': $(el).outerHeight()
		}).css({ 
			'position' : 'absolute',
			'background' : 'none',
			'border' : 'none', 
			'padding' : '0px'
		}).offset($(el).offset());

		if(close) $(el).css('position', 'relative');
		el.canvas.ctx = el.canvas.getContext('2d');
		el.canvas.roundCornersEvent = false;
		el.canvas.ctx.roundRect = function(width, height, x, y , tl, tr, bl, br){
			this.beginPath();
			if(tl>0) this.moveTo(tl + x, y);
			else this.moveTo(x, y);
			if(tl>0) this.quadraticCurveTo(x, y, x, tl + y);
			if(bl>0) this.lineTo(x, (height + y) - bl);
			else this.lineTo(x, height + y);
			if(bl>0) this.quadraticCurveTo(x, height + y, bl + x, height + y);
			if(br>0) this.lineTo((width + x) - br, height + y);
			else this.lineTo(width + x, height + y);
			if(br>0) this.quadraticCurveTo(width + x, height + y, width + x, (height + y) - br);
			if(tr>0) this.lineTo(width + x, tr + y);
			else this.lineTo(width + x, y);
			if(tr>0) this.quadraticCurveTo(width + x, y, width + x - tr, y);
			if(tl>0) this.lineTo(tl, y);
			else this.lineTo(x, y);
		};
		el.focused = false;
		el.styles = {
			bgColor : $(el).css('background-color'),
			radius : [0, 0, 0, 0],
			border : {
				top : {
					width : parseInt($(el).css('border-top-width')),
					color : $(el).css('border-top-color')
				},
				left : {
					width : parseInt($(el).css('border-left-width')),
					color : $(el).css('border-left-color')
				},
				bottom : {
					width : parseInt($(el).css('border-bottom-width')),
					color : $(el).css('border-bottom-color')
				},
				right : {
					width : parseInt($(el).css('border-right-width')),
					color : $(el).css('border-right-color')
				}
			},
			hover : {
				bgColor: $(el).css('hover:background-color'),
				border : {
					top : {
						width : parseInt($(el).css('hover:border-top-width')),
						color : $(el).css('hover:border-top-color')
					},
					left : {
						width : parseInt($(el).css('hover:border-left-width')),
						color : $(el).css('hover:border-left-color')
					},
					bottom : {
						width : parseInt($(el).css('hover:border-bottom-width')),
						color : $(el).css('hover:border-bottom-color')
					},
					right : {
						width : parseInt($(el).css('hover:border-right-width')),
						color : $(el).css('hover:border-right-color')
					}
				}
			},
			active : {
				bgColor: $(el).css('active:background-color'),
				border : {
					top : {
						width : parseInt($(el).css('active:border-top-width')),
						color : $(el).css('active:border-top-color')
					},
					left : {
						width : parseInt($(el).css('active:border-left-width')),
						color : $(el).css('active:border-left-color')
					},
					bottom : {
						width : parseInt($(el).css('active:border-bottom-width')),
						color : $(el).css('active:border-bottom-color')
					},
					right : {
						width : parseInt($(el).css('active:border-right-width')),
						color : $(el).css('active:border-right-color')
					}
				}
			},
			focus : {
				bgColor: $(el).css('focus:background-color'),
				opacity : $(el).css('opacity'),
				border : {
					top : {
						width : parseInt($(el).css('focus:border-top-width')),
						color : $(el).css('focus:border-top-color')
					},
					left : {
						width : parseInt($(el).css('focus:border-left-width')),
						color : $(el).css('focus:border-left-color')
					},
					bottom : {
						width : parseInt($(el).css('focus:border-bottom-width')),
						color : $(el).css('focus:border-bottom-color')
					},
					right : {
						width : parseInt($(el).css('focus:border-right-width')),
						color : $(el).css('focus:border-right-color')
					}
				}
			}
		};
		if(hide) $(el).hide();
	});
	return this;
};
$.fn.getCanvas = function(){
	var el = this;
	$(el).canvas();
	var cvs = $(el)[0].canvas;
	if ($.browser.msie) {
		$(cvs).children().css({
			'position': 'relative',
			'background': 'none',
			'border': 'none',
			'padding': '0px',
			'margin': '0px',
			'left': '0px',
			'top': '0px'
		});
	};
	return cvs;
};
$.fn.draw = function(specialStyle){
	$(this).each(function(){
		var el = this;
		var hide = false;
		if($(el).css('display')=='none'){
			hide = true;
			$(el).show();
		};
		var canvas = $(el).getCanvas(tl, tr, br, bl, width, height);
		var tl = el.styles.radius[0];
		var tr = el.styles.radius[1];
		var br = el.styles.radius[2];
		var bl = el.styles.radius[3];
		var border, bgColor;
		switch(specialStyle){
			case 'hover':
				border = el.styles.hover.border;
				bgColor = el.styles.hover.bgColor;
			break;
			case 'active':
				border = el.styles.active.border;
				bgColor = el.styles.active.bgColor;
			break;
			case 'focus':
				border = el.styles.focus.border;
				bgColor = el.styles.focus.bgColor;
			break;
			default:
				border = el.styles.border;
				bgColor = el.styles.bgColor;
		};
		var width = $(el).outerWidth();
		var height = $(el).outerHeight();
		$(canvas).attr({
			'width' : width,
			'height' : height
		}).offset($(el).offset());
		var ctx = canvas.ctx;
			if(canvas.oldWidth && canvas.oldHeight) ctx.clearRect(0, 0, canvas.oldWidth, canvas.oldHeight);
			canvas.oldWidth = width;
			canvas.oldHeight = height;
			var bWidth = border.top.width;
			if(border.right.width>bWidth) bWidth = border.right.width;
			if(border.bottom.width>bWidth) bWidth = border.bottom.width;
			if(border.left.width>bWidth) bWidth = border.left.width;
			if (bWidth > 0) {
				ctx.roundRect(width, height, 0, 0, tl, tr, bl, br);
				ctx.fillStyle = border.top.color;
				ctx.fill();
				if(bWidth>tl && bWidth>tr && bWidth>br && bWidth>bl){
					ctx.roundRect(width - (border.right.width+border.left.width), 
					height - (border.bottom.width+border.top.width), border.left.width, border.top.width, 
					0, 0, 0, 0);
				} else{
					ctx.roundRect(width - (border.right.width+border.left.width), 
						height - (border.bottom.width+border.top.width), border.left.width, border.top.width, 
						tl - border.left.width, tr - border.right.width, bl - border.left.width, br - border.right.width);
				};
				if(bgColor=='rgba(0, 0, 0, 0)' || bgColor=='transparent'){
					var parBgColor = '#ffffff';
					$(el).parents().each(function(){
						if($(this).css('background-color')!='rgba(0, 0, 0, 0)' && $(this).css('background-color')!='transparent'){
							parBgColor = $(this).css('background-color');
							return false;
						};
					});
					ctx.fillStyle = parBgColor;
				} else{
					ctx.fillStyle = bgColor;
				};
			} else{
				ctx.roundRect(width, 
					height, 0, 0, tl, tr, bl, br);
				ctx.fillStyle = bgColor;
			};
			ctx.fill();
		$(el).css({
			'background' : 'none',
			'border-color' : 'transparent'
		});
		if($.browser.msie && parseInt($.browser.version)==6){
			$(el).css({
				'padding-top' : parseInt($(el).css('padding-top')+border.top.width)+'px',
				'padding-left' : parseInt($(el).css('padding-left')+border.left.width)+'px',
				'border' : 'none'
			});
		};
		if(hide) $(el).hide();
	});
	return this;
};
function getGradient(ctx, colors, height){
       var grad = ctx.createLinearGradient(0, 0, 0, height);
       var g = 0;
       for(var i=0; i<colors.length; i++){
               var offset = i / (colors.length-1);
               grad.addColorStop(offset, colors[g]);
               g++;
       };
       return grad;
}
$.fn.bg = function(radius, gradient, specialStyle){
	$(this).each(function(){
		var el = this;
		var $el = $(this);
		if(typeof($el.attr('radius'))!='undefined') alert('hi');
		if(typeof($el.attr('gradient'))!='undefined') gradient = eval($el.attr('gradient'));
		$el.canvas();
		el.canvas.args = {
			radius: radius,
			gradient: gradient
		};
		if(gradient){
			if(typeof gradient[0]=='object'){
				var colors = gradient[0];
				if(gradient[1]){
					if (gradient[1].length>0) var colorsHover = gradient[1];
					else colorsHover = colors;
				};
				if(gradient[2]){
					if (gradient[2].length>0) var colorsClick = gradient[2];
					else{
						if(colorsHover) var colorsClick = colorsHover;
						else var colorsClick = colors;
					};
				};
				if(gradient[3]){
					if (gradient[3].length>0) var colorsFocus = gradient[3];
					else{
						var colorsFocus = colors;
					};
				};
			} else{
				var colors = gradient;
				var colorsHover = gradient;
				var colorsClick = gradient;
				var colorsFocus = gradient;
			};
			if(colors) el.styles.bgColor = getGradient(el.canvas.ctx, colors, $el.outerHeight());
			if(colorsHover) el.styles.hover.bgColor = getGradient(el.canvas.ctx, colorsHover, $el.outerHeight());
			if(colorsClick) el.styles.active.bgColor = getGradient(el.canvas.ctx, colorsClick, $el.outerHeight());
			if(colorsFocus) el.styles.focus.bgColor = getGradient(el.canvas.ctx, colorsFocus, $el.outerHeight());
		};
		if(radius){
			if(typeof radius != 'object') radius = [radius, radius, radius, radius];
		} else{
			radius = [0, 0, 0, 0];
		};
		if(radius[0]==null){
			radius[0] = 14;
		};
		for(var i=0; i<4; i++){
			if(radius[i]==null){
				radius[i] = radius[0];
			};
			if(typeof radius[i] == 'string'){
				if (radius[i].search('em')!=-1) {
					radius[i] = parseFloat(radius[i]);
					radius[i] = radius[i] * 14;
				};
				radius[i] = parseInt(radius[i]);			
			};
		};
		el.styles.radius = radius;
		var canvas = el.canvas;
		if (!canvas.roundCornersEvent) {
			canvas.roundCornersEvent = true;
			$(window).resize(function(){
				$(el).bg(radius, gradient);
			});
			$(el).mouseenter(function(){
				if(!el.focused) $(el).bg(radius, gradient, 'hover');
			}); 
			$(el).mouseleave(function(){
				if(!el.focused) $(el).bg(radius, gradient);
			});
			$(el).mousedown(function(){
				if(!el.focused) $(el).bg(radius, gradient, 'active');
			});
			$(el).mouseup(function(){
				if(!el.focused) $(el).bg(radius, gradient, 'hover');
			});
			$(el).focus(function(){
				el.focused = true;
				$(el).bg(radius, gradient, 'focus');
			});
			$(el).blur(function(){
				el.focused = false;
				$(el).bg(radius, gradient);
			});
			emFuncs[emFuncs.length] = function(){
				if(!el.focused) $(el).bg(radius, gradient);
				else $(el).bg(radius, gradient, 'focus');
			};
		};
		$(el).draw(specialStyle);
	});
	return this;
};
$.fn.roundCorners = function(tl, tr, br, bl){
	return $(this).bg([tl, tr, br, bl]);
};

})(jQuery); //end wrapper funciton