# Usage: ./release.ps1 1.0.1
# Bumps appVersion, commits, tags v<version> and pushes; GitHub Actions builds and publishes the release.
param([Parameter(Mandatory = $true)][string]$Version)
$ErrorActionPreference = "Stop"
$Version = $Version.TrimStart("v")
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "버전은 X.Y.Z 형식이어야 합니다: $Version" }
$tag = "v$Version"
if (git tag --list $tag) { throw "태그 $tag 가 이미 있습니다" }
$props = Get-Content gradle.properties -Encoding utf8
$props = $props -replace '^appVersion=.*', "appVersion=$Version"
[IO.File]::WriteAllLines((Resolve-Path gradle.properties), $props)
git add gradle.properties
git commit -m "Release $tag"
git tag -a $tag -m "ProSketch $tag"
git push origin HEAD
git push origin $tag
Write-Host "푸시 완료. 빌드 진행: https://github.com/kjt7942/ProSketch/actions"
