$(document).ready(function() {
	var config = {
		selectors: {
			navigation: "#navigation",
			content: "#contenu"
		},

		text: {
			navigation: {
				minimize: "Hide this menu",
				maximize: ""
			}
		},

		image: {
			prefix: "images/",

			navigation: {
				minimize: "puceHide.jpg",
				maximize: "PuceMenuCachee.jpg"
			}
		},

		className: {
			reader: "for-reader",

			minimize: "minimize",
			minimized: "minimized",

			maximize: "maximize",
			maximized: "maximized"
		}
	},

	tmp = null,
	cache = {};


	cache.$navigation = $(config.selectors.navigation);
	cache.$content    = $(config.selectors.content);

	// Generating the navigation's minimize link
	tmp = '<a class="menuHide ' + config.className.minimize + '" title="' + config.text.navigation.minimize + '" href="#"><img src="' + config.image.prefix + config.image.navigation.minimize + '" alt="" /> <span>' + config.text.navigation.minimize + '</span></a>';
	cache.$navigation.prepend(tmp);

	cache.minimize_navigation = {};
	cache.minimize_navigation.$link = $(config.selectors.navigation + " a." + config.className.minimize);
	cache.minimize_navigation.$img  = cache.minimize_navigation.$link.find("img");
	cache.minimize_navigation.$text = cache.minimize_navigation.$link.find("span");

	/**
	 * Showing/Hidding the #navigation
	 */
	cache.minimize_navigation.$link.click(function() {
		var $link = $(this);

		if (cache.$navigation.hasClass(config.className.minimized)) {
			// The navigation is already minimized, maximize!
			cache.$navigation.removeClass(config.className.minimized);
			cache.$content.removeClass(config.className.maximized);

			// Changing the image
			cache.minimize_navigation.$img.attr("src", config.image.prefix + config.image.navigation.minimize);

			// Changing the text value
			cache.minimize_navigation.$text.removeClass(config.className.reader);
			cache.minimize_navigation.$text.text(config.text.navigation.minimize);
			cache.minimize_navigation.$link.attr("title", config.text.navigation.minimize);
		} else {
			// The navigation is maximized, lets minimize it!
			cache.$navigation.addClass(config.className.minimized);
			cache.$content.addClass(config.className.maximized);

			// Changing the image
			cache.minimize_navigation.$img.attr("src", config.image.prefix + config.image.navigation.maximize);

			// Changing the text value
			cache.minimize_navigation.$text.addClass(config.className.reader);
			cache.minimize_navigation.$text.text(config.text.navigation.maximize);
			cache.minimize_navigation.$link.attr("title", config.text.navigation.maximize);
		}

		return false; // Don’t follow the link
	});
});