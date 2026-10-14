{ pkgs, ... }:
{
  packages = [ pkgs.git pkgs.curl pkgs.jq ];
  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk21;
    lsp.enable = false;
  };
  languages.javascript = {
    enable = true;
    package = pkgs.nodejs_24;
  };
  scripts.verify.exec = ''
    set -eu
    ./gradlew check
    npm --prefix interop run check
  '';
}
