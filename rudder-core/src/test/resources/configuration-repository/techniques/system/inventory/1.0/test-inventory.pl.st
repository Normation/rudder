#!/usr/bin/perl

use warnings;
use strict;
use XML::TreePP;
use Data::Dumper;

my $file = $ARGV[0];
if(!defined $file || $file eq "") {
  print "Usage check-inventory <filename>\n";
  exit 2;
}

my $tpp = XML::TreePP->new();
my $tree = $tpp->parsefile( $file );
my $os = $tree->{REQUEST}->{CONTENT}->{OPERATINGSYSTEM};

# Test extension
my $rudder = $tree->{REQUEST}->{CONTENT}->{RUDDER};
if(!defined $rudder) {
  print "Inventory ERROR: No RUDDER extension\n";
  exit 1;
}

# Test RUDDER/UUID
my $uuid = $rudder->{UUID};
if(!defined $uuid || $uuid eq "" || ($uuid ne "root" \&\& $uuid !~ /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i)) {
  print "Inventory ERROR: No valid UUID, is your agent properly installed ?\n";
  exit 1;
}

# Test RUDDER/HOSTNAME or OPERATINGSYSTEM/FQDN
my $hostname = $rudder->{HOSTNAME};
my $fqdn = $os->{FQDN};
if( (!defined $hostname || $hostname eq "") \&\& (!defined $fqdn || $fqdn eq "")) {
  print "Inventory ERROR: No RUDDER/HOSTNAME or OPERATINGSYSTEM/FQDN, set a hostname other than 'localhost' and add its name in /etc/hosts\n";
  exit 1;
}

# more than one agent are possible
my @agents;
if(!defined $rudder->{AGENT}) {
  print "Inventory ERROR: No RUDDER/AGENT\n";
  exit 1;
} elsif(ref($rudder->{AGENT}) eq 'HASH') {
  # One RUDDER/AGENT
  @agents = ($rudder->{AGENT});
} else {
  # Many RUDDER/AGENT
  @agents = @{$rudder->{AGENT}};
}
foreach my $agent (@agents) {
  # Test RUDDER/AGENT/OWNER
  my $owner = $agent->{OWNER};
  if(!defined $owner || $owner eq "") {
    print "Inventory ERROR: No RUDDER/AGENT/OWNER\n";
    exit 1;
  }
  
  # Test RUDDER/AGENT/POLICY_SERVER_UUID
  my $server_uuid = $agent->{POLICY_SERVER_UUID};
  if(!defined $server_uuid || $server_uuid eq "" || ($server_uuid ne "root" \&\& $server_uuid !~ /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i)) {
    print "Inventory ERROR: No RUDDER/AGENT/POLICY_SERVER_UUID\n";
    exit 1;
  }
  
  # Test RUDDER/AGENT/AGENT_NAME
  my $agent_name = $agent->{AGENT_NAME};
  if(!defined $agent_name || $agent_name eq "") {
    print "Inventory ERROR: No RUDDER/AGENT/AGENT_NAME\n";
    exit 1;
  }
}

# Test OPERATINGSYSTEM/{FULL_NAME or KERNEL_NAME or NAME}
if(!defined $os->{FULL_NAME} || !defined $os->{KERNEL_NAME} || !defined $os->{NAME}) {
  print "Inventory ERROR: No OPERATINGSYSTEM/FULL_NAME nor OPERATINGSYSTEM/KERNEL_NAME nor OPERATINGSYSTEM/NAME\n";
  exit 1;
}

# Test OPERATINGSYSTEM/KERNEL_VERSION or if OPERATINGSYSTEM/KERNEL_NAME.toLowerCase == aix: HARDWARE/OSVERSION
if(defined $os->{KERNEL_NAME} \&\& lc($os->{KERNEL_NAME}) eq "aix") {
  if(!defined $tree->{REQUEST}->{CONTENT}->{HARDWARE}->{OSVERSION}) {
    print "Inventory ERROR: No HARDWARE/OSVERSION\n";
    exit 1;
  }
} else {
  if(!defined $os->{KERNEL_VERSION}) {
    print "Inventory ERROR: No OPERATINGSYSTEM/KERNEL_VERSION\n";
    exit 1
  }
}

exit 0;

