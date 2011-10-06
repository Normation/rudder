#!/usr/bin/env python
# encoding: utf-8
"""
schema-convert.py

# CDDL HEADER START
#
# The contents of this file are subject to the terms of the
# Common Development and Distribution License, Version 1.0 only
# (the "License").  You may not use this file except in compliance
# with the License.
#
# You can obtain a copy of the license at
# trunk/opends/resource/legal-notices/OpenDS.LICENSE
# or https://OpenDS.dev.java.net/OpenDS.LICENSE.
# See the License for the specific language governing permissions
# and limitations under the License.
#
# When distributing Covered Code, include this CDDL HEADER in each
# file and include the License file at
# trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
# add the following below this CDDL HEADER, with the fields enclosed
# by brackets "[]" replaced with your own identifying information:
#      Portions Copyright [yyyy] [name of copyright owner]
#
# CDDL HEADER END
#
#
#      Copyright 2009 Sun Microsystems, Inc.

Created by Ludovic Poitou on 2009-01-28.

This program converts an OpenLDAP schema file to the OpenDS schema file format.
"""

import sys
import getopt
import re
import string

help_message = '''
Usage: schema-convert.py [options] <openldap-schema-file>
options:
\t -o output : specifies the output file, otherwise stdout is used
\t -v : verbose mode
'''


class Usage(Exception):
	def __init__(self, msg):
		self.msg = msg


def main(argv=None):
	output = ""
	seclineoid = 0
	IDs = {}
	if argv is None:
		argv = sys.argv
	try:
		try:
			opts, args = getopt.getopt(argv[1:], "ho:v", ["help", "output="])
		except getopt.error, msg:
			raise Usage(msg)
	
		# option processing
		for option, value in opts:
			if option == "-v":
				verbose = True
			if option in ("-h", "--help"):
				raise Usage(help_message)
			if option in ("-o", "--output"):
				output = value
		
		
	except Usage, err:
		print >> sys.stderr, sys.argv[0].split("/")[-1] + ": " + str(err.msg)
		print >> sys.stderr, "\t for help use --help"
		return 2
	try:
		infile = open(args[0], "r")
	except Usage, err:
		print >> sys.stderr, "Can't open file: " + str(err.msg)
	if output != "":
		try:
			outfile = open(output, "w")
		except Usage, err:
			print >> sys.stderr, "Can't open output file: " + str(err.msg)
	else:
		outfile = sys.stdout
	outfile.write("dn: cn=schema\n")
	outfile.write("objectclass: top\n")
	outfile.write("")
	for i in infile:
		newline = ""
		if not i.strip():
			continue
		#if i.startswith("#"):
		#	continue
		if re.match("objectidentifier", i, re.IGNORECASE):
			# Need to fill in an array of identifiers
			oid = i.split()
			if not re.match ("[0-9.]+", oid[2]):
				suboid = oid[2].split(':')
				IDs[oid[1]] = IDs[suboid[0]] + "." + suboid[1]
			else:	
				IDs[oid[1]] = oid[2]
			continue
		if seclineoid == 1:
			subattr = i.split()			
			if not re.match("[0-9.]+", subattr[0]):
				if re.match (".*:", subattr[0]):
					# The OID is an name prefixed OID. Replace string with the OID
					suboid = subattr[0].split(":")
					repl = IDs[suboid[0]] + "." + suboid[1]
				else:
					# The OID is a name. Replace string with the OID
					repl = IDs[subattr[0]]
				newline = string.replace(i, subattr[0], repl, 1)
			seclineoid = 0
			
		if re.match("attributetype ", i, re.IGNORECASE):
			newline = re.sub("attribute[tT]ype", "attributeTypes:", i)
			# replace OID string with real OID if necessary
			subattr = newline.split()
			if len(subattr) < 3:
				seclineoid = 1
			else: 
				if not re.match("[0-9.]+", subattr[2]):
					if re.match (".*:", subattr[2]):
						# The OID is an name prefixed OID. Replace string with the OID
						suboid = subattr[2].split(":")
						repl = IDs[suboid[0]] + "." + suboid[1]
					else:
						# The OID is a name. Replace string with the OID
						repl = IDs[subattr[2]]
					newline = string.replace(newline, subattr[2], repl, 1)
				
		if re.match("objectclass ", i, re.IGNORECASE):
			newline = re.sub("object[cC]lass", "objectClasses:", i)
			# replace OID String with real OID
			subattr = newline.split()
			if len(subattr) < 3:
				seclineoid = 1	
			else:
				if not re.match("[0-9.]+", subattr[2]):
					if re.match (".*:", subattr[2]):
						# The OID is an name prefixed OID. Replace string with the OID
						suboid = subattr[2].split(":")
						repl = IDs[suboid[0]] + "." + suboid[1]
					else:
						# The OID is a name. Replace string with the OID
						repl = IDs[subattr[2]]
					newline = string.replace(newline, subattr[2], repl, 1)

		# Remove quoted syntax.
		if re.search("SYNTAX\s'[\d.]+'", newline):
			# Found a quoted syntax in an already updated line
			newline = re.sub("SYNTAX '([\d.]+)'", "SYNTAX \g<1>", newline)
		else:
			if re.search("SYNTAX\s'[\d.]+'", i):
				# Found a quoted syntax in the original line
				newline = re.sub("SYNTAX '([\d.]+)'", "SYNTAX \g<1>", i)

		# Remove quoted SUP
		if re.search("SUP\s'[\w\-]+'", newline):
			# Found a quoted sup in an already updated line
			newline = re.sub("SUP '([\w\-]+)'", "SUP \g<1>", newline)
		else:
			if re.search("SUP\s'[\w\-]+'", i):
				# Found a quoted sup in the original line
				newline = re.sub("SUP '([\w\-]+)'", "SUP \g<1>", i)

		# transform continuation lines with only 2 spaces
		if re.match("  +|\t", i):
			if newline != "":
				newline = "  " + newline.strip() + "\n"
			else:	
				newline = "  " + i.strip() + "\n"
			
		if newline != "":
			outfile.write(newline)
		else:
			outfile.write(i)

	outfile.close()
if __name__ == "__main__":
	sys.exit(main())

