{
  description = "Rudder";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
  inputs.rudder-tools.url = "github:mbaechler/rudder-tools?ref=issue-28801-nix-support";

  outputs =
    { self, ... }@inputs:

    let
      javaVersion = 17; # Change this value to update the whole stack

      supportedSystems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forEachSupportedSystem =
        f:
        inputs.nixpkgs.lib.genAttrs supportedSystems (
          system:
          f {
            pkgs = import inputs.nixpkgs {
              inherit system;
              overlays = [ inputs.self.overlays.default ];
            };
            rudder-tools = inputs.rudder-tools.packages.${system};
          }
        );
    in
    {
      overlays.default =
        final: prev:
        let
          jdk = prev."jdk${toString javaVersion}_headless";
        in
        {
          maven = prev.maven.override { jdk_headless = jdk; };
        };

      devShells = forEachSupportedSystem (
        { pkgs, rudder-tools }:
        let
          fhs = pkgs.buildFHSEnv {
            name = "rudder fhs";
            targetPkgs = pkgs: with pkgs; [
              rudder-tools.rudder-dev
              cacert
              maven
              coreutils
              nodejs_22
              bash
              elmPackages.elm
              elmPackages.elm-format
              elmPackages.elm-test
            ];
          };
        in
        {
          default = fhs.env;
        }
      );
    };
}
