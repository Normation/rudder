#####################################################################################
# Copyright 2012 Normation SAS
#####################################################################################
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, Version 3.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
#
#####################################################################################

#=================================================
# Specification file for ncf
#
# Install the ncf framework
#
# Copyright (C) 2013 Normation
#=================================================

#=================================================
# Variables
#=================================================
#%define installdir       /usr

#=================================================
# Header
#=================================================
Summary: CFEngine framework
Name: ncf
Version: %{real_version}
Release: 1%{?dist}
Epoch: 0
License: GPLv3
URL: http://www.ncf.io
Source: ncf-%{version}.tar.gz

Group: Applications/System

BuildRoot: %{_tmppath}/%{name}-%{version}-%{release}-root-%(%{__id_u} -n)
BuildArch: noarch

# Add Requires here - order is important
BuildRequires: python, asciidoc, libxml2, libxslt, docbook-dtds, docbook-style-xsl

%description
ncf is a CFEngine framework aimed at helping newcomers on CFEngine
to be more quickly operationnal and old timers to spend less time
focusing on low level details and have more time for fun things.

#=================================================
# Source preparation
#=================================================
%prep
%setup 

#=================================================
# Building
#=================================================
%build

#=================================================
# Installation
#=================================================
%install

rm -rf %{buildroot}
ls
cat Makefile
make install DESTDIR=%{buildroot}/usr

%pre -n ncf
#=================================================
# Pre Installation
#=================================================


%post -n ncf
#=================================================
# Post Installation
#=================================================


#=================================================
# Cleaning
#=================================================
%clean
rm -rf %{buildroot}

#=================================================
# Files
#=================================================
%files
%defattr(-, root, root, 0755)
/usr/


#=================================================
# Changelog
#=================================================
%changelog
* Tue Jul 21 2015 - Benoît PECCATTE <benoit.peccatte@normation.com> 0.201507210500-1
- First official version
* Thu Dec 05 2013 - Matthieu CERDA <matthieu.cerda@normation.com> 0.2013120500-1
- Initial release
