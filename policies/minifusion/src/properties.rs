/*

sub _getCustomProperties {
    my (%params) = @_;
    my $logger   = $params{logger};

    my $custom_properties_dir = ($OSNAME eq 'MSWin32') ? 'C:\Program Files\Rudder\hooks.d' : '/var/rudder/hooks.d';
    my $custom_properties;
    if (-d "$custom_properties_dir") {
        my @custom_properties_list = ();
        my @ordered_script_list = ();
        opendir(DIR, $custom_properties_dir);
        # List each file in the custom_properties directory, each files being a script
        @ordered_script_list = sort readdir(DIR);
        closedir(DIR);
        while (my $file = shift @ordered_script_list) {
            my $script_file = $custom_properties_dir . "/" . $file;
            my $properties;
            if (-f $script_file) {
                next if ($file =~ m/^\./);

                if ($OSNAME eq 'MSWin32') {
                  # We assume the files are non world-writtable on windows
                  # -x does not make sense since the files are just interpreted by the powershell exe

                  $logger->debug("Executing Rudder inventory hook $script_file") if $logger;
                  $properties =  `powershell.exe -file "$script_file"`;
                  my $exit_code = $? >> 8;
                  if ($exit_code > 0) {
                      $logger->error("Script $script_file failed to run properly, with exit code $exit_code") if $logger;
                      next;
                  }
                } else {
                  # Ignore non-executable file, or folders
                  next unless -x $script_file;
                  # Check that the file is owned by current user (or root), and not world writable
                  my $stats = stat($script_file);
                  my $owner = $stats->uid;
                  my $currentUser = $<;

                  # file must be owned by root or current user
                  if (($owner != 0) && ($owner != $currentUser)) {
                      $logger->error("Skipping script $script_file as it is not owned by root nor current user (owner is $owner)") if $logger;
                      next;
                  }

                  my $retMode = $stats->mode;
                  $retMode = $retMode & 0777;
                  if (($retMode & 002) || ($retMode & 020)) {
                      $logger->error("Skipping script $script_file as it is world or group writable") if $logger;
                      next;
                  }

                  # Execute the inventory script
                  $logger->debug2("executing $script_file") if $logger;
                  $properties = qx($script_file);
                  my $exit_code = $? >> 8;
                  if ($exit_code > 0) {
                      $logger->error("Script $script_file failed to run properly, with exit code $exit_code") if $logger;
                      next;
                  }
                }

                # check that it is valid JSON
                eval {
                    my $package = "JSON::PP";
                    $package->require();
                    if ($EVAL_ERROR) {
                        print STDERR
                            "Failed to load JSON module: ($EVAL_ERROR)\n";
                        next;
                    }
                    my $coder = JSON::PP->new;
                    my $propertiesData = $coder->decode($properties);
                    push @custom_properties_list, $coder->encode($propertiesData);
                };
                if ($@) {
                    $logger->error("Script $script_file didn't return valid JSON entry, error is:$@") if $logger;
                    my $rudderTmp = ($OSNAME eq 'MSWin32') ? 'C:\Program Files\Rudder\tmp' : '/var/rudder/tmp';
                    my $filename = "$rudderTmp/inventory-json-error-".time();
                    open(my $fh, '>', $filename);
                    print $fh $properties;
                    close($fh);
                    $logger->error("Invalid JSON data stored in $filename") if $logger;
                }
            }
        }
        $custom_properties = "[". join(",", @custom_properties_list) . "]";
   }
   return $custom_properties;
}


*/
