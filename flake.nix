{
  description = "Minecraft Parcel - NeoForge 26.2 development environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];

      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
          lib = pkgs.lib;

          java = pkgs.jdk25;
          java21 = pkgs.jdk21;

          nativeLibraries = with pkgs; [
            stdenv.cc.cc.lib
            zlib
            alsa-lib
            libGL
            fontconfig
            freetype
            libxkbcommon
            wayland
            xorg.libX11
            xorg.libXext
            xorg.libXrandr
            xorg.libXrender
            xorg.libXi
            xorg.libXcursor
            xorg.libXfixes
          ];
        in
        {
          default = pkgs.mkShell {
            packages = with pkgs; [
              java
              java21
              gradle
              git
              curl
              wget
              unzip
              zip
              nodejs_22
              python3
              pkg-config
              gcc
              gnumake
            ];

            JAVA_HOME = "${java}";

            LD_LIBRARY_PATH = lib.makeLibraryPath nativeLibraries;

            shellHook = ''
              export PATH="$JAVA_HOME/bin:$PATH"

              echo ""
              echo "======================================"
              echo " Minecraft Parcel Development Shell"
              echo "======================================"
              echo " Java:"
              java -version
              echo ""
              echo " Node:"
              node --version
              echo ""
              echo " Gradle:"
              gradle --version | head -n 8
              echo ""
              echo " Environment ready."
              echo "======================================"
              echo ""
            '';
          };
        });
    };
}
