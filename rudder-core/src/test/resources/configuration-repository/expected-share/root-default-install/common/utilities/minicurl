#!/usr/bin/env perl
#
#####################################################################################
# Copyright 2014 Normation SAS
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
#
# This script uploads or downloads a file using Perl's LWP library and PUT/GET HTTP method.
#

use strict;
use warnings;
use Getopt::Long;

# HTTP operations
use LWP::UserAgent;
use HTTP::Request::Common;
use MIME::Base64 qw( encode_base64 );

# File handling
use File::Basename;

# Version
my $program_name    = "minicurl.pl";
my $program_version = "1.0";
my $program_description = "Perl HTTP, HTTP/S, FTP download and upload tool";

## Argument handling (Getopt)
my ($get, $put, $httpstatus, $help, $debug, $version, $no_verify);
$get = $put = $httpstatus = $help = $debug = $version = $no_verify = 0;

my ($url, $file, $authentication, $response);
$url = $file = $authentication = $response = '';

GetOptions( "get!"             => \$get,
            "put!"             => \$put,
            "url:s"            => \$url,
            "file:s"           => \$file,
            "authentication=s" => \$authentication,
            "http-status!"     => \$httpstatus,
            "help!"            => \$help,
            "debug!"           => \$debug,
            "version!"         => \$version,
            "no-verify!"       => \$no_verify);

sub usage {
  print "This is " . $program_name . ", v" . $program_version . ": " . $program_description . "\n";
  print "Usage: $0 [--no-verify] [--help] [--debug] [--http-status] [--authentication <user:password>] --get/--put --url <url> --file <file>\n";
}

if ($version >= 1) {
  print $program_name . ", version " . $program_version . "\n";
  exit(0);
} elsif ($help >= 1) {
  usage();
  exit(0);
}

if ($get == $put) {
  print "ERROR: Please specify only one method to use.\n";
  usage();
  exit(1);
} elsif ($url eq "" || $file eq "") {
  print "ERROR: The --url and --file arguments are mandatory!\n";
  usage();
  exit(1);
}

# Build the file basename
my $file_basename = basename($file);

# Create a HTTP Basic authentication string if applicable
$authentication = encode_base64($authentication) if ($authentication ne "");

# Make sure we use SSLv3 at least while connecting to HTTP/S
$ENV{HTTPS_VERSION} = 3;

# Create a new LWP object
my $ua = LWP::UserAgent->new;
if($no_verify) {

  if ( LWP::UserAgent->can("ssl_opts") ) {
    $ua->ssl_opts( verify_hostname => 0, SSL_verify_mode => 0 );
  }

}

if ($put == 1) {
  # PUT

  # Create a variable from the target file
  my $message;
  open(my $fh, '<', $file) or die "ERROR: Unable to open file $file.\n";
  {
      local $/;
      $message = <$fh>;
  }
  close($fh);

  # Send the file using the PUT method
  if ($authentication eq "") {
    $response = $ua->request(PUT $url . $file_basename, Content => $message);
  } else {
    $response = $ua->request(PUT $url . "/" . $file_basename, Authorization => "Basic " . $authentication, Content => $message);
  }

} else {

  # GET

  # Get the file using the GET method
  if ($authentication eq "") {
    $response = $ua->request(GET $url);
  } else {
    $response = $ua->request(GET $url, Authorization => "Basic " . $authentication);
  }

  open my $fh, ">", $file or die("ERROR: Unable to open file $file.\n");
  print $fh $response->content;
}

if ($response->is_success) {
  print "OK (" . $response->status_line . ")\n" if ($debug >= 1);
  print $response->code . "\n" if ($httpstatus >= 1);
  exit(0);
}
else {
  print "FAILED (" . $response->status_line . ")\n" if ($debug >= 1);
  print $response->code . "\n" if ($httpstatus >= 1);
  exit(1);
}
