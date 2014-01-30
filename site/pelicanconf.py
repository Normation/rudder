#!/usr/bin/env python
# -*- coding: utf-8 -*- #
from __future__ import unicode_literals

AUTHOR = u'Normation'
SITENAME = u'ncf - a structured and powerful CFEngine framework'
#SITEURL = 'http://www.ncf-project.org'

TIMEZONE = 'Europe/Paris'

DEFAULT_LANG = u'en'

# Don't use a "pages" sub-directory, we don't intend to use this as a blog majoritarily
PAGE_DIR = ''
ARTICLE_DIR = 'articles'
PAGE_EXCLUDES = ('articles')

# Don't generate useless pages
DIRECT_TEMPLATES = ('index',)

# Clean up everything before generating
DELETE_OUTPUT_DIRECTORY = True

# Feed generation is usually not desired when developing
FEED_ALL_ATOM = None
CATEGORY_FEED_ATOM = None
TRANSLATION_FEED_ATOM = None

# Theme extras
BOOTSTRAP_THEME = 'spacelab'
CUSTOM_CSS = 'static/custom.css'
GITHUB_URL = 'http://github.com/Normation/ncf'
GOOGLE_ANALYTICS = 'UA-15347142-5'
BOOTSTRAP_NAVBAR_INVERSE = True
PYGMENTS_STYLE = 'monokai'
HIDE_SIDEBAR = True

# Tell Pelican to add 'extra/custom.css' to the output dir
STATIC_PATHS = ['images', 'extra/custom.css']

# Tell Pelican to change the path to 'static/custom.css' in the output dir
EXTRA_PATH_METADATA = {
  'extra/custom.css': {'path': 'static/custom.css'}
}

# Sidebar
DISPLAY_TAGS_ON_SIDEBAR = False
LINKS =  (
          ('Source on GitHub', 'http://github.com/Normation/ncf'),
          ('CFEngine', 'http://www.cfengine.com/'),
          ('Rudder', 'http://www.rudder-project.org/'),
         )

# Social widget
#SOCIAL = (('You can add links in your config file', '#'),
#          ('Another social link', '#'),)

DEFAULT_PAGINATION = 10

THEME = 'pelican-bootstrap3' 

CC_LICENSE = 'CC-BY-SA'

# Uncomment following line if you want document-relative URLs when developing
#RELATIVE_URLS = True
