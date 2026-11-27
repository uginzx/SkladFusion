# Установка агента печати этикеток SkladFusion на компьютер с принтером.
# Запуск (PowerShell, без прав администратора):
#   irm https://sklad.example.com/agent/install.ps1 | iex
# Спросит имя и PIN владельца (чтобы получить ключ агента) и принтер, поставит автозапуск при входе в Windows.

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
# Две строки ниже сервер подставляет сам (config.php → url, instance): у второго экземпляра склада
# своя папка агента и своя задача Windows, и оба агента работают на одном компьютере.
$server = 'https://sklad.example.com'
$instance = ''
$suffix = $(if ($instance) { "-$instance" } else { '' })
$dir = Join-Path $env:LOCALAPPDATA "SkladPrint$suffix"
$taskName = 'SkladFusion — печать этикеток' + $(if ($instance) { " ($instance)" } else { '' })
New-Item -ItemType Directory -Force $dir | Out-Null
$utf8bom = New-Object Text.UTF8Encoding $true

Write-Host ''
Write-Host 'Агент печати этикеток SkladFusion' -ForegroundColor Cyan

# 1. Ключ агента: по имени и PIN владельца.
$name = Read-Host 'Имя владельца (как в приложении)'
$pin = Read-Host 'PIN'
$login = @{ name = $name; pin = $pin; device = "Агент печати $env:COMPUTERNAME" } | ConvertTo-Json -Compress
$tok = (Invoke-RestMethod -Method Post -Uri "$server/api/login" -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($login))).token
$key = (Invoke-RestMethod -Uri "$server/api/print/key" -Headers @{ Authorization = "Bearer $tok" }).key
# Вход владельца нужен был только для ключа — сразу выходим.
try { Invoke-RestMethod -Method Post -Uri "$server/api/logout" -Headers @{ Authorization = "Bearer $tok" } | Out-Null } catch {}

# 2. Принтер.
$printers = @(Get-Printer | Sort-Object Name)
for ($i = 0; $i -lt $printers.Count; $i++) { Write-Host ("  {0}. {1}" -f ($i + 1), $printers[$i].Name) }
$guess = [Array]::FindIndex($printers, [Predicate[object]] { param($p) $p.Name -match 'xprinter|xp-|label|этикет' })
$prompt = 'Номер принтера этикеток' + $(if ($guess -ge 0) { " [Enter = $($guess + 1)]" } else { '' })
$n = Read-Host $prompt
if ($n -eq '' -and $guess -ge 0) { $n = $guess + 1 }
$printer = $printers[[int]$n - 1].Name

# 3. Скрипт агента и настройки.
$agent = (Invoke-WebRequest -UseBasicParsing -Uri "$server/agent/sklad-print.ps1").Content
if ($agent -is [byte[]]) { $agent = [Text.Encoding]::UTF8.GetString($agent) }
[IO.File]::WriteAllText((Join-Path $dir 'sklad-print.ps1'), $agent, $utf8bom)
[IO.File]::WriteAllText((Join-Path $dir 'config.json'), (@{ server = $server; key = $key; printer = $printer } | ConvertTo-Json), $utf8bom)

# 4. Автозапуск при входе в Windows, скрыто; если упадёт — перезапуск.
$ps = (Get-Command powershell.exe).Source
$action = New-ScheduledTaskAction -Execute $ps -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$dir\sklad-print.ps1`" -Dir `"$dir`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $taskName

Write-Host ''
Write-Host "Готово. Принтер: $printer, сервер: $server" -ForegroundColor Green
Write-Host 'Агент запущен и будет стартовать сам при входе в Windows.'
Write-Host "Журнал: $dir\agent.log"
Write-Host 'Проверка: в приложении «Ещё» → «Печать» должно быть «Принтер на связи».'
