#!/usr/bin/perl
use warnings;
use strict;

my $exit = 0;

# ordering from cfengine documentation
# convert to a hash { promise_type => order_id }
my $orderings = {
  agent => make_hash(qw/meta vars defaults classes files packages guest_environments methods processes services commands storage databases reports/),
  edit_line => make_hash(qw/meta vars defaults classes delete_lines field_edits insert_lines replace_patterns reports/),
  server => make_hash(qw/vars classes access roles/),
  monitor => make_hash(qw/vars classes measurements reports/),
  common => make_hash(qw/vars classes reports/),
};

while(my $file = shift @ARGV) {
  check_ordering($file);
}
exit $exit;

sub check_ordering {
  # open file
  my $filename = shift;

  open(my $fh, "<$filename") or die "Can't open file $filename";

  # work variables
  my $bundle = "unknown";
  my $bundle_type = "unknown";
  my $type = "";
  
  # scan the file
  while(my $line = <$fh>) {
    $line =~ s/#.*//;
  
    if($line =~ /^\s*bundle\s+(\w+)\s+(\w+)/) {
    # detect bundles
  
      $bundle_type = $1;
      $bundle = $2;
      $type = "";
  
    } elsif($line =~ /\W(\w+):\s*$/) {
    # detect promise type
  
      my $new_type = $1;
      if($type ne "") {
  
        # check ordering
        die "Unknown bundle type $bundle_type" unless exists $orderings->{$bundle_type};
        my $ordering = $orderings->{$bundle_type};
        if (!exists($ordering->{$type})){
          print "Unknown promise type '$type' in '$filename' for 'bundle $bundle_type $bundle'\n";
        } elsif (!exists($ordering->{$new_type})){
          print "Unknown promise type '$new_type' in '$filename' for 'bundle $bundle_type $bundle'\n";
        } elsif($ordering->{$type} > $ordering->{$new_type}) {
          print "Error in '$filename' in 'bundle $bundle_type $bundle' : $type before $new_type\n";
          $exit++;
        }
  
      }
      $type = $new_type;
    }
  }
  close($fh);
}

sub make_hash {
  my $i=1;
  my %ordering = map { $_ => $i++ } @_;
  return \%ordering;
}

