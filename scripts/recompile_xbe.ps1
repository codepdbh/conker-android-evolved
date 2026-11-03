# assets\default.xbe to C++ in generated\ (a build does this by itself when the XBE changes), with the identity checks
# first. A copy that is not a supported release stops here unless -AcceptVariant says the player has read why.
param([switch]$AcceptVariant)
. "$PSScriptRoot\env.ps1"
Set-Location $Root
$xbe = 'assets\default.xbe'
if (-not (Test-Path $xbe)) { throw "no ${xbe}: python scripts\extract_game.py <your.iso> --out assets --only default.xbe" }
python scripts\validate_game.py $xbe
$verdict = $LASTEXITCODE
if ($verdict -eq 2) { throw 'the XBE is unusable' }
cmake -DWHAT=zig-windows -P tools\configure.cmake
cmake --build out\build\zig-windows --target xbe_recomp xbe_symbols
if ($verdict -eq 3) {
    New-Item -ItemType Directory -Force out\compare | Out-Null
    & out\build\zig-windows\xbe_symbols.exe $xbe src\runtime\xdk --out out\compare\game.toml --name game *> out\compare\xbe_symbols.log
    python scripts\compare_config.py $xbe --config src\conker\game.toml --symbols out\compare\game.toml
    if ($LASTEXITCODE) { throw 'this XBE contradicts the config: another build of the game, not recompiled' }
    if (-not $AcceptVariant) { throw 'not a supported release, though nothing contradicts the config. Read docs\RECOMPILATION.md, then run with -AcceptVariant' }
}
& out\build\zig-windows\xbe_recomp.exe src\conker\game.toml --xdk src\runtime\xdk
if ($LASTEXITCODE) { throw "xbe_recomp failed ($LASTEXITCODE)" }
Write-Output "generated\game: $((Get-ChildItem generated\game).Count) files"
