. "$PSScriptRoot/local-common.ps1"
. "$PSScriptRoot/load-env.ps1" -Path $LocalConfig
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js 20.19+ is required on PATH.' }
& node "$LocalProject/examples/rest-demand/live.mjs"
if ($LASTEXITCODE -ne 0) { throw 'External REST integration verification failed.' }
